import Foundation
import Metal

private enum SmokeFailure: Error, CustomStringConvertible {
    case message(String)

    var description: String {
        switch self {
        case .message(let message):
            return message
        }
    }
}

private let shaderSource = """
#include <metal_stdlib>
using namespace metal;

struct VertexOut {
    float4 position [[position]];
};

struct FullMRTOut {
    float4 color [[color(0)]];
    half2 motion [[color(1)]];
    float validity [[color(2)]];
};

struct NullSlotOut {
    float4 color [[color(0)]];
    float validity [[color(2)]];
};

vertex VertexOut mrt_smoke_vs(uint vertexID [[vertex_id]]) {
    const float2 positions[3] = {
        float2(-1.0, -1.0),
        float2( 3.0, -1.0),
        float2(-1.0,  3.0)
    };
    VertexOut output;
    output.position = float4(positions[vertexID], 0.0, 1.0);
    return output;
}

fragment FullMRTOut mrt_smoke_fs() {
    FullMRTOut output;
    output.color = float4(0.25, 0.50, 0.75, 1.0);
    output.motion = half2(-0.25, 0.50);
    output.validity = 0.75;
    return output;
}

fragment NullSlotOut mrt_null_slot_fs() {
    NullSlotOut output;
    output.color = float4(0.75, 0.25, 0.50, 1.0);
    output.validity = 0.25;
    return output;
}
"""

private func fail(_ message: String) throws -> Never {
    throw SmokeFailure.message(message)
}

private func check(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    if !condition() {
        try fail(message)
    }
}

private func checkNear(_ actual: Float, _ expected: Float, _ tolerance: Float, _ label: String) throws {
    try check(actual.isFinite && abs(actual - expected) <= tolerance,
              "(label): expected (expected), got (actual)")
}

private func makeTexture(
    device: MTLDevice,
    pixelFormat: MTLPixelFormat,
    width: Int,
    height: Int,
    label: String
) throws -> MTLTexture {
    let descriptor = MTLTextureDescriptor.texture2DDescriptor(
        pixelFormat: pixelFormat,
        width: width,
        height: height,
        mipmapped: false
    )
    descriptor.storageMode = .shared
    descriptor.usage = [.renderTarget, .shaderRead]
    guard let texture = device.makeTexture(descriptor: descriptor) else {
        try fail("could not allocate (label)")
    }
    texture.label = label
    return texture
}

private func makePipeline(
    device: MTLDevice,
    library: MTLLibrary,
    fragmentName: String,
    colorFormats: [MTLPixelFormat]
) throws -> MTLRenderPipelineState {
    guard let vertex = library.makeFunction(name: "mrt_smoke_vs"),
          let fragment = library.makeFunction(name: fragmentName) else {
        try fail("missing MSL entry point for (fragmentName)")
    }
    let descriptor = MTLRenderPipelineDescriptor()
    descriptor.vertexFunction = vertex
    descriptor.fragmentFunction = fragment
    for index in 0..<colorFormats.count {
        descriptor.colorAttachments[index].pixelFormat = colorFormats[index]
        descriptor.colorAttachments[index].isBlendingEnabled = false
        descriptor.colorAttachments[index].writeMask = .all
    }
    do {
        return try device.makeRenderPipelineState(descriptor: descriptor)
    } catch {
        try fail("could not create (fragmentName) pipeline: (error)")
    }
}

private func render(
    queue: MTLCommandQueue,
    pipeline: MTLRenderPipelineState,
    attachments: [MTLTexture?],
    clearColors: [MTLClearColor?],
    label: String
) throws {
    guard let commandBuffer = queue.makeCommandBuffer() else {
        try fail("could not allocate (label) command buffer")
    }
    commandBuffer.label = label
    let descriptor = MTLRenderPassDescriptor()
    for index in 0..<attachments.count {
        guard index < clearColors.count else {
            try fail("clear color array is shorter than attachment array")
        }
        guard let texture = attachments[index] else {
            continue
        }
        guard let attachment = descriptor.colorAttachments[index] else {
            try fail("Metal did not provide a color attachment descriptor for slot (index)")
        }
        attachment.texture = texture
        if let clearColor = clearColors[index] {
            attachment.loadAction = .clear
            attachment.clearColor = clearColor
        } else {
            attachment.loadAction = .load
        }
        attachment.storeAction = .store
    }
    guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: descriptor) else {
        try fail("could not create (label) render encoder")
    }
    encoder.label = label
    let width = attachments.compactMap { $0?.width }.first ?? 0
    let height = attachments.compactMap { $0?.height }.first ?? 0
    encoder.setViewport(MTLViewport(
        originX: 0.0,
        originY: 0.0,
        width: Double(width),
        height: Double(height),
        znear: 0.0,
        zfar: 1.0
    ))
    encoder.setRenderPipelineState(pipeline)
    encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
    encoder.endEncoding()
    commandBuffer.commit()
    commandBuffer.waitUntilCompleted()
    try check(commandBuffer.status == .completed,
              "(label) failed: (String(describing: commandBuffer.error))")
}

private func readRGBA8(_ texture: MTLTexture) -> [UInt8] {
    var values = [UInt8](repeating: 0, count: 4)
    texture.getBytes(&values, bytesPerRow: 4, from: MTLRegionMake2D(0, 0, 1, 1), mipmapLevel: 0)
    return values
}

private func readR8(_ texture: MTLTexture) -> Float {
    var value: UInt8 = 0
    texture.getBytes(&value, bytesPerRow: 1, from: MTLRegionMake2D(0, 0, 1, 1), mipmapLevel: 0)
    return Float(value) / 255.0
}

private func readRG16Float(_ texture: MTLTexture) -> (Float, Float) {
    var values = [UInt16](repeating: 0, count: 2)
    texture.getBytes(&values, bytesPerRow: 4, from: MTLRegionMake2D(0, 0, 1, 1), mipmapLevel: 0)
    return (
        Float(Float16(bitPattern: values[0])),
        Float(Float16(bitPattern: values[1]))
    )
}

private func runSmokeTest() throws {
    guard let device = MTLCreateSystemDefaultDevice() else {
        try fail("MTLCreateSystemDefaultDevice returned nil")
    }
    guard let queue = device.makeCommandQueue() else {
        try fail("could not create Metal command queue")
    }
    let library: MTLLibrary
    do {
        library = try device.makeLibrary(source: shaderSource, options: nil)
    } catch {
        try fail("could not compile MRT smoke MSL: (error)")
    }

    let width = 8
    let height = 8
    let color0 = try makeTexture(device: device, pixelFormat: .rgba8Unorm, width: width, height: height, label: "MRT smoke color 0")
    let motion = try makeTexture(device: device, pixelFormat: .rg16Float, width: width, height: height, label: "MRT smoke motion 1")
    let validity = try makeTexture(device: device, pixelFormat: .r8Unorm, width: width, height: height, label: "MRT smoke validity 2")

    let fullPipeline = try makePipeline(
        device: device,
        library: library,
        fragmentName: "mrt_smoke_fs",
        colorFormats: [.rgba8Unorm, .rg16Float, .r8Unorm]
    )
    try render(
        queue: queue,
        pipeline: fullPipeline,
        attachments: [color0, motion, validity],
        clearColors: [
            MTLClearColor(red: 0.1, green: 0.2, blue: 0.3, alpha: 1.0),
            MTLClearColor(red: 0.1, green: -0.2, blue: 0.0, alpha: 1.0),
            MTLClearColor(red: 0.1, green: 0.0, blue: 0.0, alpha: 1.0)
        ],
        label: "MRT smoke full-slot clear and draw"
    )

    let rgba = readRGBA8(color0)
    try check(rgba[0] == 64 && rgba[1] == 128 && rgba[2] == 191 && rgba[3] == 255,
              "RGBA8 readback mismatch: (rgba)")
    let (motionX, motionY) = readRG16Float(motion)
    try checkNear(motionX, -0.25, 0.01, "RG16_FLOAT X")
    try checkNear(motionY, 0.50, 0.01, "RG16_FLOAT Y")
    try checkNear(readR8(validity), 0.75, 0.01, "R8 validity")

    let nullColor = try makeTexture(device: device, pixelFormat: .rgba8Unorm, width: width, height: height, label: "MRT smoke null-slot color 0")
    let nullValidity = try makeTexture(device: device, pixelFormat: .r8Unorm, width: width, height: height, label: "MRT smoke null-slot validity 2")
    let nullPipeline = try makePipeline(
        device: device,
        library: library,
        fragmentName: "mrt_null_slot_fs",
        colorFormats: [.rgba8Unorm, .invalid, .r8Unorm]
    )
    try render(
        queue: queue,
        pipeline: nullPipeline,
        attachments: [nullColor, nil, nullValidity],
        clearColors: [
            MTLClearColor(red: 0.0, green: 0.0, blue: 0.0, alpha: 1.0),
            nil,
            MTLClearColor(red: 0.0, green: 0.0, blue: 0.0, alpha: 1.0)
        ],
        label: "MRT smoke preserved null slot"
    )
    let nullRGBA = readRGBA8(nullColor)
    try check(nullRGBA[0] == 191 && nullRGBA[1] == 64 && nullRGBA[2] == 128 && nullRGBA[3] == 255,
              "null-slot RGBA8 readback mismatch: (nullRGBA)")
    try checkNear(readR8(nullValidity), 0.25, 0.01, "null-slot R8 validity")

    print("MRT smoke passed: full slots [RGBA8, RG16_FLOAT, R8_UNORM], preserved null slot [RGBA8, unused, R8_UNORM]")
}

do {
    try runSmokeTest()
} catch {
    fputs("MRT smoke failed: (error)\n", stderr)
    exit(1)
}
