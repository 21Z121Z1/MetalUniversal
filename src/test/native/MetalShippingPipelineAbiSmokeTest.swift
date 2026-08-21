import Darwin
import Foundation
import Metal

private enum SmokeFailure: Error, CustomStringConvertible {
    case message(String)

    var description: String {
        switch self {
        case .message(let message): return message
        }
    }
}

private func fail(_ message: String) throws -> Never {
    throw SmokeFailure.message(message)
}

private func check(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    if !condition() { try fail(message) }
}

private func loadSymbol<T>(_ image: UnsafeMutableRawPointer, _ name: String, as: T.Type) throws -> T {
    dlerror()
    guard let symbol = dlsym(image, name) else {
        let error = dlerror().map { String(cString: $0) } ?? "unknown dlsym error"
        try fail("missing shipping symbol \(name): \(error)")
    }
    return unsafeBitCast(symbol, to: T.self)
}

private typealias CreateDeviceFn = @convention(c) () -> UnsafeMutableRawPointer?
private typealias CreateShaderFunctionFn = @convention(c) (
    UnsafeMutableRawPointer?, UnsafePointer<CChar>?, UnsafePointer<CChar>?
) -> UnsafeMutableRawPointer?
private typealias CreatePipelineDescriptorFn = @convention(c) () -> UnsafeMutableRawPointer?
private typealias SetCompiledFunctionsFn = @convention(c) (
    UnsafeMutableRawPointer?, UnsafeMutableRawPointer?, UnsafeMutableRawPointer?
) -> Void
private typealias SetColorAttachmentFormatFn = @convention(c) (
    UnsafeMutableRawPointer?, Int32, UInt
) -> Int32
private typealias MakeRenderPipelineStateFn = @convention(c) (
    UnsafeMutableRawPointer?, UnsafeMutableRawPointer?
) -> UnsafeMutableRawPointer?
private typealias ReleaseObjectFn = @convention(c) (UnsafeMutableRawPointer?) -> Void

private let shaderSource = """
#include <metal_stdlib>
using namespace metal;

struct SmokeVertexOut {
    float4 position [[position]];
};

vertex SmokeVertexOut abi_smoke_vs(uint vertexID [[vertex_id]]) {
    const float2 positions[3] = {
        float2(-1.0, -1.0),
        float2( 3.0, -1.0),
        float2(-1.0,  3.0)
    };
    SmokeVertexOut output;
    output.position = float4(positions[vertexID], 0.0, 1.0);
    return output;
}

fragment float4 abi_smoke_fs() {
    return float4(0.25, 0.50, 0.75, 1.0);
}
"""

// This probe measures RAW Metal behavior in this environment, so the dylib's
// own compiler-thread hop must stay out of the way: METALLUM_PSO_COMPILE_HOP=0
// makes every shipping entry execute inline on the caller's thread.
setenv("METALLUM_PSO_COMPILE_HOP", "0", 1)

private struct Symbols {
    let createDevice: CreateDeviceFn
    let createFunction: CreateShaderFunctionFn
    let createDescriptor: CreatePipelineDescriptorFn
    let setFunctions: SetCompiledFunctionsFn
    let setColorFormat: SetColorAttachmentFormatFn
    let makePipeline: MakeRenderPipelineStateFn
    let releaseObject: ReleaseObjectFn
}

/// One full device -> MSL -> descriptor -> render PSO sequence through the
/// shipping C ABI. Everything is created, used and released inside here so a
/// phase can run on any thread without sharing Metal objects across threads.
private func runSequence(_ symbols: Symbols) throws {
    guard let device = symbols.createDevice() else {
        try fail("shipping metallum_create_system_default_device returned nil")
    }
    defer { symbols.releaseObject(device) }

    let vertexFunction: UnsafeMutableRawPointer? = shaderSource.withCString { source in
        "abi_smoke_vs".withCString { entry in
            symbols.createFunction(device, source, entry)
        }
    }
    guard let vertexFunction else {
        try fail("shipping metallum_create_shader_function returned nil for vertex shader")
    }
    defer { symbols.releaseObject(vertexFunction) }

    let fragmentFunction: UnsafeMutableRawPointer? = shaderSource.withCString { source in
        "abi_smoke_fs".withCString { entry in
            symbols.createFunction(device, source, entry)
        }
    }
    guard let fragmentFunction else {
        try fail("shipping metallum_create_shader_function returned nil for fragment shader")
    }
    defer { symbols.releaseObject(fragmentFunction) }

    guard let descriptor = symbols.createDescriptor() else {
        try fail("shipping metallum_MTLRenderPipelineDescriptor_create returned nil")
    }
    defer { symbols.releaseObject(descriptor) }

    symbols.setFunctions(descriptor, vertexFunction, fragmentFunction)
    try check(
        symbols.setColorFormat(descriptor, 0, MTLPixelFormat.rgba8Unorm.rawValue) != 0,
        "shipping bridge rejected RGBA8 color attachment 0"
    )

    guard let pipeline = symbols.makePipeline(device, descriptor) else {
        try fail("shipping metallum_MTLDevice_makeRenderPipelineState returned nil")
    }
    symbols.releaseObject(pipeline)
}

private func runSmoke() throws {
    guard CommandLine.arguments.count == 2 else {
        try fail("usage: MetalShippingPipelineAbiSmokeTest /path/to/libmetallum.dylib")
    }
    let dylibPath = CommandLine.arguments[1]
    guard let image = dlopen(dylibPath, RTLD_NOW | RTLD_LOCAL) else {
        let error = dlerror().map { String(cString: $0) } ?? "unknown dlopen error"
        try fail("dlopen failed for \(dylibPath): \(error)")
    }
    defer { dlclose(image) }

    let symbols = Symbols(
        createDevice: try loadSymbol(image, "metallum_create_system_default_device", as: CreateDeviceFn.self),
        createFunction: try loadSymbol(image, "metallum_create_shader_function", as: CreateShaderFunctionFn.self),
        createDescriptor: try loadSymbol(image, "metallum_MTLRenderPipelineDescriptor_create", as: CreatePipelineDescriptorFn.self),
        setFunctions: try loadSymbol(image, "metallum_MTLRenderPipelineDescriptor_setCompiledFunctions", as: SetCompiledFunctionsFn.self),
        setColorFormat: try loadSymbol(image, "metallum_MTLRenderPipelineDescriptor_setColorAttachmentFormat", as: SetColorAttachmentFormatFn.self),
        makePipeline: try loadSymbol(image, "metallum_MTLDevice_makeRenderPipelineState", as: MakeRenderPipelineStateFn.self),
        releaseObject: try loadSymbol(image, "metallum_release_object", as: ReleaseObjectFn.self)
    )

    // Phase 1: process main thread. This is the historically passing context.
    try runSequence(symbols)
    print("SHIPPING_PIPELINE_C_ABI_PASS device=shipping-dylib format=rgba8Unorm")

    // Phase 2: one dedicated background thread running the identical sequence
    // with no shared Metal objects. A crash here is itself the measurement:
    // the process dies by signal and the harness records
    // background_pso_compile=false instead of pretending the gate passed.
    let done = DispatchSemaphore(value: 0)
    let backgroundOutcome = SmokeOutcome()
    let thread = Thread {
        do {
            try runSequence(symbols)
            backgroundOutcome.passed = true
        } catch {
            fputs("SHIPPING_PIPELINE_BACKGROUND_THREAD_FAIL: \(error)\n", stderr)
        }
        done.signal()
    }
    thread.name = "MetallumAbiBackgroundProbe"
    thread.stackSize = 8 << 20
    thread.start()
    _ = done.wait(timeout: .distantFuture)
    if backgroundOutcome.passed {
        print("SHIPPING_PIPELINE_BACKGROUND_THREAD_C_ABI_PASS device=shipping-dylib format=rgba8Unorm")
    } else {
        try fail("background-thread pipeline sequence failed (see SHIPPING_PIPELINE_BACKGROUND_THREAD_FAIL above)")
    }
}

/// Box for the background thread's outcome; Thread closures are escaping.
private final class SmokeOutcome {
    var passed = false
}

do {
    try runSmoke()
} catch {
    fputs("SHIPPING_PIPELINE_C_ABI_FAIL: \(error)\n", stderr)
    exit(1)
}
