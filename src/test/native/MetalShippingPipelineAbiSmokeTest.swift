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

@main
private struct MetalShippingPipelineAbiSmokeTest {
    static func main() {
        do {
            guard CommandLine.arguments.count == 2 else {
                try fail("usage: MetalShippingPipelineAbiSmokeTest /path/to/libmetallum.dylib")
            }
            let dylibPath = CommandLine.arguments[1]
            guard let image = dlopen(dylibPath, RTLD_NOW | RTLD_LOCAL) else {
                let error = dlerror().map { String(cString: $0) } ?? "unknown dlopen error"
                try fail("dlopen failed for \(dylibPath): \(error)")
            }
            defer { dlclose(image) }

            let createDevice = try loadSymbol(image, "metallum_create_system_default_device", as: CreateDeviceFn.self)
            let createFunction = try loadSymbol(image, "metallum_create_shader_function", as: CreateShaderFunctionFn.self)
            let createDescriptor = try loadSymbol(image, "metallum_MTLRenderPipelineDescriptor_create", as: CreatePipelineDescriptorFn.self)
            let setFunctions = try loadSymbol(image, "metallum_MTLRenderPipelineDescriptor_setCompiledFunctions", as: SetCompiledFunctionsFn.self)
            let setColorFormat = try loadSymbol(image, "metallum_MTLRenderPipelineDescriptor_setColorAttachmentFormat", as: SetColorAttachmentFormatFn.self)
            let makePipeline = try loadSymbol(image, "metallum_MTLDevice_makeRenderPipelineState", as: MakeRenderPipelineStateFn.self)
            let releaseObject = try loadSymbol(image, "metallum_release_object", as: ReleaseObjectFn.self)

            guard let device = createDevice() else {
                try fail("shipping metallum_create_system_default_device returned nil")
            }
            defer { releaseObject(device) }

            let vertexFunction: UnsafeMutableRawPointer? = shaderSource.withCString { source in
                "abi_smoke_vs".withCString { entry in
                    createFunction(device, source, entry)
                }
            }
            guard let vertexFunction else {
                try fail("shipping metallum_create_shader_function returned nil for vertex shader")
            }
            defer { releaseObject(vertexFunction) }

            let fragmentFunction: UnsafeMutableRawPointer? = shaderSource.withCString { source in
                "abi_smoke_fs".withCString { entry in
                    createFunction(device, source, entry)
                }
            }
            guard let fragmentFunction else {
                try fail("shipping metallum_create_shader_function returned nil for fragment shader")
            }
            defer { releaseObject(fragmentFunction) }

            guard let descriptor = createDescriptor() else {
                try fail("shipping metallum_MTLRenderPipelineDescriptor_create returned nil")
            }
            defer { releaseObject(descriptor) }

            setFunctions(descriptor, vertexFunction, fragmentFunction)
            try check(
                setColorFormat(descriptor, 0, MTLPixelFormat.rgba8Unorm.rawValue) != 0,
                "shipping bridge rejected RGBA8 color attachment 0"
            )

            guard let pipeline = makePipeline(device, descriptor) else {
                try fail("shipping metallum_MTLDevice_makeRenderPipelineState returned nil")
            }
            releaseObject(pipeline)

            print("SHIPPING_PIPELINE_C_ABI_PASS device=shipping-dylib format=rgba8Unorm")
        } catch {
            fputs("SHIPPING_PIPELINE_C_ABI_FAIL: \(error)\n", stderr)
            exit(1)
        }
    }
}
