import CoreGraphics
import Foundation
import ImageIO
import Metal
import MetalFX
import UniformTypeIdentifiers

private enum ValidationFailure: Error, CustomStringConvertible {
    case message(String)

    var description: String {
        switch self {
        case .message(let message):
            return message
        }
    }
}

private struct SyntheticUniforms {
    var centers: SIMD4<Float>
    var parameters: SIMD4<Float>
    var flags: SIMD4<UInt32>
}

private struct Transform {
    var center: SIMD2<Float>
    var angle: Float
}

private struct Scenario {
    var name: String
    var start: Transform
    var middle: Transform
    var end: Transform
    var cameraPrevious: simd_float4x4
    var alphaTest: Bool = false
    var occluder: Bool = false
    var illegalMotion: Bool = false
    var sceneCut: Bool = false
    var historyReset: Bool = false
}

private struct FrameTextures {
    var color: MTLTexture
    var depth: MTLTexture
    var objectMotion: MTLTexture
    var validity: MTLTexture
}

private let syntheticShaderSource = """
#include <metal_stdlib>
using namespace metal;

struct SyntheticUniforms {
    float4 centers;
    float4 parameters;
    uint4 flags;
};

struct VertexOut {
    float4 position [[position]];
};

struct FragmentOut {
    float4 color [[color(0)]];
    half2 objectMotion [[color(1)]];
    float validity [[color(2)]];
    float depth [[depth(any)]];
};

float2 rotatePoint(float2 point, float angle) {
    float sine = sin(angle);
    float cosine = cos(angle);
    return float2(cosine * point.x - sine * point.y,
                  sine * point.x + cosine * point.y);
}

vertex VertexOut synthetic_vs(uint vertexID [[vertex_id]]) {
    const float2 positions[3] = {
        float2(-1.0, -1.0),
        float2( 3.0, -1.0),
        float2(-1.0,  3.0)
    };
    VertexOut output;
    output.position = float4(positions[vertexID], 0.0, 1.0);
    return output;
}

fragment FragmentOut synthetic_fs(
    VertexOut input [[stage_in]],
    constant SyntheticUniforms& uniforms [[buffer(0)]]
) {
    FragmentOut output;
    float2 pixel = input.position.xy;
    float2 currentCenter = uniforms.centers.xy;
    float2 previousCenter = uniforms.centers.zw;
    float currentAngle = uniforms.parameters.x;
    float previousAngle = uniforms.parameters.y;
    float2 viewport = uniforms.parameters.zw;
    float2 local = rotatePoint(pixel - currentCenter, -currentAngle);
    bool insideObject = all(abs(local) <= float2(10.0, 8.0));
    bool insideOccluder = uniforms.flags.y != 0u
        && pixel.x >= viewport.x * 0.45
        && pixel.x <= viewport.x * 0.55
        && pixel.y >= viewport.y * 0.18
        && pixel.y <= viewport.y * 0.82;

    output.color = float4(
        0.06 + 0.18 * pixel.x / viewport.x,
        0.08 + 0.20 * pixel.y / viewport.y,
        0.12,
        1.0
    );
    output.objectMotion = half2(0.0);
    output.validity = 0.0;
    output.depth = 0.20;

    if (insideObject) {
        if (uniforms.flags.x != 0u) {
            uint2 checker = uint2(pixel) / 3u;
            if (((checker.x + checker.y) & 1u) == 0u) {
                discard_fragment();
            }
        }
        float2 previousPixel = previousCenter + rotatePoint(local, previousAngle);
        float2 motion = (previousPixel - pixel) * 2.0 / viewport;
        if (uniforms.flags.z != 0u) {
            motion = float2(NAN, INFINITY);
        }
        output.color = float4(0.92, 0.18 + 0.25 * local.y / 8.0, 0.08, 1.0);
        output.objectMotion = half2(motion);
        output.validity = 1.0;
        output.depth = 0.70;
    }

    // The occluder is closer in reversed-depth space and is a valid static
    // producer. This distinguishes valid zero motion from uncovered pixels.
    if (insideOccluder) {
        output.color = float4(0.18, 0.72, 0.82, 1.0);
        output.objectMotion = half2(0.0);
        output.validity = 1.0;
        output.depth = 0.92;
    }
    return output;
}
"""

private func fail(_ message: String) throws -> Never {
    throw ValidationFailure.message(message)
}

private func require(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    if !condition() {
        try fail(message)
    }
}

private func align(_ value: Int, to alignment: Int) -> Int {
    ((value + alignment - 1) / alignment) * alignment
}

private func bytesPerPixel(_ format: MTLPixelFormat) throws -> Int {
    switch format {
    case .rgba8Unorm, .rgba8Unorm_srgb:
        return 4
    case .rg16Float:
        return 4
    case .r8Unorm:
        return 1
    case .depth32Float:
        return 4
    default:
        try fail("unsupported validation readback format \(format.rawValue)")
    }
}

private final class OffscreenHarness {
    let device: MTLDevice
    let queue: MTLCommandQueue
    let width = 64
    let height = 64
    let temporalWidth = 96
    let temporalHeight = 96
    let pipeline: MTLRenderPipelineState
    let depthState: MTLDepthStencilState

    init() throws {
        guard let device = MTLCreateSystemDefaultDevice() else {
            try fail("MTLCreateSystemDefaultDevice returned nil")
        }
        guard let queue = device.makeCommandQueue() else {
            try fail("could not create Metal command queue")
        }
        self.device = device
        self.queue = queue

        let library: MTLLibrary
        do {
            library = try device.makeLibrary(source: syntheticShaderSource, options: nil)
        } catch {
            try fail("could not compile synthetic MRT shader: \(error)")
        }
        guard let vertex = library.makeFunction(name: "synthetic_vs"),
              let fragment = library.makeFunction(name: "synthetic_fs") else {
            try fail("synthetic MRT entry point is missing")
        }
        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = vertex
        descriptor.fragmentFunction = fragment
        descriptor.colorAttachments[0].pixelFormat = .rgba8Unorm
        descriptor.colorAttachments[1].pixelFormat = .rg16Float
        descriptor.colorAttachments[2].pixelFormat = .r8Unorm
        descriptor.depthAttachmentPixelFormat = .depth32Float
        do {
            self.pipeline = try device.makeRenderPipelineState(descriptor: descriptor)
        } catch {
            try fail("could not create synthetic MRT pipeline: \(error)")
        }
        let depthDescriptor = MTLDepthStencilDescriptor()
        depthDescriptor.depthCompareFunction = .always
        depthDescriptor.isDepthWriteEnabled = true
        guard let depthState = device.makeDepthStencilState(descriptor: depthDescriptor) else {
            try fail("could not create synthetic depth-write state")
        }
        self.depthState = depthState
    }

    func makeTexture(
        format: MTLPixelFormat,
        width: Int,
        height: Int,
        label: String,
        usage: MTLTextureUsage
    ) throws -> MTLTexture {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: format,
            width: width,
            height: height,
            mipmapped: false
        )
        descriptor.storageMode = .private
        descriptor.usage = usage
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            try fail("could not allocate \(label)")
        }
        texture.label = label
        return texture
    }

    func makeFrame(label: String) throws -> FrameTextures {
        FrameTextures(
            color: try makeTexture(
                format: .rgba8Unorm,
                width: width,
                height: height,
                label: "\(label) color",
                usage: [.renderTarget, .shaderRead]
            ),
            depth: try makeTexture(
                format: .depth32Float,
                width: width,
                height: height,
                label: "\(label) depth",
                usage: [.renderTarget, .shaderRead]
            ),
            objectMotion: try makeTexture(
                format: .rg16Float,
                width: width,
                height: height,
                label: "\(label) object motion",
                usage: [.renderTarget, .shaderRead, .shaderWrite]
            ),
            validity: try makeTexture(
                format: .r8Unorm,
                width: width,
                height: height,
                label: "\(label) validity",
                usage: [.renderTarget, .shaderRead, .shaderWrite]
            )
        )
    }

    func render(
        current: Transform,
        previous: Transform,
        scenario: Scenario,
        label: String
    ) throws -> FrameTextures {
        let frame = try makeFrame(label: label)
        guard let commandBuffer = queue.makeCommandBuffer() else {
            try fail("could not create \(label) command buffer")
        }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = frame.color
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].clearColor = MTLClearColor(red: 0.01, green: 0.01, blue: 0.015, alpha: 1.0)
        pass.colorAttachments[0].storeAction = .store
        pass.colorAttachments[1].texture = frame.objectMotion
        pass.colorAttachments[1].loadAction = .clear
        pass.colorAttachments[1].clearColor = MTLClearColor()
        pass.colorAttachments[1].storeAction = .store
        pass.colorAttachments[2].texture = frame.validity
        pass.colorAttachments[2].loadAction = .clear
        pass.colorAttachments[2].clearColor = MTLClearColor()
        pass.colorAttachments[2].storeAction = .store
        pass.depthAttachment.texture = frame.depth
        pass.depthAttachment.loadAction = .clear
        pass.depthAttachment.clearDepth = 0.0
        pass.depthAttachment.storeAction = .store
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
            try fail("could not create \(label) MRT encoder")
        }
        let flags = SIMD4<UInt32>(
            scenario.alphaTest ? 1 : 0,
            scenario.occluder ? 1 : 0,
            scenario.illegalMotion ? 1 : 0,
            0
        )
        var uniforms = SyntheticUniforms(
            centers: SIMD4<Float>(
                current.center.x, current.center.y,
                previous.center.x, previous.center.y
            ),
            parameters: SIMD4<Float>(
                current.angle, previous.angle,
                Float(width), Float(height)
            ),
            flags: flags
        )
        encoder.setRenderPipelineState(pipeline)
        encoder.setDepthStencilState(depthState)
        encoder.setFragmentBytes(
            &uniforms,
            length: MemoryLayout<SyntheticUniforms>.stride,
            index: 0
        )
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
        try commitAndWait(commandBuffer, label: label)
        return frame
    }

    func makeWorkingTexture(
        format: MTLPixelFormat,
        width: Int? = nil,
        height: Int? = nil,
        label: String
    ) throws -> MTLTexture {
        try makeTexture(
            format: format,
            width: width ?? self.width,
            height: height ?? self.height,
            label: label,
            usage: [.renderTarget, .shaderRead, .shaderWrite]
        )
    }

    func clearColor(_ texture: MTLTexture, color: MTLClearColor = MTLClearColor()) throws {
        guard let commandBuffer = queue.makeCommandBuffer() else {
            try fail("could not create clear command buffer for \(texture.label ?? "texture")")
        }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = texture
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].clearColor = color
        pass.colorAttachments[0].storeAction = .store
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
            try fail("could not create clear encoder for \(texture.label ?? "texture")")
        }
        encoder.endEncoding()
        try commitAndWait(commandBuffer, label: "clear \(texture.label ?? "texture")")
    }

    func copyTexture(_ source: MTLTexture, to destination: MTLTexture, label: String) throws {
        guard source.width == destination.width, source.height == destination.height,
              source.pixelFormat == destination.pixelFormat,
              let commandBuffer = queue.makeCommandBuffer(),
              let blit = commandBuffer.makeBlitCommandEncoder() else {
            try fail("could not create \(label) texture copy")
        }
        blit.copy(
            from: source,
            sourceSlice: 0,
            sourceLevel: 0,
            to: destination,
            destinationSlice: 0,
            destinationLevel: 0,
            sliceCount: 1,
            levelCount: 1
        )
        blit.endEncoding()
        try commitAndWait(commandBuffer, label: label)
    }

    func encodeTemporal(
        frame: FrameTextures,
        cameraMotion: MTLTexture,
        objectMotion: MTLTexture,
        validity: MTLTexture,
        disocclusion: MTLTexture,
        mergedMotion: MTLTexture,
        reactive: MTLTexture,
        output: MTLTexture,
        previousViewProjection: simd_float4x4,
        reset: Bool,
        preserveReactiveMask: Bool,
        label: String,
        handDepth: MTLTexture? = nil,
        emitMotionDiagnostics: Bool = true
    ) throws {
        guard let commandBuffer = queue.makeCommandBuffer() else {
            try fail("could not create \(label) temporal command buffer")
        }
        if handDepth != nil && ProcessInfo.processInfo.environment[
            "METALLUM_METALFX_LEGACY_MOTION_PASSES"
        ] == "1" {
            let handResult = metallum_metalfx_encode_hand_overlay(
                commandBuffer,
                handDepth!,
                objectMotion,
                validity,
                reactive,
                Int32(width),
                Int32(height),
                0.35,
                nil
            )
            try require(handResult == 1, "\(label) legacy hand overlay encode was rejected")
        }
        let identity = matrixFloats(matrix_identity_float4x4)
        let previous = matrixFloats(previousViewProjection)
        let result = identity.withUnsafeBufferPointer { currentPointer in
            identity.withUnsafeBufferPointer { inversePointer in
                previous.withUnsafeBufferPointer { previousPointer in
                    metallum_metalfx_encode_v2(
                        commandBuffer,
                        device,
                        frame.color,
                        frame.depth,
                        handDepth,
                        cameraMotion,
                        objectMotion,
                        validity,
                        disocclusion,
                        mergedMotion,
                        reactive,
                        output,
                        currentPointer.baseAddress,
                        inversePointer.baseAddress,
                        previousPointer.baseAddress,
                        nil,
                        0.0,
                        0.0,
                        0.35,
                        Int32(width),
                        Int32(height),
                        reset ? 1 : 0,
                        1,
                        preserveReactiveMask ? 1 : 0,
                        emitMotionDiagnostics ? 1 : 0
                    )
                }
            }
        }
        try require(result == 1, "\(label) MetalFX Temporal encode was rejected")
        try commitAndWait(commandBuffer, label: label)
    }

    func applyCutoutReactive(
        coverage: MTLTexture,
        reactive: MTLTexture,
        radius: Int32,
        label: String
    ) throws {
        guard let commandBuffer = queue.makeCommandBuffer() else {
            try fail("could not create \(label) CUTOUT reactive command buffer")
        }
        let result = metallum_metalfx_apply_cutout_reactive(
            commandBuffer,
            coverage,
            reactive,
            Int32(width),
            Int32(height),
            radius,
            nil
        )
        try require(result == 1, "\(label) CUTOUT reactive dilation was rejected")
        try commitAndWait(commandBuffer, label: label)
    }

    func applyItemEntityTransparencyReactive(
        itemEntity: MTLTexture,
        reactive: MTLTexture,
        label: String
    ) throws {
        guard let commandBuffer = queue.makeCommandBuffer() else {
            try fail("could not create \(label) transparency command buffer")
        }
        let result = metallum_metalfx_mark_transparency(
            commandBuffer,
            device,
            nil,
            itemEntity,
            nil,
            nil,
            nil,
            reactive,
            Int32(width),
            Int32(height)
        )
        try require(result == 1, "\(label) transparency reactive encode was rejected")
        try commitAndWait(commandBuffer, label: label)
    }

    func encodeInterpolation(
        previous: FrameTextures,
        current: FrameTextures,
        motion: MTLTexture,
        output: MTLTexture,
        reset: Bool,
        label: String
    ) throws {
        let ui = try makeWorkingTexture(
            format: .rgba8Unorm,
            label: "\(label) transparent UI"
        )
        try clearColor(ui, color: MTLClearColor(red: 0.0, green: 0.0, blue: 0.0, alpha: 0.0))
        guard let commandBuffer = queue.makeCommandBuffer() else {
            try fail("could not create \(label) interpolation command buffer")
        }
        let result = metallum_metalfx_frame_interpolator_encode_offscreen(
            commandBuffer,
            device,
            current.color,
            previous.color,
            ui,
            current.depth,
            motion,
            output,
            0.0,
            0.0,
            60.0 * .pi / 180.0,
            0.05,
            1000.0,
            Float(width) / Float(height),
            1.0 / 30.0,
            0,
            reset ? 1 : 0,
            1
        )
        try require(result == 1, "\(label) MetalFX Frame Interpolator encode was rejected")
        try commitAndWait(commandBuffer, label: label)
    }

    func commitAndWait(_ commandBuffer: MTLCommandBuffer, label: String) throws {
        commandBuffer.label = label
        commandBuffer.commit()
        commandBuffer.waitUntilCompleted()
        try require(
            commandBuffer.status == .completed,
            "\(label) GPU command buffer failed: \(String(describing: commandBuffer.error))"
        )
    }

    func readback(_ texture: MTLTexture) throws -> [UInt8] {
        let pixelSize = try bytesPerPixel(texture.pixelFormat)
        let compactRow = texture.width * pixelSize
        let paddedRow = align(compactRow, to: 256)
        let length = paddedRow * texture.height
        guard let buffer = device.makeBuffer(length: length, options: .storageModeShared),
              let commandBuffer = queue.makeCommandBuffer(),
              let blit = commandBuffer.makeBlitCommandEncoder() else {
            try fail("could not create readback resources for \(texture.label ?? "texture")")
        }
        blit.copy(
            from: texture,
            sourceSlice: 0,
            sourceLevel: 0,
            sourceOrigin: MTLOrigin(),
            sourceSize: MTLSize(width: texture.width, height: texture.height, depth: 1),
            to: buffer,
            destinationOffset: 0,
            destinationBytesPerRow: paddedRow,
            destinationBytesPerImage: length
        )
        blit.endEncoding()
        try commitAndWait(commandBuffer, label: "readback \(texture.label ?? "texture")")
        let source = buffer.contents().assumingMemoryBound(to: UInt8.self)
        var compact = [UInt8](repeating: 0, count: compactRow * texture.height)
        compact.withUnsafeMutableBytes { destination in
            guard let destinationBase = destination.baseAddress else {
                return
            }
            for row in 0..<texture.height {
                memcpy(
                    destinationBase.advanced(by: row * compactRow),
                    source.advanced(by: row * paddedRow),
                    compactRow
                )
            }
        }
        return compact
    }
}

private func matrixFloats(_ matrix: simd_float4x4) -> [Float] {
    [
        matrix.columns.0.x, matrix.columns.0.y, matrix.columns.0.z, matrix.columns.0.w,
        matrix.columns.1.x, matrix.columns.1.y, matrix.columns.1.z, matrix.columns.1.w,
        matrix.columns.2.x, matrix.columns.2.y, matrix.columns.2.z, matrix.columns.2.w,
        matrix.columns.3.x, matrix.columns.3.y, matrix.columns.3.z, matrix.columns.3.w
    ]
}

private func cameraTranslation(_ x: Float, _ y: Float) -> simd_float4x4 {
    simd_float4x4(
        SIMD4<Float>(1, 0, 0, 0),
        SIMD4<Float>(0, 1, 0, 0),
        SIMD4<Float>(0, 0, 1, 0),
        SIMD4<Float>(x, y, 0, 1)
    )
}

private func cameraRotation(_ angle: Float) -> simd_float4x4 {
    let cosine = cos(angle)
    let sine = sin(angle)
    return simd_float4x4(
        SIMD4<Float>(cosine, sine, 0, 0),
        SIMD4<Float>(-sine, cosine, 0, 0),
        SIMD4<Float>(0, 0, 1, 0),
        SIMD4<Float>(0, 0, 0, 1)
    )
}

private func halfValue(_ low: UInt8, _ high: UInt8) -> Float {
    Float(Float16(bitPattern: UInt16(low) | (UInt16(high) << 8)))
}

private func rgbaVisualization(
    bytes: [UInt8],
    format: MTLPixelFormat,
    width: Int,
    height: Int
) throws -> [UInt8] {
    var rgba = [UInt8](repeating: 0, count: width * height * 4)
    switch format {
    case .rgba8Unorm, .rgba8Unorm_srgb:
        return bytes
    case .r8Unorm:
        for index in 0..<(width * height) {
            let value = bytes[index]
            rgba[index * 4] = value
            rgba[index * 4 + 1] = value
            rgba[index * 4 + 2] = value
            rgba[index * 4 + 3] = 255
        }
    case .depth32Float:
        bytes.withUnsafeBytes { raw in
            let floats = raw.bindMemory(to: Float.self)
            for index in 0..<(width * height) {
                let value = floats[index].isFinite ? min(max(floats[index], 0.0), 1.0) : 0.0
                let byte = UInt8((value * 255.0).rounded())
                rgba[index * 4] = byte
                rgba[index * 4 + 1] = byte
                rgba[index * 4 + 2] = byte
                rgba[index * 4 + 3] = 255
            }
        }
    case .rg16Float:
        for index in 0..<(width * height) {
            let base = index * 4
            let x = halfValue(bytes[base], bytes[base + 1])
            let y = halfValue(bytes[base + 2], bytes[base + 3])
            let red = x.isFinite ? min(max(0.5 + x * 0.5, 0.0), 1.0) : 1.0
            let green = y.isFinite ? min(max(0.5 + y * 0.5, 0.0), 1.0) : 0.0
            rgba[base] = UInt8((red * 255.0).rounded())
            rgba[base + 1] = UInt8((green * 255.0).rounded())
            rgba[base + 2] = (!x.isFinite || !y.isFinite) ? 255 : 32
            rgba[base + 3] = 255
        }
    default:
        try fail("cannot visualize pixel format \(format.rawValue)")
    }
    return rgba
}

private func writePNG(_ rgba: [UInt8], width: Int, height: Int, url: URL) throws {
    let data = Data(rgba)
    guard let provider = CGDataProvider(data: data as CFData),
          let image = CGImage(
              width: width,
              height: height,
              bitsPerComponent: 8,
              bitsPerPixel: 32,
              bytesPerRow: width * 4,
              space: CGColorSpaceCreateDeviceRGB(),
              bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
              provider: provider,
              decode: nil,
              shouldInterpolate: false,
              intent: .defaultIntent
          ),
          let destination = CGImageDestinationCreateWithURL(
              url as CFURL,
              UTType.png.identifier as CFString,
              1,
              nil
          ) else {
        try fail("could not create PNG writer for \(url.path)")
    }
    CGImageDestinationAddImage(destination, image, nil)
    try require(CGImageDestinationFinalize(destination), "could not finalize \(url.path)")
}

@discardableResult
private func exportTexture(
    harness: OffscreenHarness,
    texture: MTLTexture,
    name: String,
    directory: URL
) throws -> [UInt8] {
    let bytes = try harness.readback(texture)
    try Data(bytes).write(to: directory.appendingPathComponent("\(name).bin"), options: .atomic)
    let rgba = try rgbaVisualization(
        bytes: bytes,
        format: texture.pixelFormat,
        width: texture.width,
        height: texture.height
    )
    try writePNG(
        rgba,
        width: texture.width,
        height: texture.height,
        url: directory.appendingPathComponent("\(name).png")
    )
    return bytes
}

private func motionMetrics(motion: [UInt8], validity: [UInt8]) -> [String: Any] {
    var count = 0
    var sumX = 0.0
    var sumY = 0.0
    var maxMagnitude = 0.0
    var invalidCount = 0
    for index in 0..<validity.count where validity[index] > 127 {
        let base = index * 4
        let x = Double(halfValue(motion[base], motion[base + 1]))
        let y = Double(halfValue(motion[base + 2], motion[base + 3]))
        if x.isFinite && y.isFinite {
            count += 1
            sumX += x
            sumY += y
            maxMagnitude = max(maxMagnitude, hypot(x, y))
        } else {
            invalidCount += 1
        }
    }
    return [
        "valid_pixel_count": count,
        "invalid_motion_pixel_count": invalidCount,
        "mean_x": count == 0 ? 0.0 : sumX / Double(count),
        "mean_y": count == 0 ? 0.0 : sumY / Double(count),
        "max_magnitude": maxMagnitude
    ]
}

private func scalarMetrics(_ bytes: [UInt8]) -> [String: Any] {
    guard !bytes.isEmpty else {
        return ["mean": 0.0, "max": 0.0, "nonzero_pixels": 0]
    }
    let sum = bytes.reduce(0) { $0 + Int($1) }
    let maximum = bytes.max() ?? 0
    return [
        "mean": Double(sum) / Double(bytes.count) / 255.0,
        "max": Double(maximum) / 255.0,
        "nonzero_pixels": bytes.count { $0 != 0 }
    ]
}

private func differenceMetrics(
    interpolated: [UInt8],
    groundTruth: [UInt8],
    width: Int,
    height: Int
) -> ([UInt8], [String: Any]) {
    var difference = [UInt8](repeating: 0, count: width * height * 4)
    var absoluteSum = 0.0
    var squaredSum = 0.0
    var maximum = 0
    let channelCount = width * height * 3
    for pixel in 0..<(width * height) {
        for channel in 0..<3 {
            let index = pixel * 4 + channel
            let delta = abs(Int(interpolated[index]) - Int(groundTruth[index]))
            difference[index] = UInt8(delta)
            absoluteSum += Double(delta) / 255.0
            let normalized = Double(delta) / 255.0
            squaredSum += normalized * normalized
            maximum = max(maximum, delta)
        }
        difference[pixel * 4 + 3] = 255
    }
    let mae = absoluteSum / Double(channelCount)
    let mse = squaredSum / Double(channelCount)
    let psnr = mse == 0.0 ? 120.0 : 10.0 * log10(1.0 / mse)
    return (
        difference,
        [
            "mae": mae,
            "mse": mse,
            "psnr_db": psnr,
            "max_channel_error": Double(maximum) / 255.0
        ]
    )
}

private func scenarios() -> [Scenario] {
    let identity = matrix_identity_float4x4
    return [
        Scenario(
            name: "static",
            start: Transform(center: SIMD2<Float>(32, 32), angle: 0),
            middle: Transform(center: SIMD2<Float>(32, 32), angle: 0),
            end: Transform(center: SIMD2<Float>(32, 32), angle: 0),
            cameraPrevious: identity
        ),
        Scenario(
            name: "translation",
            start: Transform(center: SIMD2<Float>(26, 32), angle: 0),
            middle: Transform(center: SIMD2<Float>(32, 32), angle: 0),
            end: Transform(center: SIMD2<Float>(38, 32), angle: 0),
            cameraPrevious: identity
        ),
        Scenario(
            name: "rotation",
            start: Transform(center: SIMD2<Float>(32, 32), angle: -0.55),
            middle: Transform(center: SIMD2<Float>(32, 32), angle: 0),
            end: Transform(center: SIMD2<Float>(32, 32), angle: 0.55),
            cameraPrevious: cameraRotation(0.10)
        ),
        Scenario(
            name: "occlusion_reveal",
            start: Transform(center: SIMD2<Float>(27, 32), angle: 0),
            middle: Transform(center: SIMD2<Float>(35, 32), angle: 0),
            end: Transform(center: SIMD2<Float>(43, 32), angle: 0),
            cameraPrevious: identity,
            occluder: true
        ),
        Scenario(
            name: "alpha_test",
            start: Transform(center: SIMD2<Float>(28, 32), angle: 0),
            middle: Transform(center: SIMD2<Float>(32, 32), angle: 0),
            end: Transform(center: SIMD2<Float>(36, 32), angle: 0),
            cameraPrevious: identity,
            alphaTest: true
        ),
        Scenario(
            name: "scene_cut",
            start: Transform(center: SIMD2<Float>(32, 32), angle: 0),
            middle: Transform(center: SIMD2<Float>(32, 32), angle: 0),
            end: Transform(center: SIMD2<Float>(32, 32), angle: 0),
            cameraPrevious: cameraTranslation(3.0, 0.0),
            sceneCut: true
        ),
        Scenario(
            name: "illegal_motion",
            start: Transform(center: SIMD2<Float>(30, 32), angle: 0),
            middle: Transform(center: SIMD2<Float>(32, 32), angle: 0),
            end: Transform(center: SIMD2<Float>(34, 32), angle: 0),
            cameraPrevious: identity,
            illegalMotion: true
        ),
        Scenario(
            name: "history_reset",
            start: Transform(center: SIMD2<Float>(28, 32), angle: 0),
            middle: Transform(center: SIMD2<Float>(32, 32), angle: 0),
            end: Transform(center: SIMD2<Float>(36, 32), angle: 0),
            cameraPrevious: identity,
            historyReset: true
        )
    ]
}

private func runScenario(
    _ scenario: Scenario,
    harness: OffscreenHarness,
    root: URL
) throws -> [String: Any] {
    let directory = root.appendingPathComponent(scenario.name, isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

    let frame0 = try harness.render(
        current: scenario.start,
        previous: scenario.start,
        scenario: scenario,
        label: "\(scenario.name) t0"
    )
    let groundTruth = try harness.render(
        current: scenario.middle,
        previous: scenario.start,
        scenario: scenario,
        label: "\(scenario.name) t0.5 ground truth"
    )
    let frame1 = try harness.render(
        current: scenario.end,
        previous: scenario.start,
        scenario: scenario,
        label: "\(scenario.name) t1"
    )

    let cameraMotion = try harness.makeWorkingTexture(format: .rg16Float, label: "\(scenario.name) camera motion")
    let disocclusion = try harness.makeWorkingTexture(format: .r8Unorm, label: "\(scenario.name) disocclusion")
    let mergedMotion = try harness.makeWorkingTexture(format: .rg16Float, label: "\(scenario.name) merged motion")
    let reactive = try harness.makeWorkingTexture(format: .r8Unorm, label: "\(scenario.name) reactive")
    let temporalOutput = try harness.makeWorkingTexture(
        format: .rgba8Unorm,
        width: harness.temporalWidth,
        height: harness.temporalHeight,
        label: "\(scenario.name) temporal output"
    )

    try harness.clearColor(reactive)
    if scenario.alphaTest {
        try harness.applyCutoutReactive(
            coverage: frame0.validity,
            reactive: reactive,
            radius: 1,
            label: "\(scenario.name) CUTOUT t0"
        )
    }
    try harness.encodeTemporal(
        frame: frame0,
        cameraMotion: cameraMotion,
        objectMotion: frame0.objectMotion,
        validity: frame0.validity,
        disocclusion: disocclusion,
        mergedMotion: mergedMotion,
        reactive: reactive,
        output: temporalOutput,
        previousViewProjection: matrix_identity_float4x4,
        reset: true,
        preserveReactiveMask: scenario.alphaTest,
        label: "\(scenario.name) temporal t0"
    )
    try harness.clearColor(reactive)
    if scenario.alphaTest {
        try harness.applyCutoutReactive(
            coverage: frame1.validity,
            reactive: reactive,
            radius: 1,
            label: "\(scenario.name) CUTOUT t1"
        )
    }
    try harness.encodeTemporal(
        frame: frame1,
        cameraMotion: cameraMotion,
        objectMotion: frame1.objectMotion,
        validity: frame1.validity,
        disocclusion: disocclusion,
        mergedMotion: mergedMotion,
        reactive: reactive,
        output: temporalOutput,
        previousViewProjection: scenario.cameraPrevious,
        reset: scenario.sceneCut || scenario.historyReset,
        preserveReactiveMask: scenario.alphaTest,
        label: "\(scenario.name) temporal t1"
    )

    let interpolatedOutput = try harness.makeWorkingTexture(
        format: .rgba8Unorm,
        label: "\(scenario.name) interpolated output"
    )
    try harness.encodeInterpolation(
        previous: frame0,
        current: frame1,
        motion: mergedMotion,
        output: interpolatedOutput,
        reset: scenario.sceneCut || scenario.historyReset,
        label: scenario.name
    )

    _ = try exportTexture(harness: harness, texture: frame0.color, name: "input_color_t0", directory: directory)
    _ = try exportTexture(harness: harness, texture: frame1.color, name: "input_color_t1", directory: directory)
    let depthBytes = try exportTexture(harness: harness, texture: frame1.depth, name: "depth", directory: directory)
    let cameraBytes = try exportTexture(harness: harness, texture: cameraMotion, name: "camera_motion", directory: directory)
    let objectBytes = try exportTexture(harness: harness, texture: frame1.objectMotion, name: "object_motion", directory: directory)
    let validityBytes = try exportTexture(harness: harness, texture: frame1.validity, name: "object_validity", directory: directory)
    if scenario.alphaTest {
        _ = try exportTexture(
            harness: harness,
            texture: frame1.validity,
            name: "cutout_coverage",
            directory: directory
        )
    }
    let mergedBytes = try exportTexture(harness: harness, texture: mergedMotion, name: "merged_motion", directory: directory)
    let disocclusionBytes = try exportTexture(harness: harness, texture: disocclusion, name: "disocclusion", directory: directory)
    let reactiveBytes = try exportTexture(harness: harness, texture: reactive, name: "reactive", directory: directory)
    _ = try exportTexture(harness: harness, texture: temporalOutput, name: "temporal_output", directory: directory)
    let interpolatedBytes = try exportTexture(harness: harness, texture: interpolatedOutput, name: "interpolated_output", directory: directory)
    let truthBytes = try exportTexture(harness: harness, texture: groundTruth.color, name: "ground_truth_t0_5", directory: directory)

    let (difference, differenceValues) = differenceMetrics(
        interpolated: interpolatedBytes,
        groundTruth: truthBytes,
        width: harness.width,
        height: harness.height
    )
    try Data(difference).write(
        to: directory.appendingPathComponent("difference.bin"),
        options: .atomic
    )
    try writePNG(
        difference,
        width: harness.width,
        height: harness.height,
        url: directory.appendingPathComponent("difference.png")
    )

    var expectedMeanX = 0.0
    var expectedMeanY = 0.0
    if !scenario.illegalMotion {
        expectedMeanX = Double((scenario.start.center.x - scenario.end.center.x) * 2.0 / Float(harness.width))
        expectedMeanY = Double((scenario.start.center.y - scenario.end.center.y) * 2.0 / Float(harness.height))
    }
    let metrics: [String: Any] = [
        "scenario": scenario.name,
        "dimensions": [
            "input_width": harness.width,
            "input_height": harness.height,
            "temporal_width": harness.temporalWidth,
            "temporal_height": harness.temporalHeight
        ],
        "expected_object_translation_motion": [
            "x": expectedMeanX,
            "y": expectedMeanY
        ],
        "object_motion": motionMetrics(motion: objectBytes, validity: validityBytes),
        "camera_motion": motionMetrics(
            motion: cameraBytes,
            validity: [UInt8](repeating: 255, count: harness.width * harness.height)
        ),
        "merged_motion": motionMetrics(
            motion: mergedBytes,
            validity: [UInt8](repeating: 255, count: harness.width * harness.height)
        ),
        "validity": scalarMetrics(validityBytes),
        "disocclusion": scalarMetrics(disocclusionBytes),
        "reactive": scalarMetrics(reactiveBytes),
        "frame_interpolation_difference": differenceValues,
        "history_reset": scenario.sceneCut || scenario.historyReset,
        "illegal_motion_injected": scenario.illegalMotion,
        "depth_readback_bytes": depthBytes.count
    ]
    let json = try JSONSerialization.data(
        withJSONObject: metrics,
        options: [.prettyPrinted, .sortedKeys]
    )
    try json.write(to: directory.appendingPathComponent("metrics.json"), options: .atomic)

    if scenario.name == "static" {
        let object = motionMetrics(motion: objectBytes, validity: validityBytes)
        try require(
            abs((object["mean_x"] as? Double) ?? 1.0) < 0.01
                && abs((object["mean_y"] as? Double) ?? 1.0) < 0.01,
            "static valid object did not produce zero motion"
        )
    }
    if scenario.name == "translation" {
        let object = motionMetrics(motion: objectBytes, validity: validityBytes)
        let actual = (object["mean_x"] as? Double) ?? 0.0
        try require(
            abs(actual - expectedMeanX) < 0.03,
            "translation object motion mismatch: expected \(expectedMeanX), got \(actual)"
        )
    }
    if scenario.name == "rotation" {
        let camera = motionMetrics(
            motion: cameraBytes,
            validity: [UInt8](repeating: 255, count: harness.width * harness.height)
        )
        try require(
            ((camera["max_magnitude"] as? Double) ?? 0.0) > 0.02,
            "camera rotation did not produce non-zero camera motion"
        )
    }
    if scenario.illegalMotion {
        let reactiveValues = scalarMetrics(reactiveBytes)
        try require(
            ((reactiveValues["max"] as? Double) ?? 0.0) > 0.99,
            "illegal object motion did not force reactive rejection"
        )
    }
    if scenario.alphaTest {
        try require(
            validityBytes.contains(0) && validityBytes.contains(where: { $0 > 127 }),
            "alpha-test case did not preserve invalid holes and valid object pixels"
        )
        // Static/alpha-tested coverage has depth and motion, so it should use
        // normal temporal accumulation. Reactive values are reserved for the
        // exceptional pixels the motion pass confirms as disoccluded; there
        // must be no standing silhouette band and no full suppression.
        var edgeBandReactivePixels = 0
        var fullSuppressionPixels = 0
        var coveredPixels = 0
        for pixel in validityBytes.indices where validityBytes[pixel] > 127 {
            coveredPixels += 1
            if reactiveBytes[pixel] >= 72 {
                edgeBandReactivePixels += 1
            }
            // 224/255 sits above the 0.85 disocclusion cap (217) and below 1.0.
            if reactiveBytes[pixel] > 224 {
                fullSuppressionPixels += 1
            }
        }
        try require(
            edgeBandReactivePixels < max(1, coveredPixels / 2),
            "CUTOUT coverage still carries a standing reactive silhouette band"
                + " (\(edgeBandReactivePixels)/\(coveredPixels) pixels)"
        )
        try require(
            fullSuppressionPixels == 0,
            "CUTOUT coverage still writes full reactive suppression"
                + " (\(fullSuppressionPixels) pixels above 224/255)"
        )
    }
    if scenario.occluder {
        let disocclusionValues = scalarMetrics(disocclusionBytes)
        try require(
            ((disocclusionValues["nonzero_pixels"] as? Int) ?? 0) > 0,
            "occlusion reveal did not produce a previous-depth disocclusion response"
        )
    }
    if scenario.sceneCut {
        let disocclusionValues = scalarMetrics(disocclusionBytes)
        try require(
            ((disocclusionValues["mean"] as? Double) ?? 0.0) > 0.95,
            "scene cut did not reject prior history"
        )
    }
    try require(
        ((differenceValues["mae"] as? Double) ?? 1.0) < 0.05,
        "\(scenario.name) frame interpolation MAE exceeded 0.05"
    )
    return metrics
}

private func runHandFusionScenario(
    harness: OffscreenHarness,
    root: URL
) throws -> [String: Any] {
    let scenario = Scenario(
        name: "hand_fusion_steady",
        start: Transform(center: SIMD2<Float>(26, 32), angle: -0.2),
        middle: Transform(center: SIMD2<Float>(32, 32), angle: 0.0),
        end: Transform(center: SIMD2<Float>(38, 32), angle: 0.2),
        cameraPrevious: matrix_identity_float4x4
    )
    let directory = root.appendingPathComponent(scenario.name, isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let frame = try harness.render(
        current: scenario.end,
        previous: scenario.start,
        scenario: scenario,
        label: "hand fusion input"
    )
    let handDepth = try harness.makeWorkingTexture(
        format: .r8Unorm,
        label: "hand fusion depth coverage"
    )
    try harness.copyTexture(frame.validity, to: handDepth, label: "hand fusion depth coverage copy")
    let cameraMotion = try harness.makeWorkingTexture(format: .rg16Float, label: "hand fusion camera motion")
    let disocclusion = try harness.makeWorkingTexture(format: .r8Unorm, label: "hand fusion disocclusion")
    let mergedMotion = try harness.makeWorkingTexture(format: .rg16Float, label: "hand fusion merged motion")
    let reactive = try harness.makeWorkingTexture(format: .r8Unorm, label: "hand fusion reactive")
    let temporalOutput = try harness.makeWorkingTexture(
        format: .rgba8Unorm,
        width: harness.temporalWidth,
        height: harness.temporalHeight,
        label: "hand fusion temporal output"
    )
    try harness.clearColor(reactive)
    try harness.encodeTemporal(
        frame: frame,
        cameraMotion: cameraMotion,
        objectMotion: frame.objectMotion,
        validity: frame.validity,
        disocclusion: disocclusion,
        mergedMotion: mergedMotion,
        reactive: reactive,
        output: temporalOutput,
        previousViewProjection: matrix_identity_float4x4,
        reset: true,
        preserveReactiveMask: false,
        label: "hand fusion production temporal",
        handDepth: handDepth,
        emitMotionDiagnostics: false
    )

    let handBytes = try exportTexture(
        harness: harness,
        texture: handDepth,
        name: "hand_depth",
        directory: directory
    )
    let motionBytes = try exportTexture(
        harness: harness,
        texture: mergedMotion,
        name: "merged_motion",
        directory: directory
    )
    let reactiveBytes = try exportTexture(
        harness: harness,
        texture: reactive,
        name: "reactive",
        directory: directory
    )
    let handMotion = motionMetrics(motion: motionBytes, validity: handBytes)
    let covered = handBytes.indices.filter { handBytes[$0] > 127 }
    let minimumReactive = covered.map { reactiveBytes[$0] }.min() ?? 0
    try require(!covered.isEmpty, "hand fusion scenario produced no hand coverage")
    try require(
        ((handMotion["max_magnitude"] as? Double) ?? 1.0) < 0.0001,
        "fused hand path did not override object motion with camera-locked zero motion"
    )
    try require(
        minimumReactive >= 88,
        "fused hand path did not preserve the 0.35 reactive boost"
    )
    return [
        "scenario": scenario.name,
        "hand_pixels": covered.count,
        "hand_motion": handMotion,
        "minimum_hand_reactive_byte": minimumReactive,
        "history_reset": true
    ]
}

private func runTransparencyAlphaContract(
    harness: OffscreenHarness,
    root: URL
) throws -> [String: Any] {
    let directory = root.appendingPathComponent("transparency_alpha_contract", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let itemEntity = try harness.makeWorkingTexture(
        format: .rgba8Unorm,
        label: "transparency alpha contract item entity"
    )
    let reactive = try harness.makeWorkingTexture(
        format: .r8Unorm,
        label: "transparency alpha contract reactive"
    )

    // Colored zero-alpha texels do not contribute to alpha blending. Treating
    // RGB presence as reactive rejects useful history and exposes shimmer.
    try harness.clearColor(
        itemEntity,
        color: MTLClearColor(red: 1.0, green: 0.2, blue: 0.1, alpha: 0.0)
    )
    try harness.clearColor(reactive)
    try harness.applyItemEntityTransparencyReactive(
        itemEntity: itemEntity,
        reactive: reactive,
        label: "zero-alpha colored item entity"
    )
    let zeroAlpha = try exportTexture(
        harness: harness,
        texture: reactive,
        name: "zero_alpha_reactive",
        directory: directory
    )
    try require(
        zeroAlpha.allSatisfy { $0 == 0 },
        "colored zero-alpha texels incorrectly produced a reactive mask"
    )

    // Entity shadows use alpha around 0.4. The production mask preserves that
    // strength and applies the 0.9 cap, yielding 0.36 (about 92/255), rather
    // than converting every shadow pixel into full history rejection.
    try harness.clearColor(
        itemEntity,
        color: MTLClearColor(red: 0.0, green: 0.0, blue: 0.0, alpha: 0.4)
    )
    try harness.clearColor(reactive)
    try harness.applyItemEntityTransparencyReactive(
        itemEntity: itemEntity,
        reactive: reactive,
        label: "partial-alpha item entity"
    )
    let partialAlpha = try exportTexture(
        harness: harness,
        texture: reactive,
        name: "partial_alpha_reactive",
        directory: directory
    )
    let minimum = partialAlpha.min() ?? 0
    let maximum = partialAlpha.max() ?? 0
    try require(
        minimum >= 90 && maximum <= 93,
        "0.4-alpha transparency did not preserve scaled strength"
            + " (expected about 92/255, got \(minimum)...\(maximum))"
    )
    return [
        "scenario": "transparency_alpha_contract",
        "zero_alpha_maximum_byte": zeroAlpha.max() ?? 0,
        "partial_alpha_minimum_byte": minimum,
        "partial_alpha_maximum_byte": maximum,
        "expected_partial_alpha_byte": 92
    ]
}

private func runV3InputContract(
    harness: OffscreenHarness,
    root: URL
) throws -> [String: Any] {
    try require(
        metallum_residency_set_enable(harness.device, harness.queue) == 1,
        "could not enable the residency set for v3 history validation"
    )
    func residencyCount(_ label: String) throws -> UInt32 {
        var allocations: UInt32 = 0
        var bytes: UInt64 = 0
        try require(
            metallum_residency_set_stats(&allocations, &bytes) == 1,
            "could not read residency count at \(label)"
        )
        return allocations
    }
    func flushResidency(_ label: String) throws {
        guard let commandBuffer = harness.queue.makeCommandBuffer() else {
            try fail("could not create \(label) residency flush command buffer")
        }
        metallum_MTLCommandBuffer_commit(Unmanaged.passUnretained(commandBuffer).toOpaque())
        commandBuffer.waitUntilCompleted()
        try require(
            commandBuffer.status == .completed,
            "\(label) residency flush failed: \(String(describing: commandBuffer.error))"
        )
    }
    let residencyBaseline = try residencyCount("v3 baseline")

    let scenario = Scenario(
        name: "v3_input_contract",
        start: Transform(center: SIMD2(112, 88), angle: 0),
        middle: Transform(center: SIMD2(116, 88), angle: 0),
        end: Transform(center: SIMD2(120, 88), angle: 0),
        cameraPrevious: matrix_identity_float4x4
    )
    let frame = try harness.render(
        current: scenario.middle,
        previous: scenario.start,
        scenario: scenario,
        label: "v3 input contract source"
    )
    let color = try harness.makeWorkingTexture(
        format: .rgba8Unorm_srgb, label: "v3 input contract sRGB color"
    )
    let output = try harness.makeWorkingTexture(
        format: .rgba8Unorm_srgb, label: "v3 input contract sRGB output"
    )
    let cameraMotion = try harness.makeWorkingTexture(
        format: .rg16Float, label: "v3 input contract camera motion"
    )
    let disocclusion = try harness.makeWorkingTexture(
        format: .r8Unorm, label: "v3 input contract disocclusion"
    )
    let motion = try harness.makeWorkingTexture(
        format: .rg16Float, label: "v3 input contract finalized motion"
    )
    let reactive = try harness.makeWorkingTexture(
        format: .r8Unorm, label: "v3 input contract reactive"
    )
    try harness.clearColor(color, color: MTLClearColor(red: 0.2, green: 0.25, blue: 0.3, alpha: 1))
    try harness.clearColor(output)
    try harness.clearColor(cameraMotion)
    try harness.clearColor(disocclusion)
    try harness.clearColor(motion)
    try harness.clearColor(reactive)
    guard let fence = harness.device.makeFence() else {
        try fail("could not create v3 input contract fence")
    }
    let frameID: UInt64 = 10_001
    let historyEpoch: UInt64 = 701
    let identity = matrixFloats(matrix_identity_float4x4)

    guard let staleCommandBuffer = harness.queue.makeCommandBuffer() else {
        try fail("could not create stale-motion contract command buffer")
    }
    let staleToken = metallum_metalfx_temporal_encode_v3(
        staleCommandBuffer, harness.device, color, frame.depth, motion, reactive, nil, output, fence,
        0, 0, .nan, Int32(harness.width), Int32(harness.height), 1, 1, 1, 0,
        frameID, historyEpoch
    )
    try require(staleToken == 0, "v3 Temporal accepted motion that was never finalized")

    guard let invalidExposureCommandBuffer = harness.queue.makeCommandBuffer() else {
        try fail("could not create invalid-exposure contract command buffer")
    }
    let invalidExposureToken = metallum_metalfx_temporal_encode_v3(
        invalidExposureCommandBuffer, harness.device, color, frame.depth, motion, reactive, nil, output, fence,
        0, 0, .nan, Int32(harness.width), Int32(harness.height), 1, 1, 1, 1,
        frameID, historyEpoch
    )
    try require(
        invalidExposureToken == 0,
        "v3 Temporal accepted AUTO exposure for FINAL_LDR_SRGB"
    )

    guard let commandBuffer = harness.queue.makeCommandBuffer() else {
        try fail("could not create v3 split motion/Temporal command buffer")
    }
    let finalized = identity.withUnsafeBufferPointer { current in
        identity.withUnsafeBufferPointer { inverse in
            identity.withUnsafeBufferPointer { previous in
                metallum_metalfx_motion_finalize_v3(
                    commandBuffer, harness.device, frame.depth, nil, cameraMotion,
                    frame.objectMotion, frame.validity, disocclusion, motion, reactive,
                    current.baseAddress, inverse.baseAddress, previous.baseAddress, fence,
                    0.35, Int32(harness.width), Int32(harness.height), 1, 1, 0, 1,
                    frameID, historyEpoch
                )
            }
        }
    }
    try require(finalized == 1, "v3 motion finalize was rejected")
    let sceneLinearColor = try harness.makeWorkingTexture(
        format: .rgba16Float,
        label: "v3 scene-linear color"
    )
    let sceneLinearOutput = try harness.makeWorkingTexture(
        format: .rgba16Float,
        label: "v3 scene-linear output"
    )
    let invalidExposureDescriptor = MTLTextureDescriptor.texture2DDescriptor(
        pixelFormat: .r16Float,
        width: 1,
        height: 1,
        mipmapped: false
    )
    invalidExposureDescriptor.textureType = .type2DArray
    invalidExposureDescriptor.arrayLength = 2
    invalidExposureDescriptor.storageMode = .private
    invalidExposureDescriptor.usage = [.shaderRead]
    guard let invalidArrayExposure = harness.device.makeTexture(
        descriptor: invalidExposureDescriptor
    ) else {
        try fail("could not create the invalid manual-exposure array texture")
    }
    let invalidManualExposureToken = metallum_metalfx_temporal_encode_v3(
        commandBuffer, harness.device, sceneLinearColor, frame.depth, motion, reactive,
        invalidArrayExposure, sceneLinearOutput, fence,
        0, 0, 1.0, Int32(harness.width), Int32(harness.height), 1, 1, 2, 2,
        frameID, historyEpoch
    )
    try require(
        invalidManualExposureToken == 0,
        "v3 Temporal accepted an array texture for the 1x1 manual exposure contract"
    )
    let scalerToken = metallum_metalfx_temporal_encode_v3(
        commandBuffer, harness.device, color, frame.depth, motion, reactive, nil, output, fence,
        0, 0, .nan, Int32(harness.width), Int32(harness.height), 1, 1, 1, 0,
        frameID, historyEpoch
    )
    try require(scalerToken != 0, "v3 Temporal rejected same-stamp finalized motion")
    commandBuffer.label = "v3 split motion and Temporal"
    commandBuffer.commit()
    try require(
        metallum_metalfx_history_commit_v3(frameID, historyEpoch) == 1,
        "v3 history commit rejected the encoded frame"
    )
    commandBuffer.waitUntilCompleted()
    try require(
        commandBuffer.status == .completed,
        "v3 split motion and Temporal failed: \(String(describing: commandBuffer.error))"
    )
    let outputBytes = try exportTexture(
        harness: harness, texture: output, name: "v3_temporal_output", directory: root
    )
    try require(outputBytes.contains { $0 != 0 }, "v3 Temporal output was entirely zero")

    guard let failureGate = harness.device.makeSharedEvent(),
          let failureCommandBuffer = harness.queue.makeCommandBuffer() else {
        try fail("could not create v3 submit/failure lifecycle resources")
    }
    failureCommandBuffer.encodeWaitForEvent(failureGate, value: 1)
    let failureFrameID: UInt64 = 20_001
    let failureEpoch: UInt64 = 702
    let failureFinalized = identity.withUnsafeBufferPointer { current in
        identity.withUnsafeBufferPointer { inverse in
            identity.withUnsafeBufferPointer { previous in
                metallum_metalfx_motion_finalize_v3(
                    failureCommandBuffer, harness.device, frame.depth, nil, cameraMotion,
                    frame.objectMotion, frame.validity, disocclusion, motion, reactive,
                    current.baseAddress, inverse.baseAddress, previous.baseAddress, fence,
                    0.35, Int32(harness.width), Int32(harness.height), 1, 1, 0, 0,
                    failureFrameID, failureEpoch
                )
            }
        }
    }
    try require(failureFinalized == 1, "v3 failure-lifecycle motion finalize was rejected")
    failureCommandBuffer.commit()
    try require(
        metallum_metalfx_history_commit_v3(failureFrameID, failureEpoch) == 1,
        "v3 failure-lifecycle submit acceptance was rejected"
    )
    try require(
        metallum_metalfx_history_previous_is_valid_for_testing(
            harness.device, frame.depth, failureFrameID + 1, failureEpoch
        ),
        "accepted v3 submit did not make previous depth available"
    )
    metallum_metalfx_history_discard_v3(failureFrameID, failureEpoch)
    try require(
        !metallum_metalfx_history_previous_is_valid_for_testing(
            harness.device, frame.depth, failureFrameID + 1, failureEpoch
        ),
        "GPU failure discard left accepted previous-depth history valid"
    )
    guard let poisonedEpochBuffer = harness.queue.makeCommandBuffer() else {
        try fail("could not create poisoned-epoch contract command buffer")
    }
    let poisonedEpochFinalize = identity.withUnsafeBufferPointer { current in
        identity.withUnsafeBufferPointer { inverse in
            identity.withUnsafeBufferPointer { previous in
                metallum_metalfx_motion_finalize_v3(
                    poisonedEpochBuffer, harness.device, frame.depth, nil, cameraMotion,
                    frame.objectMotion, frame.validity, disocclusion, motion, reactive,
                    current.baseAddress, inverse.baseAddress, previous.baseAddress, fence,
                    0.35, Int32(harness.width), Int32(harness.height), 0, 1, 0, 0,
                    failureFrameID + 1, failureEpoch
                )
            }
        }
    }
    try require(
        poisonedEpochFinalize == 0,
        "motion finalize reopened an epoch after an accepted command buffer failed"
    )
    failureGate.signaledValue = 1
    failureCommandBuffer.waitUntilCompleted()
    try require(
        failureCommandBuffer.status == .completed,
        "v3 failure-lifecycle gated command buffer did not drain"
    )
    try require(
        metallum_metalfx_history_tracking_count_for_testing() == 0,
        "discard followed by GPU completion leaked v3 history transaction tracking"
    )

    guard let oldEpochGate = harness.device.makeSharedEvent(),
          let oldEpochBuffer = harness.queue.makeCommandBuffer(),
          let newEpochBuffer = harness.queue.makeCommandBuffer() else {
        try fail("could not create cross-epoch history lifecycle resources")
    }
    oldEpochBuffer.encodeWaitForEvent(oldEpochGate, value: 1)
    let oldStamp = (frame: UInt64(30_001), epoch: UInt64(703))
    let newStamp = (frame: UInt64(30_002), epoch: UInt64(704))
    func finalizeLifecycleBuffer(
        _ buffer: MTLCommandBuffer,
        frameID: UInt64,
        epoch: UInt64
    ) -> Int32 {
        identity.withUnsafeBufferPointer { current in
            identity.withUnsafeBufferPointer { inverse in
                identity.withUnsafeBufferPointer { previous in
                    metallum_metalfx_motion_finalize_v3(
                        buffer, harness.device, frame.depth, nil, cameraMotion,
                        frame.objectMotion, frame.validity, disocclusion, motion, reactive,
                        current.baseAddress, inverse.baseAddress, previous.baseAddress, fence,
                        0.35, Int32(harness.width), Int32(harness.height), 1, 1, 0, 0,
                        frameID, epoch
                    )
                }
            }
        }
    }
    try require(
        finalizeLifecycleBuffer(oldEpochBuffer, frameID: oldStamp.frame, epoch: oldStamp.epoch) == 1,
        "old-epoch lifecycle finalize was rejected"
    )
    try require(
        metallum_metalfx_history_reset_required_for_testing(oldStamp.frame, oldStamp.epoch),
        "first frame after a poisoned epoch did not force MetalFX history reset"
    )
    oldEpochBuffer.commit()
    try require(
        metallum_metalfx_history_commit_v3(oldStamp.frame, oldStamp.epoch) == 1,
        "old-epoch lifecycle commit was rejected"
    )
    try require(
        finalizeLifecycleBuffer(newEpochBuffer, frameID: newStamp.frame, epoch: newStamp.epoch) == 1,
        "new-epoch lifecycle finalize was rejected"
    )
    newEpochBuffer.commit()
    try require(
        metallum_metalfx_history_commit_v3(newStamp.frame, newStamp.epoch) == 1,
        "new-epoch lifecycle commit was rejected"
    )
    metallum_metalfx_history_discard_v3(oldStamp.frame, oldStamp.epoch)
    try require(
        metallum_metalfx_history_previous_is_valid_for_testing(
            harness.device, frame.depth, newStamp.frame + 1, newStamp.epoch
        ),
        "late old-epoch failure invalidated accepted new-epoch previous depth"
    )
    oldEpochGate.signaledValue = 1
    oldEpochBuffer.waitUntilCompleted()
    newEpochBuffer.waitUntilCompleted()
    try require(
        oldEpochBuffer.status == .completed && newEpochBuffer.status == .completed,
        "cross-epoch lifecycle command buffers did not drain"
    )
    try require(
        metallum_metalfx_history_tracking_count_for_testing() == 0,
        "cross-epoch lifecycle leaked v3 transaction tracking"
    )
    try flushResidency("v3 history additions")
    let residencyWithHistory = try residencyCount("v3 history allocated")
    try require(
        residencyWithHistory > residencyBaseline,
        "v3 history allocation was not tracked by the residency set"
    )
    metallum_metalfx_release_scalers()
    try flushResidency("v3 history release")
    let residencyAfterRelease = try residencyCount("v3 history released")
    try require(
        residencyAfterRelease == residencyBaseline,
        "releasing MetalFX history left stale residency allocations"
    )
    return [
        "scenario": "v3_input_contract",
        "stale_motion_rejected": true,
        "invalid_exposure_rejected": true,
        "invalid_manual_exposure_shape_rejected": true,
        "scaler_token": scalerToken,
        "history_commit_accepted": true,
        "accepted_then_failed_history_invalidated": true,
        "failed_epoch_rejected_until_advanced": true,
        "post_failure_epoch_forced_reset": true,
        "late_old_epoch_failure_preserved_new_epoch": true,
        "history_residency_released": true
    ]
}

private func runSrgbTextureViewContract(
    harness: OffscreenHarness
) throws -> [String: Any] {
    let base = try harness.makeTexture(
        format: .rgba8Unorm,
        width: 8,
        height: 8,
        label: "sRGB view byte-preserving base",
        usage: [.renderTarget, .shaderRead, .pixelFormatView]
    )
    try harness.clearColor(
        base,
        color: MTLClearColor(red: 0.25, green: 0.5, blue: 0.75, alpha: 1.0)
    )
    guard let viewPointer = metallum_create_texture_view_v2(base, .rgba8Unorm_srgb, 0, 1) else {
        try fail("RGBA8Unorm_sRGB view creation was rejected")
    }
    let viewObject = Unmanaged<AnyObject>.fromOpaque(viewPointer).takeRetainedValue()
    guard let srgbView = viewObject as? MTLTexture else {
        try fail("sRGB view symbol did not return an MTLTexture")
    }
    try require(srgbView.pixelFormat == .rgba8Unorm_srgb, "sRGB view has the wrong pixel format")
    let baseBytes = try harness.readback(base)
    let viewBytes = try harness.readback(srgbView)
    try require(
        baseBytes == viewBytes,
        "sRGB texture view changed stored bytes (double-gamma or copy conversion)"
    )

    guard let linearPresentView = metalLinearPresentTexture(
        srgbView,
        label: "linear present view"
    ) else {
        try fail("could not create the linear present view from the sRGB base")
    }
    try require(
        linearPresentView.pixelFormat == .rgba8Unorm,
        "linear present view has the wrong pixel format"
    )
    let linearViewBytes = try harness.readback(linearPresentView)
    try require(
        linearViewBytes == baseBytes,
        "linear present view changed the sRGB base storage bytes"
    )

    let uiStorage = try harness.makeTexture(
        format: .rgba8Unorm,
        width: 8,
        height: 8,
        label: "transparent UI storage",
        usage: [.renderTarget, .shaderRead, .pixelFormatView]
    )
    try harness.clearColor(uiStorage)
    guard let uiViewPointer = metallum_create_texture_view_v2(
        uiStorage, .rgba8Unorm_srgb, 0, 1
    ) else {
        try fail("could not create the sRGB UI view")
    }
    let uiViewObject = Unmanaged<AnyObject>.fromOpaque(uiViewPointer).takeRetainedValue()
    guard let srgbUiView = uiViewObject as? MTLTexture,
          let linearUiView = metalLinearPresentTexture(
              srgbUiView,
              label: "linear UI present view"
          ) else {
        try fail("could not create the linear UI present view")
    }

    func renderComposite(
        scene: MTLTexture,
        ui: MTLTexture,
        destination: MTLTexture,
        label: String
    ) throws {
        let library: MTLLibrary
        do {
            library = try harness.device.makeLibrary(source: presentMslSource(), options: nil)
        } catch {
            try fail("could not compile the production present shader: \(error)")
        }
        guard let vertex = library.makeFunction(name: "metallum_present_vs"),
              let fragment = library.makeFunction(name: "metallum_present_composite_fs") else {
            try fail("production present shader entry points are missing")
        }
        let pipelineDescriptor = MTLRenderPipelineDescriptor()
        pipelineDescriptor.vertexFunction = vertex
        pipelineDescriptor.fragmentFunction = fragment
        pipelineDescriptor.colorAttachments[0].pixelFormat = destination.pixelFormat
        guard let pipeline = try? harness.device.makeRenderPipelineState(
                  descriptor: pipelineDescriptor
              ),
              let commandBuffer = harness.queue.makeCommandBuffer() else {
            try fail("could not create \(label) pipeline resources")
        }
        let samplerDescriptor = MTLSamplerDescriptor()
        samplerDescriptor.minFilter = .linear
        samplerDescriptor.magFilter = .linear
        samplerDescriptor.sAddressMode = .clampToEdge
        samplerDescriptor.tAddressMode = .clampToEdge
        guard let sampler = harness.device.makeSamplerState(descriptor: samplerDescriptor) else {
            try fail("could not create \(label) sampler")
        }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = destination
        pass.colorAttachments[0].loadAction = .dontCare
        pass.colorAttachments[0].storeAction = .store
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
            try fail("could not create \(label) encoder")
        }
        encoder.label = label
        encoder.setViewport(MTLViewport(
            originX: 0,
            originY: 0,
            width: Double(destination.width),
            height: Double(destination.height),
            znear: 0,
            zfar: 1
        ))
        encoder.setRenderPipelineState(pipeline)
        encoder.setFragmentTexture(scene, index: 0)
        encoder.setFragmentTexture(ui, index: 1)
        encoder.setFragmentSamplerState(sampler, index: 0)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
        try harness.commitAndWait(commandBuffer, label: label)
    }

    let decodedDestination = try harness.makeTexture(
        format: .rgba8Unorm,
        width: 8,
        height: 8,
        label: "incorrect decoded present target",
        usage: [.renderTarget, .shaderRead]
    )
    try renderComposite(
        scene: srgbView,
        ui: srgbUiView,
        destination: decodedDestination,
        label: "sRGB sampled into plain UNORM target"
    )
    let decodedBytes = try harness.readback(decodedDestination)
    try require(
        decodedBytes != baseBytes,
        "direct sRGB sampling unexpectedly preserved display-encoded bytes"
    )

    let bytePreservingDestination = try harness.makeTexture(
        format: .rgba8Unorm,
        width: 8,
        height: 8,
        label: "byte-preserving present target",
        usage: [.renderTarget, .shaderRead]
    )
    try renderComposite(
        scene: linearPresentView,
        ui: linearUiView,
        destination: bytePreservingDestination,
        label: "linear view sampled into plain UNORM target"
    )
    let presentedBytes = try harness.readback(bytePreservingDestination)
    try require(
        presentedBytes == baseBytes,
        "linear present views did not preserve bytes through the production composite shader"
    )

    let noViewUsage = try harness.makeTexture(
        format: .rgba8Unorm,
        width: 8,
        height: 8,
        label: "sRGB view missing usage",
        usage: [.renderTarget, .shaderRead]
    )
    try require(
        metallum_create_texture_view_v2(noViewUsage, .rgba8Unorm_srgb, 0, 1) == nil,
        "format reinterpretation succeeded without PixelFormatView usage"
    )
    try require(
        metallum_create_texture_view_v2(base, .bgra8Unorm_srgb, 0, 1) == nil,
        "incompatible RGBA-to-BGRA texture view was accepted"
    )
    return [
        "scenario": "srgb_texture_view_contract",
        "byte_preserving": true,
        "plain_unorm_composite_byte_preserving": true,
        "direct_srgb_sample_changed_bytes": true,
        "missing_usage_rejected": true,
        "incompatible_format_rejected": true
    ]
}

@main
private enum MetalFXOffscreenValidationMain {
    static func main() {
        do {
            guard #available(macOS 26.0, *) else {
                throw ValidationFailure.message("macOS 26 is required for MTLFXFrameInterpolator")
            }
            let root = URL(
                fileURLWithPath: CommandLine.arguments.dropFirst().first
                    ?? "build/metal-validation/offscreen-current",
                isDirectory: true
            ).standardizedFileURL
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            let harness = try OffscreenHarness()
            try require(
                MTLFXTemporalScalerDescriptor.supportsDevice(harness.device),
                "MTLFXTemporalScaler is unsupported on this device"
            )
            try require(
                MTLFXFrameInterpolatorDescriptor.supportsDevice(harness.device),
                "MTLFXFrameInterpolator is unsupported on this device"
            )

            var results: [[String: Any]] = []
            for scenario in scenarios() {
                print("[offscreen] running \(scenario.name)")
                results.append(try runScenario(scenario, harness: harness, root: root))
            }
            print("[offscreen] running transparency_alpha_contract")
            results.append(try runTransparencyAlphaContract(harness: harness, root: root))
            print("[offscreen] running hand_fusion_steady")
            results.append(try runHandFusionScenario(harness: harness, root: root))
            print("[offscreen] running v3_input_contract")
            results.append(try runV3InputContract(harness: harness, root: root))
            print("[offscreen] running srgb_texture_view_contract")
            results.append(try runSrgbTextureViewContract(harness: harness))
            let summary: [String: Any] = [
                "status": "passed",
                "device": harness.device.name,
                "scenario_count": results.count,
                "scenarios": results,
                "uses_layer": false,
                "uses_drawable": false,
                "uses_window": false,
                "uses_screenshot": false
            ]
            let data = try JSONSerialization.data(
                withJSONObject: summary,
                options: [.prettyPrinted, .sortedKeys]
            )
            try data.write(to: root.appendingPathComponent("summary.json"), options: .atomic)
            print("MetalFX offscreen validation passed; artifacts: \(root.path)")
        } catch {
            fputs("MetalFX offscreen validation failed: \(error)\n", stderr)
            exit(1)
        }
    }
}
