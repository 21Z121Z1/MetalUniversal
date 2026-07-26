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
import MetalFX
import QuartzCore

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

/// Same shape as the presenter's full-screen copy: one texture and one sampler at
/// index 0, which under Metal 4 arrive through the argument table.
private let copyShaderSource = """
#include <metal_stdlib>
using namespace metal;

struct CopyOut {
    float4 position [[position]];
};

vertex CopyOut path_copy_vs(uint vertexID [[vertex_id]]) {
    const float2 positions[3] = {
        float2(-1.0, -1.0),
        float2( 3.0, -1.0),
        float2(-1.0,  3.0)
    };
    CopyOut output;
    output.position = float4(positions[vertexID], 0.0, 1.0);
    return output;
}

fragment float4 path_copy_fs(CopyOut in [[stage_in]],
                             texture2d<float> source [[texture(0)]],
                             sampler sourceSampler [[sampler(0)]]) {
    return source.sample(sourceSampler, in.position.xy / float2(8.0, 8.0));
}
"""

/// Reads a uniform by GPU address out of an argument table, which is how every
/// former set*Bytes site will supply its uniform under Metal 4.
private let bumpShaderSource = """
#include <metal_stdlib>
using namespace metal;

struct BumpOut {
    float4 position [[position]];
};

struct BumpUniforms {
    float4 color;
};

vertex BumpOut bump_vs(uint vertexID [[vertex_id]]) {
    const float2 positions[3] = {
        float2(-1.0, -1.0),
        float2( 3.0, -1.0),
        float2(-1.0,  3.0)
    };
    BumpOut output;
    output.position = float4(positions[vertexID], 0.0, 1.0);
    return output;
}

fragment float4 bump_fs(constant BumpUniforms& u [[buffer(0)]]) {
    return u.color;
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

/// M4: exercises the shipping Metal4PresentPath object graph headlessly.
///
/// The full acceptance for M4 is the visible-window pacing run
/// (metal4PresentValidation), which needs a WindowServer-composited window. What
/// can be checked without one is everything up to the drawable: that the MTL4
/// queue, reusable command buffer, allocator ring, argument table and residency
/// set all build, that the MTL4 frame interpolator factory actually works on this
/// device (if it returned nil the present path would silently stay on Metal 3
/// forever), and that a copy encodes and renders correctly through the real
/// encodeCopy with the presenter's own Metal 3 copy pipeline.
@available(macOS 26.0, *)
private func presentPathTest(device: MTLDevice) throws {
    let layer = CAMetalLayer()
    layer.device = device
    layer.pixelFormat = .bgra8Unorm
    layer.drawableSize = CGSize(width: 8, height: 8)

    guard let path = Metal4PresentPath(device: device, layer: layer) else {
        try fail("Metal4PresentPath could not be constructed")
    }

    // The MTL4 interpolator factory is the one piece MetalFX could refuse
    // outright, which would make the present path fall back forever. A local
    // compiler is used rather than the shipping shared one, which is file-private.
    let compilerDescriptor = MTL4CompilerDescriptor()
    compilerDescriptor.label = "present-path-test-compiler"
    let compiler = try device.makeCompiler(descriptor: compilerDescriptor)
    let interpolatorDescriptor = MTLFXFrameInterpolatorDescriptor()
    interpolatorDescriptor.colorTextureFormat = .rgba16Float
    interpolatorDescriptor.outputTextureFormat = .rgba16Float
    interpolatorDescriptor.depthTextureFormat = .depth32Float
    interpolatorDescriptor.motionTextureFormat = .rg16Float
    interpolatorDescriptor.uiTextureFormat = .rgba16Float
    interpolatorDescriptor.inputWidth = 64
    interpolatorDescriptor.inputHeight = 64
    interpolatorDescriptor.outputWidth = 64
    interpolatorDescriptor.outputHeight = 64
    let interpolator: (any MTL4FXFrameInterpolator)? =
        interpolatorDescriptor.makeFrameInterpolator(device: device, compiler: compiler)
    try check(interpolator != nil,
              "MTLFXFrameInterpolatorDescriptor.makeFrameInterpolator(device:compiler:) returned nil; "
              + "the Metal 4 present path would fall back to Metal 3 permanently")

    // Same shape as the presenter's copy pipeline (full-screen triangle, one
    // texture and one sampler at index 0); buildPresentPipeline itself is
    // file-private to MetallumNative.swift so it cannot be called from here.
    let copyLibrary = try device.makeLibrary(source: copyShaderSource, options: nil)
    guard let copyVertex = copyLibrary.makeFunction(name: "path_copy_vs"),
          let copyFragment = copyLibrary.makeFunction(name: "path_copy_fs") else {
        try fail("missing copy MSL entry points")
    }
    let copyPipelineDescriptor = MTLRenderPipelineDescriptor()
    copyPipelineDescriptor.vertexFunction = copyVertex
    copyPipelineDescriptor.fragmentFunction = copyFragment
    copyPipelineDescriptor.colorAttachments[0].pixelFormat = .rgba8Unorm
    let copyPipeline = try device.makeRenderPipelineState(descriptor: copyPipelineDescriptor)
    let samplerDescriptor = MTLSamplerDescriptor()
    samplerDescriptor.minFilter = .nearest
    samplerDescriptor.magFilter = .nearest
    guard let copySampler = device.makeSamplerState(descriptor: samplerDescriptor) else {
        try fail("could not create the copy sampler")
    }

    let sourceDescriptor = MTLTextureDescriptor.texture2DDescriptor(
        pixelFormat: .rgba8Unorm, width: 8, height: 8, mipmapped: false
    )
    sourceDescriptor.storageMode = .shared
    sourceDescriptor.usage = [.shaderRead]
    guard let source = device.makeTexture(descriptor: sourceDescriptor) else {
        try fail("could not allocate the present-path source texture")
    }
    var pixels = [UInt8](repeating: 0, count: 8 * 8 * 4)
    for index in 0..<(8 * 8) {
        pixels[index * 4 + 0] = 64
        pixels[index * 4 + 1] = 128
        pixels[index * 4 + 2] = 191
        pixels[index * 4 + 3] = 255
    }
    source.replace(region: MTLRegionMake2D(0, 0, 8, 8), mipmapLevel: 0, withBytes: &pixels, bytesPerRow: 8 * 4)
    let destination = try makeTarget(device: device, label: "metal4 present path destination")

    // Both textures must be resident: Metal 4 does no automatic residency, so a
    // missing adopt() is exactly the bug this checks for.
    path.adopt(textures: [source, destination])

    let commandBuffer = path.beginFrame()
    try check(path.encodeCopy(
        commandBuffer: commandBuffer,
        source: source,
        destination: destination,
        pipeline: copyPipeline,
        sampler: copySampler,
        label: "present path test copy"
    ), "Metal4PresentPath.encodeCopy failed")

    // submit() performs the four-step drawable handshake, so it needs a drawable.
    // A detached layer can still vend one; if this host refuses, the encode is
    // abandoned cleanly and the pixel check is skipped rather than reported as a
    // failure of the code under test.
    guard let drawable = layer.nextDrawable() else {
        path.abandonFrame()
        print("Metal 4 present path: constructed and encoded, but this host vended no drawable, so submit was not exercised")
        return
    }
    // submit() now owns the readyEvent wait, so it needs an event and a value.
    // Pre-signalling it to the value being waited on keeps this test independent
    // of a producer queue while still going through the real wait.
    guard let readyEvent = device.makeSharedEvent() else {
        try fail("could not create the ready event")
    }
    readyEvent.signaledValue = 7
    let completed = DispatchSemaphore(value: 0)
    var submitError: Error?
    path.submit(drawable: drawable, readyEvent: readyEvent, eventValue: 7) { error in
        submitError = error
        completed.signal()
    }
    try check(completed.wait(timeout: .now() + .seconds(5)) == .success,
              "the Metal 4 present-path submit did not report completion within 5s")
    try check(submitError == nil,
              "the Metal 4 present-path submit failed: \(String(describing: submitError))")

    var readback = [UInt8](repeating: 0, count: 4)
    destination.getBytes(&readback, bytesPerRow: 4, from: MTLRegionMake2D(0, 0, 1, 1), mipmapLevel: 0)
    try check(readback == [64, 128, 191, 255],
              "present-path copy readback mismatch: \(readback)")

    // An abandoned frame must not wedge the queue. This is the regression test for
    // the one failure mode that would be invisible until it deadlocked: Metal 4's
    // queue.waitForEvent takes effect when called, not when the command buffer is
    // committed, so issuing it before the deadline check — which fires in normal
    // operation — would leave a wait nothing ever satisfies, and every later
    // commit would queue behind it forever. Here a frame is encoded and abandoned
    // exactly as the deadline path does, abandonFrame is called twice to confirm it
    // is idempotent, and then a real frame must still complete.
    let abandoned = path.beginFrame()
    try check(path.encodeCopy(
        commandBuffer: abandoned,
        source: source,
        destination: destination,
        pipeline: copyPipeline,
        sampler: copySampler,
        label: "present path abandoned copy"
    ), "encodeCopy failed on the frame that is about to be abandoned")
    path.abandonFrame()
    path.abandonFrame()

    guard let secondDrawable = layer.nextDrawable() else {
        print("Metal 4 present path: abandon path exercised, but no second drawable was vended")
        return
    }
    let secondCommandBuffer = path.beginFrame()
    try check(path.encodeCopy(
        commandBuffer: secondCommandBuffer,
        source: source,
        destination: destination,
        pipeline: copyPipeline,
        sampler: copySampler,
        label: "present path post-abandon copy"
    ), "encodeCopy failed after an abandoned frame")
    let secondCompleted = DispatchSemaphore(value: 0)
    var secondError: Error?
    readyEvent.signaledValue = 8
    path.submit(drawable: secondDrawable, readyEvent: readyEvent, eventValue: 8) { error in
        secondError = error
        secondCompleted.signal()
    }
    try check(secondCompleted.wait(timeout: .now() + .seconds(5)) == .success,
              "the queue is wedged: no completion within 5s after a frame was abandoned")
    try check(secondError == nil,
              "the post-abandon submit failed: \(String(describing: secondError))")

    print("Metal 4 present path: queue, allocator ring, argument table, residency set, MTL4 interpolator, copy encode and the commit/present handshake all functional, and an abandoned frame leaves the queue usable")
}

private func runPresentPathTest(device: MTLDevice) throws {
    guard #available(macOS 26.0, *) else {
        print("Metal 4 present path test skipped: needs macOS 26")
        return
    }
    try presentPathTest(device: device)
}

/// M5: the bump allocator that replaces set*Bytes.
///
/// Metal 4 removed set*Bytes outright, so uniforms have to be copied into a
/// buffer and bound by GPU address. The properties that matter are that the
/// address the allocator returns really is where the bytes landed, that a second
/// allocation in the same frame does not overlap the first, that overflow is
/// reported rather than silently dropping a binding, and that reset reuses the
/// space. Only a GPU read can confirm the first one, which is why this draws
/// with the uniform instead of just inspecting the pointer arithmetic.
@available(macOS 26.0, *)
private func bumpAllocatorTest(device: MTLDevice) throws {
    guard let ring = Metal4BumpAllocatorRing(device: device) else {
        try fail("could not create the bump allocator ring")
    }
    let allocator = ring.beginFrame()

    // A first allocation of an odd length, so the second one is only correctly
    // placed if alignment is actually applied.
    var filler: UInt8 = 0xAB
    guard allocator.allocate(bytes: &filler, length: 1) != nil else {
        try fail("the first bump allocation failed")
    }

    var color = SIMD4<Float>(0.25, 0.50, 0.75, 1.0)
    guard let uniformAddress = withUnsafeBytes(of: &color, { bytes in
        allocator.allocate(bytes: bytes.baseAddress!, length: bytes.count)
    }) else {
        try fail("the uniform bump allocation failed")
    }
    try check(uniformAddress % 16 == 0,
              "bump allocation is not 16-byte aligned: offset \(uniformAddress % 16)")
    try check(uniformAddress > allocator.backing.gpuAddress,
              "the uniform was placed on top of the preceding allocation")

    // Largest real uniform is MotionUniforms at 240 B; confirm one frame can hold
    // a realistic number of them, then that overflow is refused rather than
    // wrapping or overwriting.
    var chunk = [UInt8](repeating: 0, count: 240)
    var accepted = 0
    while chunk.withUnsafeBytes({ allocator.allocate(bytes: $0.baseAddress!, length: 240) }) != nil {
        accepted += 1
        if accepted > 4096 { break }
    }
    try check(accepted >= 200,
              "only \(accepted) largest-case uniforms fit in one frame; capacity is too small")
    var overflow: UInt8 = 0
    try check(allocator.allocate(bytes: &overflow, length: Metal4BumpAllocatorRing.capacityPerFrame) == nil,
              "an allocation larger than the whole arena was accepted")
    ring.logOverflowOnce(Metal4BumpAllocatorRing.capacityPerFrame)

    // reset() must make the space available again, which is what the ring relies on.
    let recycled = ring.beginFrame()
    guard let recycledAddress = withUnsafeBytes(of: &color, { bytes in
        recycled.allocate(bytes: bytes.baseAddress!, length: bytes.count)
    }) else {
        try fail("allocation after a ring rotation failed")
    }
    try check(recycledAddress == recycled.backing.gpuAddress,
              "a rotated allocator did not start from the beginning of its arena")

    // Now the part only the GPU can answer: is the uniform actually readable at
    // the address the allocator handed back?
    let library = try device.makeLibrary(source: bumpShaderSource, options: nil)
    guard let vertexFunction = library.makeFunction(name: "bump_vs"),
          let fragmentFunction = library.makeFunction(name: "bump_fs") else {
        try fail("missing bump MSL entry points")
    }
    let pipelineDescriptor = MTLRenderPipelineDescriptor()
    pipelineDescriptor.vertexFunction = vertexFunction
    pipelineDescriptor.fragmentFunction = fragmentFunction
    pipelineDescriptor.colorAttachments[0].pixelFormat = .rgba8Unorm
    let pipeline = try device.makeRenderPipelineState(descriptor: pipelineDescriptor)
    let target = try makeTarget(device: device, label: "bump allocator target")

    guard let queue = device.makeMTL4CommandQueue(),
          let commandBuffer = device.makeCommandBuffer(),
          let commandAllocator = device.makeCommandAllocator(),
          let completionEvent = device.makeSharedEvent() else {
        try fail("could not create the Metal 4 objects for the bump test")
    }
    // The arena is registered with the global residency set when it is created,
    // but that set is attached to the Metal 3 queue; this queue needs its own.
    let residencyDescriptor = MTLResidencySetDescriptor()
    residencyDescriptor.initialCapacity = 4
    let residencySet = try device.makeResidencySet(descriptor: residencyDescriptor)
    residencySet.addAllocations([recycled.backing, target])
    residencySet.commit()
    residencySet.requestResidency()
    queue.addResidencySet(residencySet)

    let argumentTableDescriptor = MTL4ArgumentTableDescriptor()
    argumentTableDescriptor.maxBufferBindCount = 1
    argumentTableDescriptor.initializeBindings = true
    let argumentTable = try device.makeArgumentTable(descriptor: argumentTableDescriptor)

    commandAllocator.reset()
    commandBuffer.beginCommandBuffer(allocator: commandAllocator)
    let passDescriptor = MTL4RenderPassDescriptor()
    passDescriptor.colorAttachments[0].texture = target
    passDescriptor.colorAttachments[0].loadAction = .dontCare
    passDescriptor.colorAttachments[0].storeAction = .store
    passDescriptor.renderTargetWidth = 8
    passDescriptor.renderTargetHeight = 8
    guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: passDescriptor) else {
        commandBuffer.endCommandBuffer()
        try fail("could not create the render encoder for the bump test")
    }
    // This is the set*Bytes replacement in one line.
    argumentTable.setAddress(recycledAddress, index: 0)
    encoder.setArgumentTable(argumentTable, stages: .fragment)
    encoder.setRenderPipelineState(pipeline)
    encoder.setViewport(MTLViewport(originX: 0, originY: 0, width: 8, height: 8, znear: 0, zfar: 1))
    encoder.drawPrimitives(primitiveType: .triangle, vertexStart: 0, vertexCount: 3)
    encoder.endEncoding()
    commandBuffer.endCommandBuffer()
    queue.commit([commandBuffer])
    queue.signalEvent(completionEvent, value: 1)
    try check(completionEvent.wait(untilSignaledValue: 1, timeoutMS: 5000),
              "the bump-allocator draw did not complete within 5s")

    var readback = [UInt8](repeating: 0, count: 4)
    target.getBytes(&readback, bytesPerRow: 4, from: MTLRegionMake2D(0, 0, 1, 1), mipmapLevel: 0)
    try check(readback == [64, 128, 191, 255],
              "the GPU did not read the bump-allocated uniform: \(readback)")
    print("Metal 4 bump allocator: \(accepted) largest-case (240 B) uniforms fit per frame, alignment and overflow behave, ring rotation recycles, and the GPU reads the uniform at the returned address")
}

private func runBumpAllocatorTest(device: MTLDevice) throws {
    guard #available(macOS 26.0, *) else {
        print("bump allocator test skipped: needs macOS 26")
        return
    }
    try bumpAllocatorTest(device: device)
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

    // (6) M4: the frame-generation present path's Metal 4 object graph.
    try runPresentPathTest(device: device)

    // (7) M5: the bump allocator that replaces set*Bytes.
    try runBumpAllocatorTest(device: device)

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
