// Metal 4 migration spec, M1 + M2b acceptance at the L2 level.
//
// Metal4PipelineSmokeTest answers the API question with its own code. This test
// answers the *integration* question by linking MetallumNative.swift and calling
// the shipping entry points — metallum_metal4_supported,
// metallum_create_shader_function, metallum_set_metal4_compiler_enabled,
// metallum_MTLDevice_makeRenderPipelineState — so the side table, the descriptor
// translation and the new branch are all exercised as the game will use them.
//
// Three properties are checked:
//   1. the capability export agrees with device.supportsFamily(.metal4);
//   2. with the switch on, a pipeline built through the shipping export draws
//      pixel-identically to the same descriptor with the switch off (the Metal 4
//      compiler must not change rendering);
//   3. with the switch on but the function's library missing from the side
//      table, creation still succeeds — i.e. the fall-through to Metal 3 works
//      rather than returning nil.
//
// Which path actually ran is reported by the one-shot NSLog lines in
// MetallumNative.swift ("Metal 4 pipeline path engaged" / "... unavailable,
// using Metal 3"); they appear in this task's output.

import Foundation
import Metal

private enum PathFailure: Error, CustomStringConvertible {
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

vertex VertexOut mtl4_path_vs(uint vertexID [[vertex_id]]) {
    const float2 positions[3] = {
        float2(-1.0, -1.0),
        float2( 3.0, -1.0),
        float2(-1.0,  3.0)
    };
    VertexOut output;
    output.position = float4(positions[vertexID], 0.0, 1.0);
    return output;
}

fragment float4 mtl4_path_fs() {
    return float4(0.25, 0.50, 0.75, 1.0);
}
"""

// Distinct source and entry-point names for the fall-through case. Reusing
// `shaderSource` here does not work: Metal hands back the same MTLFunction
// object for an identical library source, so the weak-keyed side table still
// hits and the Metal 4 path is taken — the test would pass while testing
// nothing. Different names guarantee genuinely unregistered functions.
private let unregisteredShaderSource = """
#include <metal_stdlib>
using namespace metal;

struct VertexOut {
    float4 position [[position]];
};

vertex VertexOut mtl4_unregistered_vs(uint vertexID [[vertex_id]]) {
    const float2 positions[3] = {
        float2(-1.0, -1.0),
        float2( 3.0, -1.0),
        float2(-1.0,  3.0)
    };
    VertexOut output;
    output.position = float4(positions[vertexID], 0.0, 1.0);
    return output;
}

fragment float4 mtl4_unregistered_fs() {
    return float4(0.25, 0.50, 0.75, 1.0);
}
"""

private func fail(_ message: String) throws -> Never {
    throw PathFailure.message(message)
}

private func check(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    if !condition() {
        try fail(message)
    }
}

/// Calls the shipping metallum_create_shader_function export, which is what
/// registers the function -> library association the Metal 4 path needs.
private func createShippingFunction(device: MTLDevice, entryPoint: String) throws -> MTLFunction {
    let pointer: UnsafeMutableRawPointer? = shaderSource.withCString { sourcePtr in
        entryPoint.withCString { entryPtr in
            metallum_create_shader_function(device, sourcePtr, entryPtr)
        }
    }
    guard let pointer else {
        try fail("metallum_create_shader_function returned nil for \(entryPoint)")
    }
    guard let function = Unmanaged<AnyObject>.fromOpaque(pointer).takeRetainedValue() as? MTLFunction else {
        try fail("metallum_create_shader_function did not return an MTLFunction for \(entryPoint)")
    }
    return function
}

private func makeDescriptor(
    vertexFunction: MTLFunction,
    fragmentFunction: MTLFunction,
    label: String
) -> MTLRenderPipelineDescriptor {
    let descriptor = MTLRenderPipelineDescriptor()
    descriptor.label = label
    descriptor.vertexFunction = vertexFunction
    descriptor.fragmentFunction = fragmentFunction
    descriptor.colorAttachments[0].pixelFormat = .rgba8Unorm
    descriptor.colorAttachments[0].isBlendingEnabled = false
    descriptor.colorAttachments[0].writeMask = .all
    return descriptor
}

/// Calls the shipping pipeline export and hands back the state it produced.
private func createShippingPipeline(
    device: MTLDevice,
    descriptor: MTLRenderPipelineDescriptor
) throws -> MTLRenderPipelineState {
    guard let pointer = metallum_MTLDevice_makeRenderPipelineState(device, descriptor) else {
        try fail("metallum_MTLDevice_makeRenderPipelineState returned nil for \(descriptor.label ?? "<unlabelled>")")
    }
    guard let state = Unmanaged<AnyObject>.fromOpaque(pointer).takeRetainedValue() as? MTLRenderPipelineState else {
        try fail("metallum_MTLDevice_makeRenderPipelineState did not return an MTLRenderPipelineState")
    }
    return state
}

private func makeTarget(device: MTLDevice, label: String) throws -> MTLTexture {
    let descriptor = MTLTextureDescriptor.texture2DDescriptor(
        pixelFormat: .rgba8Unorm,
        width: 8,
        height: 8,
        mipmapped: false
    )
    descriptor.storageMode = .shared
    descriptor.usage = [.renderTarget, .shaderRead]
    guard let texture = device.makeTexture(descriptor: descriptor) else {
        try fail("could not allocate \(label)")
    }
    texture.label = label
    return texture
}

private func drawAndRead(
    queue: MTLCommandQueue,
    pipeline: MTLRenderPipelineState,
    target: MTLTexture,
    label: String
) throws -> [UInt8] {
    guard let commandBuffer = queue.makeCommandBuffer() else {
        try fail("could not allocate \(label) command buffer")
    }
    commandBuffer.label = label
    let descriptor = MTLRenderPassDescriptor()
    guard let attachment = descriptor.colorAttachments[0] else {
        try fail("Metal did not provide a color attachment descriptor for slot 0")
    }
    attachment.texture = target
    attachment.loadAction = .clear
    attachment.clearColor = MTLClearColor(red: 0.0, green: 0.0, blue: 0.0, alpha: 1.0)
    attachment.storeAction = .store
    guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: descriptor) else {
        try fail("could not create \(label) render encoder")
    }
    encoder.label = label
    encoder.setViewport(MTLViewport(
        originX: 0.0,
        originY: 0.0,
        width: Double(target.width),
        height: Double(target.height),
        znear: 0.0,
        zfar: 1.0
    ))
    encoder.setRenderPipelineState(pipeline)
    encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
    encoder.endEncoding()
    commandBuffer.commit()
    commandBuffer.waitUntilCompleted()
    try check(commandBuffer.status == .completed,
              "\(label) failed: \(String(describing: commandBuffer.error))")
    var values = [UInt8](repeating: 0, count: 4)
    target.getBytes(&values, bytesPerRow: 4, from: MTLRegionMake2D(0, 0, 1, 1), mipmapLevel: 0)
    return values
}

/// Exercises metallum_residency_set_enable plus the creation, submit and release
/// hooks, all through the shipping exports (migration spec M3).
@available(macOS 15.0, iOS 18.0, *)
private func residencyTest(device: MTLDevice, queue: MTLCommandQueue) throws {
    try check(metallum_residency_set_enable(device, queue) != 0,
              "metallum_residency_set_enable failed")
    try check(metallum_residency_set_enable(device, queue) != 0,
              "metallum_residency_set_enable is not idempotent")

    // Counts, not object identity: the stats export deliberately does not hand
    // out the set, and counts are enough to pin down every property here.
    func residencyCount(_ label: String) throws -> UInt32 {
        var allocations: UInt32 = 0
        var bytes: UInt64 = 0
        try check(metallum_residency_set_stats(&allocations, &bytes) != 0,
                  "metallum_residency_set_stats reported no active set at \(label)")
        return allocations
    }
    /// Residency changes are published at most once per submit, so nothing is
    /// observable until one happens.
    func flushResidency(_ label: String) throws {
        guard let commandBuffer = queue.makeCommandBuffer() else {
            try fail("could not allocate the \(label) command buffer")
        }
        metallum_MTLCommandBuffer_commit(commandBuffer)
        commandBuffer.waitUntilCompleted()
    }

    let baseline = try residencyCount("baseline")

    guard let bufferPointer = metallum_create_buffer(device, 4096, []) else {
        try fail("metallum_create_buffer returned nil")
    }
    guard let texturePointer = "residency-test".withCString({ label in
        metallum_create_texture_2d(device, .rgba8Unorm, 16, 16, 1, 1, 0, [.shaderRead], .private, label)
    }) else {
        try fail("metallum_create_texture_2d returned nil")
    }
    // Memoryless needs renderTarget usage to be a legal texture at all; it must
    // still be excluded from the set.
    guard let memorylessPointer = "residency-test-memoryless".withCString({ label in
        metallum_create_texture_2d(device, .depth32Float, 16, 16, 1, 1, 0, [.renderTarget], .memoryless, label)
    }) else {
        try fail("metallum_create_texture_2d returned nil for the memoryless texture")
    }

    try flushResidency("residency additions")
    let afterCreate = try residencyCount("after create")
    // Three resources created, but the memoryless one must not be tracked.
    try check(afterCreate == baseline + 2,
              "expected \(baseline + 2) tracked allocations after creating a buffer, a texture and a "
              + "memoryless texture, got \(afterCreate)")

    metallum_release_object(bufferPointer)
    try flushResidency("residency removal")
    let afterRelease = try residencyCount("after release")
    try check(afterRelease == baseline + 1,
              "releasing the buffer should leave \(baseline + 1) tracked allocations, got \(afterRelease)")

    metallum_release_object(texturePointer)
    metallum_release_object(memorylessPointer)
    try flushResidency("residency drain")
    let afterDrain = try residencyCount("after drain")
    try check(afterDrain == baseline,
              "releasing everything should return to \(baseline) tracked allocations, got \(afterDrain)")
}

private func runResidencyTest(device: MTLDevice, queue: MTLCommandQueue) throws {
    guard #available(macOS 15.0, iOS 18.0, *) else {
        print("residency set test skipped: needs macOS 15 / iOS 18")
        return
    }
    try residencyTest(device: device, queue: queue)
}

private func runPathTest() throws {
    guard let device = MTLCreateSystemDefaultDevice() else {
        try fail("MTLCreateSystemDefaultDevice returned nil")
    }
    guard let queue = device.makeCommandQueue() else {
        try fail("could not create Metal command queue")
    }

    // (1) capability export agrees with the device
    let reported = metallum_metal4_supported(device) != 0
    var expected = false
    if #available(macOS 26.0, iOS 26.0, *) {
        expected = device.supportsFamily(.metal4)
    }
    try check(reported == expected,
              "metallum_metal4_supported returned \(reported) but supportsFamily(.metal4) is \(expected)")
    print("Metal 4 path test: metallum_metal4_supported=\(reported) on \(device.name)")

    guard reported else {
        print("Metal 4 path test skipped: this host has no Metal 4 support, nothing to compare against")
        return
    }

    let vertexFunction = try createShippingFunction(device: device, entryPoint: "mtl4_path_vs")
    let fragmentFunction = try createShippingFunction(device: device, entryPoint: "mtl4_path_fs")

    // (2a) baseline: switch off, Metal 3 path
    metallum_set_metal4_compiler_enabled(0)
    let metal3Pipeline = try createShippingPipeline(
        device: device,
        descriptor: makeDescriptor(
            vertexFunction: vertexFunction,
            fragmentFunction: fragmentFunction,
            label: "metal4-path-metal3"
        )
    )
    let metal3Pixel = try drawAndRead(
        queue: queue,
        pipeline: metal3Pipeline,
        target: try makeTarget(device: device, label: "metal4 path metal3"),
        label: "Metal 3 pipeline draw"
    )
    try check(metal3Pixel == [64, 128, 191, 255],
              "Metal 3 baseline readback mismatch: \(metal3Pixel)")

    // (2b) switch on: same descriptor, must render identically
    metallum_set_metal4_compiler_enabled(1)
    let metal4Pipeline = try createShippingPipeline(
        device: device,
        descriptor: makeDescriptor(
            vertexFunction: vertexFunction,
            fragmentFunction: fragmentFunction,
            label: "metal4-path-metal4"
        )
    )
    let metal4Pixel = try drawAndRead(
        queue: queue,
        pipeline: metal4Pipeline,
        target: try makeTarget(device: device, label: "metal4 path metal4"),
        label: "Metal 4 pipeline draw"
    )
    try check(metal4Pixel == metal3Pixel,
              "Metal 4 pipeline rendered \(metal4Pixel), Metal 3 rendered \(metal3Pixel)")

    // (3) switch on, but the library was never registered: the Metal 4
    // translation must decline and the Metal 3 path must still deliver a
    // pipeline. Functions made directly off an MTLLibrary bypass
    // metallum_create_shader_function, which is exactly that case.
    let library = try device.makeLibrary(source: unregisteredShaderSource, options: nil)
    guard let unregisteredVertex = library.makeFunction(name: "mtl4_unregistered_vs"),
          let unregisteredFragment = library.makeFunction(name: "mtl4_unregistered_fs") else {
        try fail("could not resolve the unregistered MSL entry points")
    }
    let fallbackPipeline = try createShippingPipeline(
        device: device,
        descriptor: makeDescriptor(
            vertexFunction: unregisteredVertex,
            fragmentFunction: unregisteredFragment,
            label: "metal4-path-fallback"
        )
    )
    let fallbackPixel = try drawAndRead(
        queue: queue,
        pipeline: fallbackPipeline,
        target: try makeTarget(device: device, label: "metal4 path fallback"),
        label: "unregistered-library fallback draw"
    )
    try check(fallbackPixel == metal3Pixel,
              "fallback pipeline rendered \(fallbackPixel), Metal 3 rendered \(metal3Pixel)")

    // (4) M2c: pipeline data set round trip. MTLBinaryArchive cannot re-serialize
    // an archive it loaded from disk, which is why the Metal 3 path is
    // "build on first launch, read-only afterwards". MTL4PipelineDataSetSerializer
    // has no such limit, so a flush must succeed on a warm launch too — that is
    // what this checks, along with the archive landing beside the Metal 3 file
    // rather than on top of it.
    let archiveDirectory = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        .appendingPathComponent("metallum-metal4-archive-test", isDirectory: true)
    try? FileManager.default.removeItem(at: archiveDirectory)
    try FileManager.default.createDirectory(at: archiveDirectory, withIntermediateDirectories: true)
    let binaryArchivePath = archiveDirectory.appendingPathComponent("pso.binaryarchive").path
    let metal4ArchivePath = archiveDirectory.appendingPathComponent("pso.mtl4archive").path

    for launch in ["cold", "warm"] {
        try check(binaryArchivePath.withCString { metallum_pso_archive_open(device, $0) } != 0,
                  "metallum_pso_archive_open failed on the \(launch) launch")
        let pipeline = try createShippingPipeline(
            device: device,
            descriptor: makeDescriptor(
                vertexFunction: vertexFunction,
                fragmentFunction: fragmentFunction,
                label: "metal4-path-archive-\(launch)"
            )
        )
        let pixel = try drawAndRead(
            queue: queue,
            pipeline: pipeline,
            target: try makeTarget(device: device, label: "metal4 path archive \(launch)"),
            label: "\(launch) archive launch draw"
        )
        try check(pixel == metal3Pixel,
                  "\(launch) archive launch rendered \(pixel), Metal 3 rendered \(metal3Pixel)")
        try check(binaryArchivePath.withCString { metallum_pso_archive_flush($0) } != 0,
                  "metallum_pso_archive_flush failed on the \(launch) launch")
        try check(FileManager.default.fileExists(atPath: metal4ArchivePath),
                  "no Metal 4 pipeline archive at \(metal4ArchivePath) after the \(launch) launch")
        try check(!FileManager.default.fileExists(atPath: binaryArchivePath),
                  "the Metal 4 path wrote to the Metal 3 binary archive path")
    }
    try? FileManager.default.removeItem(at: archiveDirectory)

    metallum_set_metal4_compiler_enabled(0)

    // (5) M3: residency set on a plain Metal 3 queue. Checks that enabling is
    // idempotent, that resources created afterwards land in the set, that a
    // memoryless texture is kept out of it (it has no backing allocation, so
    // adding one is invalid), that a submit publishes pending changes, and that
    // releasing a resource removes it again.
    try runResidencyTest(device: device, queue: queue)

    print("Metal 4 path test passed: MTL4Compiler pipelines render identically to Metal 3 through the shipping export, an unregistered library falls back cleanly, the pipeline data set archive flushes on both a cold and a warm launch, and the residency set tracks native allocations")
}

// Multi-file compile: no top-level code, so the entry point is explicit (same
// shape as MetalFrameGenerationPresentationValidation).
@main
private struct Metal4PipelinePathMain {
    static func main() {
        do {
            try runPathTest()
        } catch {
            fputs("Metal 4 path test failed: \(error)\n", stderr)
            exit(1)
        }
    }
}
