package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.metallum.client.metal.render.mtl.MTLRenderPipelineDescriptor;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Minimal JVM -> FFM -> shipping Swift render-pipeline smoke.
 *
 * <p>This intentionally mirrors MetalShippingPipelineAbiSmokeTest: identical
 * handwritten MSL, one RGBA8 attachment and no vertex descriptor. The native
 * dlopen/dlsym version runs immediately before this test on hosted macOS. If
 * native passes and this test crashes, the first divergence is the JVM/FFM
 * boundary rather than the shipping C ABI or the Metal shader itself.</p>
 */
final class MetalNativeBridgeRenderPipelineSmokeTest {
    private static final String SHADER_SOURCE = """
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
            """;

    @Test
    void simpleRgba8PipelineCompilesThroughFfm() {
        MemorySegment device = MemorySegment.NULL;
        MemorySegment vertexFunction = MemorySegment.NULL;
        MemorySegment fragmentFunction = MemorySegment.NULL;
        MemorySegment pipeline = MemorySegment.NULL;
        try {
            device = MetalNativeBridge.metallum_create_system_default_device();
            assertFalse(MetalNativeBridge.isNullHandle(device), "system Metal device is unavailable");

            vertexFunction = MetalNativeBridge.metallum_create_shader_function(
                    device,
                    SHADER_SOURCE,
                    "abi_smoke_vs"
            );
            assertFalse(MetalNativeBridge.isNullHandle(vertexFunction), "vertex MSL failed to compile");

            fragmentFunction = MetalNativeBridge.metallum_create_shader_function(
                    device,
                    SHADER_SOURCE,
                    "abi_smoke_fs"
            );
            assertFalse(MetalNativeBridge.isNullHandle(fragmentFunction), "fragment MSL failed to compile");

            try (MTLRenderPipelineDescriptor descriptor = new MTLRenderPipelineDescriptor()) {
                descriptor.setCompiledFunctions(vertexFunction, fragmentFunction);
                descriptor.setColorAttachmentFormat(0, MTLPixelFormat.RGBA8Unorm);
                pipeline = MetalNativeBridge.metallum_MTLDevice_makeRenderPipelineState(
                        device,
                        descriptor.handle()
                );
            }

            assertFalse(MetalNativeBridge.isNullHandle(pipeline), "RGBA8 render PSO creation returned nil");
        } finally {
            if (!MetalNativeBridge.isNullHandle(pipeline)) {
                MetalNativeBridge.metallum_release_object(pipeline);
            }
            if (!MetalNativeBridge.isNullHandle(fragmentFunction)) {
                MetalNativeBridge.metallum_release_object(fragmentFunction);
            }
            if (!MetalNativeBridge.isNullHandle(vertexFunction)) {
                MetalNativeBridge.metallum_release_object(vertexFunction);
            }
            if (!MetalNativeBridge.isNullHandle(device)) {
                MetalNativeBridge.metallum_release_object(device);
            }
        }
    }
}
