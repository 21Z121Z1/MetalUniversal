import Foundation
import Metal
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
import simd

private struct ContractError: Error, CustomStringConvertible {
    var description: String
    init(_ message: String) { description = message }
}

// This matches the actual constant layout consumed by the shipping fused kernel.
private struct FusedUniforms {
    var current: simd_float4x4
    var inverse: simd_float4x4
    var previous: simd_float4x4
    var viewport: SIMD4<Float>
    var flags: SIMD4<UInt32>
    var options: SIMD4<UInt32>
    var params: SIMD4<Float>
}

private struct Case {
    var name: String
    var previous = matrix_identity_float4x4
    var inverse = matrix_identity_float4x4
    var object = SIMD2<Float>(0, 0)
    var valid: Float = 0
    var hand: Float = 0
    var handExact: Float = 0
    var reactive: Float = 0
    var depth: Float = 0.5
    var previousDepth: Float = 0.5
    var history = false
    var reversed = true
    var sky = false
    var expectedMotion: SIMD2<Float>? = nil
    var expectedRejection = false
    var reactiveMinimum: Float = 0
}

private func cases(width: Int, height: Int) -> [Case] {
    var x = matrix_identity_float4x4; x.columns.3.x = 0.125
    var y = matrix_identity_float4x4; y.columns.3.y = 0.25
    let angle: Float = 0.05
    var rotation = matrix_identity_float4x4
    rotation.columns.0 = SIMD4(cos(angle), sin(angle), 0, 0)
    rotation.columns.1 = SIMD4(-sin(angle), cos(angle), 0, 0)
    var jitter = matrix_identity_float4x4
    jitter.columns.3.x = -0.5 / Float(width)
    jitter.columns.3.y = 1.0 / Float(height)
    var behind = matrix_identity_float4x4; behind.columns.3.w = -1
    var singular = matrix_identity_float4x4; singular.columns.3.w = 0
    return [
        Case(name: "static", expectedMotion: .zero),
        Case(name: "jitter_only", inverse: jitter, expectedMotion: .zero),
        Case(name: "camera_x_ndc", previous: x, expectedMotion: SIMD2(0.125, 0)),
        Case(name: "camera_y_top_left", previous: y, expectedMotion: SIMD2(0, -0.25)),
        Case(name: "camera_rotation", previous: rotation),
        Case(name: "previous_behind_camera", previous: behind, expectedMotion: .zero,
             expectedRejection: true, reactiveMinimum: 0.99),
        Case(name: "singular_unprojection", inverse: singular, expectedMotion: .zero,
             expectedRejection: true, reactiveMinimum: 0.99),
        Case(name: "object_overrides_camera", previous: x, object: SIMD2(-0.25, 0.125),
             valid: 1, expectedMotion: SIMD2(-0.25, 0.125)),
        Case(name: "uncovered_object_ignored", object: SIMD2(8, 8), expectedMotion: .zero),
        Case(name: "invalid_object", previous: x, object: SIMD2(.nan, .infinity),
             valid: 1, expectedMotion: SIMD2(0.125, 0), reactiveMinimum: 0.99),
        Case(name: "hand_covers_world_validity", previous: x, object: SIMD2(-0.25, 0.125),
             valid: 1, hand: 0.9, expectedMotion: .zero, reactiveMinimum: 0.49),
        Case(name: "exact_hand", previous: x, object: SIMD2(-0.25, 0.125),
             valid: 1, hand: 0.9, handExact: 1,
             expectedMotion: SIMD2(-0.25, 0.125), reactiveMinimum: 0.49),
        Case(name: "invalid_exact_hand", object: SIMD2(.nan, .infinity),
             valid: 1, hand: 0.9, handExact: 1, expectedMotion: .zero, reactiveMinimum: 0.99),
        Case(name: "reversed_z_occlusion", previousDepth: 0.8, history: true,
             expectedMotion: .zero, expectedRejection: true, reactiveMinimum: 0.84),
        Case(name: "standard_z_occlusion", previousDepth: 0.2, history: true, reversed: false,
             expectedMotion: .zero, expectedRejection: true, reactiveMinimum: 0.84),
        Case(name: "history_reset_ignores_old_depth", previousDepth: 0.8,
             expectedMotion: .zero),
        Case(name: "transparent_reactive_max_merge", reactive: 0.9,
             expectedMotion: .zero, reactiveMinimum: 0.89),
        Case(name: "reversed_z_sky", depth: 0, previousDepth: 0, history: true,
             sky: true, expectedMotion: .zero)
    ]
}

@available(macOS 13.0, *)
private final class Harness {
    let device: MTLDevice
    let queue: MTLCommandQueue
    let pipeline: MTLComputePipelineState

    init() throws {
        guard let device = MTLCreateSystemDefaultDevice(), let queue = device.makeCommandQueue() else {
            throw ContractError("No Metal device/queue")
        }
        self.device = device; self.queue = queue
        // Use exactly the same source and compile options as ensureMotionV2Pipelines.
        let library = try device.makeLibrary(source: motionFusedV2MslSource(), options: nil)
        guard let function = library.makeFunction(name: "metallum_motion_fused_v2") else {
            throw ContractError("Shipping fused kernel missing")
        }
        pipeline = try device.makeComputePipelineState(function: function)
    }

    func texture(_ format: MTLPixelFormat, _ width: Int, _ height: Int,
                 _ value: SIMD2<Float>) throws -> MTLTexture {
        let desc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: format, width: width,
                                                          height: height, mipmapped: false)
        desc.storageMode = .shared; desc.usage = [.shaderRead, .shaderWrite]
        guard let result = device.makeTexture(descriptor: desc) else { throw ContractError("Texture allocation failed") }
        let count = width * height
        switch format {
        case .rg16Float:
            var values = [UInt16](repeating: 0, count: count * 2)
            for i in 0..<count { values[i*2] = Float16(value.x).bitPattern; values[i*2+1] = Float16(value.y).bitPattern }
            values.withUnsafeBytes { result.replace(region: MTLRegionMake2D(0, 0, width, height), mipmapLevel: 0,
                                                   withBytes: $0.baseAddress!, bytesPerRow: width * 4) }
        case .r32Float:
            let values = [Float](repeating: value.x, count: count)
            values.withUnsafeBytes { result.replace(region: MTLRegionMake2D(0, 0, width, height), mipmapLevel: 0,
                                                   withBytes: $0.baseAddress!, bytesPerRow: width * 4) }
        case .r8Unorm:
            let values = [UInt8](repeating: UInt8((max(0, min(1, value.x)) * 255).rounded()), count: count)
            values.withUnsafeBytes { result.replace(region: MTLRegionMake2D(0, 0, width, height), mipmapLevel: 0,
                                                   withBytes: $0.baseAddress!, bytesPerRow: width) }
        default: throw ContractError("Unexpected format")
        }
        return result
    }

    func read(_ texture: MTLTexture, bytesPerPixel: Int) -> Data {
        var data = Data(count: texture.width * texture.height * bytesPerPixel)
        data.withUnsafeMutableBytes { texture.getBytes($0.baseAddress!, bytesPerRow: texture.width * bytesPerPixel,
                                                     from: MTLRegionMake2D(0, 0, texture.width, texture.height), mipmapLevel: 0) }
        return data
    }

    func run(_ c: Case, width: Int, height: Int, root: URL) throws -> [String: Any] {
        let dir = root.appendingPathComponent("\(width)x\(height)-\(c.name)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let depth = try texture(.r32Float, width, height, SIMD2(c.depth, 0))
        let object = try texture(.rg16Float, width, height, c.object)
        let valid = try texture(.r8Unorm, width, height, SIMD2(c.valid, 0))
        let previous = try texture(.r32Float, width, height, SIMD2(c.previousDepth, 0))
        let motion = try texture(.rg16Float, width, height, SIMD2(30, 30))
        let reactive = try texture(.r8Unorm, width, height, SIMD2(c.reactive, 0))
        let camera = try texture(.rg16Float, width, height, SIMD2(30, 30))
        let rejection = try texture(.r8Unorm, width, height, SIMD2(0.5, 0))
        let hand = try texture(.r32Float, width, height, SIMD2(c.hand, 0))
        let handExact = try texture(.r8Unorm, width, height, SIMD2(c.handExact, 0))
        var u = FusedUniforms(current: matrix_identity_float4x4, inverse: c.inverse,
                              previous: c.previous, viewport: SIMD4(Float(width), Float(height), 0, 0),
                              flags: SIMD4(1, c.sky ? 1 : 0, c.history ? 1 : 0, c.reversed ? 1 : 0),
                              options: SIMD4(0, 1, c.hand > 0 ? 1 : 0, c.handExact > 0 ? 1 : 0),
                              params: SIMD4(0.5, 0.85, 0.5, 0))
        guard let cb = queue.makeCommandBuffer(), let encoder = cb.makeComputeCommandEncoder() else {
            throw ContractError("Command buffer allocation failed")
        }
        encoder.label = c.name
        encoder.setComputePipelineState(pipeline)
        encoder.setBytes(&u, length: MemoryLayout<FusedUniforms>.stride, index: 0)
        for (index, texture) in [depth, object, valid, previous, motion, reactive, camera, rejection, hand, handExact].enumerated() {
            encoder.setTexture(texture, index: index)
        }
        let w = max(1, min(pipeline.threadExecutionWidth, 64))
        let h = max(1, min(8, pipeline.maxTotalThreadsPerThreadgroup / w))
        encoder.dispatchThreads(MTLSize(width: width, height: height, depth: 1),
                                threadsPerThreadgroup: MTLSize(width: w, height: h, depth: 1))
        encoder.endEncoding(); cb.commit(); cb.waitUntilCompleted()
        guard cb.status == .completed, cb.error == nil else { throw ContractError("GPU failed: \(String(describing: cb.error))") }
        let motionData = read(motion, bytesPerPixel: 4), reactiveData = read(reactive, bytesPerPixel: 1)
        let rejectionData = read(rejection, bytesPerPixel: 1)
        try motionData.write(to: dir.appendingPathComponent("motion.rg16f"))
        try reactiveData.write(to: dir.appendingPathComponent("reactive.r8"))
        try rejectionData.write(to: dir.appendingPathComponent("disocclusion.r8"))
        try read(depth, bytesPerPixel: 4).write(to: dir.appendingPathComponent("depth.r32f"))
        var violations = 0, checked = 0, maximumError: Float = 0
        var visualization = [UInt8](repeating: 255, count: width * height * 4)
        for y in 0..<height { for x in 0..<width {
            let p = y*width+x, offset = p*4
            let vx = Float(Float16(bitPattern: UInt16(motionData[offset]) | UInt16(motionData[offset+1]) << 8))
            let vy = Float(Float16(bitPattern: UInt16(motionData[offset+2]) | UInt16(motionData[offset+3]) << 8))
            if !vx.isFinite || !vy.isFinite { violations += 1 }
            visualization[p*4] = UInt8(max(0, min(255, vx.isFinite ? 128 + vx*128 : 255)))
            visualization[p*4+1] = UInt8(max(0, min(255, vy.isFinite ? 128 + vy*128 : 0)))
            visualization[p*4+2] = rejectionData[p]
            // Use an interior region to keep border visibility changes separate from the oracle.
            guard x >= width/3, x < width*2/3, y >= height/3, y < height*2/3 else { continue }
            checked += 1
            let expected: SIMD2<Float>
            if let constant = c.expectedMotion { expected = constant }
            else {
                // Independent affine rotation of a top-left pixel center; no production math helper.
                let px = 2 * (Float(x)+0.5)/Float(width)-1
                let py = 1-2 * (Float(y)+0.5)/Float(height)
                let a: Float = 0.05
                expected = SIMD2(cos(a)*px - sin(a)*py - px, py - sin(a)*px - cos(a)*py)
            }
            let error = max(abs(vx-expected.x), abs(vy-expected.y))
            maximumError = max(maximumError, error)
            if !error.isFinite || error > 0.001 { violations += 1 }
            if (rejectionData[p] >= 128) != c.expectedRejection { violations += 1 }
            if Float(reactiveData[p])/255 < c.reactiveMinimum { violations += 1 }
        }}
        try png(visualization, width: width, height: height, to: dir.appendingPathComponent("motion.png"))
        let result: [String: Any] = ["name":c.name,"width":width,"height":height,
                                   "passed":violations == 0,"violations":violations,"checkedPixels":checked,
                                   "maxNdcError":maximumError.isFinite ? maximumError : -1,
                                   "motionUnit":"unjittered current-to-previous NDC, top-left Y",
                                   "motionVectorScale":[Float(width)/2,Float(height)/2],
                                   "gpuMilliseconds":max(0,(cb.gpuEndTime-cb.gpuStartTime)*1000)]
        try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted,.sortedKeys])
            .write(to: dir.appendingPathComponent("result.json"))
        print("MOTION_CONTRACT \(violations == 0 ? "PASS" : "FAIL") \(width)x\(height) \(c.name) violations=\(violations) error=\(maximumError)")
        return result
    }
}

private func png(_ rgba: [UInt8], width: Int, height: Int, to url: URL) throws {
    let data = Data(rgba)
    guard let provider = CGDataProvider(data: data as CFData),
          let image = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
                              bytesPerRow: width*4, space: CGColorSpaceCreateDeviceRGB(),
                              bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                              provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent),
          let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else {
        throw ContractError("PNG creation failed")
    }
    CGImageDestinationAddImage(destination, image, nil)
    if !CGImageDestinationFinalize(destination) { throw ContractError("PNG export failed") }
}

@main
private enum MetalMotionContractValidation {
    static func main() {
        do {
            guard #available(macOS 13.0, *) else { throw ContractError("macOS 13 required") }
            let root = URL(fileURLWithPath: CommandLine.arguments.dropFirst().first ?? "build/metal-validation/motion-current")
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            let h = try Harness()
            var results = [[String: Any]]()
            for (width,height) in [(32,24),(37,19)] {
                for c in cases(width:width,height:height) { results.append(try h.run(c,width:width,height:height,root:root)) }
            }
            let failed = results.filter { ($0["passed"] as? Bool) != true }.count
            let result: [String: Any] = ["sourceCommit":ProcessInfo.processInfo.environment["METALLUM_SOURCE_SHA"] ?? "unknown",
                                       "device":h.device.name,"passed":failed == 0,"cases":results,"failedCases":failed,
                                       "kernel":"shipping metallum_motion_fused_v2","metalfxQualityValidated":false,
                                       "presentationValidated":false]
            try JSONSerialization.data(withJSONObject: result, options:[.prettyPrinted,.sortedKeys])
                .write(to:root.appendingPathComponent("summary.json"),options:.atomic)
            if failed != 0 { throw ContractError("\(failed) motion cases failed") }
            print("METAL_MOTION_CONTRACT_PASS cases=\(results.count)")
        } catch { fputs("METAL_MOTION_CONTRACT_FAIL: \(error)\n",stderr); exit(1) }
    }
}
