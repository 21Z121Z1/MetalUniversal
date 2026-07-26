// Metal 4 migration spec (MinecraftMetal_Metal4_Migration_Specs_2026-07-27.md),
// M2 step 0 — the gate that decides whether M2 can be done at all.
//
// All of M2 (MTL4Compiler, flexible/unspecialized PSOs, PipelineDataSet
// archiving) rests on one runtime assumption: a pipeline state built by
// MTL4Compiler is an ordinary MTLRenderPipelineState and can be bound to an
// ordinary *Metal 3* MTLRenderCommandEncoder. If that holds, M2 needs zero
// encoder changes and can land long before the main queue moves to Metal 4
// (M7). If it does not hold, M2 must be deferred until after M7.
//
// The test answers it the only way that counts: build the PSO through
// MTL4Compiler, draw with it on a Metal 3 queue/command buffer/encoder, and read
// the pixel back. It asks the same question a second time for a pipeline created
// by specialization from an unspecialized parent, because M2c binds the variant
// matrix to that path.
//
// The library is created with the Metal 3 device.makeLibrary(source:) entry
// point on purpose: that is what metallum_create_shader_function does today, and
// M2a keeps it (the MSL disk cache from S8 hangs off it), so a Metal-3-built
// MTLLibrary feeding an MTL4LibraryFunctionDescriptor is the production shape.

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

vertex VertexOut mtl4_smoke_vs(uint vertexID [[vertex_id]]) {
    const float2 positions[3] = {
        float2(-1.0, -1.0),
        float2( 3.0, -1.0),
        float2(-1.0,  3.0)
    };
    VertexOut output;
    output.position = float4(positions[vertexID], 0.0, 1.0);
    return output;
}

fragment float4 mtl4_smoke_fs() {
    return float4(0.25, 0.50, 0.75, 1.0);
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

private func makeTarget(device: MTLDevice, width: Int, height: Int, label: String) throws -> MTLTexture {
    let descriptor = MTLTextureDescriptor.texture2DDescriptor(
        pixelFormat: .rgba8Unorm,
        width: width,
        height: height,
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

/// Draws the full-screen triangle with `pipeline` on a plain Metal 3 encoder.
/// Nothing in here is Metal 4 — that is the whole point of the test.
private func renderOnMetal3(
    queue: MTLCommandQueue,
    pipeline: MTLRenderPipelineState,
    target: MTLTexture,
    label: String
) throws {
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
}

private func readRGBA8(_ texture: MTLTexture) -> [UInt8] {
    var values = [UInt8](repeating: 0, count: 4)
    texture.getBytes(&values, bytesPerRow: 4, from: MTLRegionMake2D(0, 0, 1, 1), mipmapLevel: 0)
    return values
}

/// float4(0.25, 0.50, 0.75, 1.0) quantized to rgba8Unorm.
private func checkSmokePixel(_ texture: MTLTexture, _ label: String) throws {
    let rgba = readRGBA8(texture)
    try check(rgba[0] == 64 && rgba[1] == 128 && rgba[2] == 191 && rgba[3] == 255,
              "\(label) readback mismatch: \(rgba)")
}

@available(macOS 26.0, iOS 26.0, *)
private func makeMetal4Descriptor(library: MTLLibrary, colorFormat: MTLPixelFormat) -> MTL4RenderPipelineDescriptor {
    let vertexDescriptor = MTL4LibraryFunctionDescriptor()
    vertexDescriptor.library = library
    vertexDescriptor.name = "mtl4_smoke_vs"
    let fragmentDescriptor = MTL4LibraryFunctionDescriptor()
    fragmentDescriptor.library = library
    fragmentDescriptor.name = "mtl4_smoke_fs"
    let descriptor = MTL4RenderPipelineDescriptor()
    descriptor.label = "metal4-smoke"
    descriptor.vertexFunctionDescriptor = vertexDescriptor
    descriptor.fragmentFunctionDescriptor = fragmentDescriptor
    descriptor.rasterSampleCount = 1
    guard let attachment = descriptor.colorAttachments[0] else {
        return descriptor
    }
    attachment.pixelFormat = colorFormat
    attachment.writeMask = .all
    attachment.blendingState = .disabled
    return descriptor
}

@available(macOS 26.0, iOS 26.0, *)
private func runMetal4SmokeTest(device: MTLDevice, queue: MTLCommandQueue, library: MTLLibrary) throws {
    let compilerDescriptor = MTL4CompilerDescriptor()
    compilerDescriptor.label = "metal4-smoke-compiler"
    let compiler: MTL4Compiler
    do {
        compiler = try device.makeCompiler(descriptor: compilerDescriptor)
    } catch {
        try fail("could not create MTL4Compiler: \(error)")
    }

    // (1) direct MTL4Compiler PSO -> Metal 3 encoder
    let descriptor = makeMetal4Descriptor(library: library, colorFormat: .rgba8Unorm)
    let pipeline: MTLRenderPipelineState
    do {
        pipeline = try compiler.makeRenderPipelineState(descriptor: descriptor)
    } catch {
        try fail("MTL4Compiler could not create the pipeline state: \(error)")
    }
    let directTarget = try makeTarget(device: device, width: 8, height: 8, label: "metal4 smoke direct")
    try renderOnMetal3(
        queue: queue,
        pipeline: pipeline,
        target: directTarget,
        label: "MTL4Compiler PSO on Metal 3 encoder"
    )
    try checkSmokePixel(directTarget, "direct MTL4 PSO")

    // (2) unspecialized parent + specialization -> Metal 3 encoder (M2c's path)
    guard let attachment = descriptor.colorAttachments[0] else {
        try fail("Metal did not provide an MTL4 color attachment descriptor for slot 0")
    }
    attachment.pixelFormat = .unspecialized
    attachment.blendingState = .unspecialized
    let generic: MTLRenderPipelineState
    do {
        generic = try compiler.makeRenderPipelineState(descriptor: descriptor)
    } catch {
        try fail("MTL4Compiler could not create the unspecialized pipeline state: \(error)")
    }
    attachment.pixelFormat = .rgba8Unorm
    attachment.blendingState = .disabled
    let specialized: MTLRenderPipelineState
    do {
        specialized = try compiler.makeRenderPipelineStateBySpecialization(descriptor: descriptor, pipeline: generic)
    } catch {
        try fail("MTL4Compiler could not specialize the pipeline state: \(error)")
    }
    let specializedTarget = try makeTarget(device: device, width: 8, height: 8, label: "metal4 smoke specialized")
    try renderOnMetal3(
        queue: queue,
        pipeline: specialized,
        target: specializedTarget,
        label: "specialized MTL4 PSO on Metal 3 encoder"
    )
    try checkSmokePixel(specializedTarget, "specialized MTL4 PSO")

    print("Metal 4 PSO smoke passed: MTL4Compiler and specialized-from-unspecialized pipeline states both draw correctly on a Metal 3 render encoder")
}

private func runSmokeTest() throws {
    guard let device = MTLCreateSystemDefaultDevice() else {
        try fail("MTLCreateSystemDefaultDevice returned nil")
    }
    guard let queue = device.makeCommandQueue() else {
        try fail("could not create Metal command queue")
    }

    // Same two gates the production code uses (spec M0.7): the compile-time
    // #available and the run-time supportsFamily(.metal4). A host without both
    // cannot answer the question, so it skips rather than reporting a failure
    // that is really "not applicable here".
    guard #available(macOS 26.0, iOS 26.0, *) else {
        print("Metal 4 PSO smoke skipped: built or running against a pre-Metal-4 OS")
        return
    }
    guard device.supportsFamily(.metal4) else {
        print("Metal 4 PSO smoke skipped: \(device.name) does not support MTLGPUFamily.metal4")
        return
    }
    print("Metal 4 PSO smoke: \(device.name) reports MTLGPUFamily.metal4 support")

    let library: MTLLibrary
    do {
        library = try device.makeLibrary(source: shaderSource, options: nil)
    } catch {
        try fail("could not compile the smoke MSL: \(error)")
    }
    try runMetal4SmokeTest(device: device, queue: queue, library: library)
}

do {
    try runSmokeTest()
} catch {
    fputs("Metal 4 PSO smoke failed: \(error)\n", stderr)
    exit(1)
}
