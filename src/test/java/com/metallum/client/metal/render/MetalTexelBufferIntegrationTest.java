package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Physical Metal coverage for the two fixed Iris typed texel-buffer providers. */
@EnabledOnOs(OS.MAC)
final class MetalTexelBufferIntegrationTest {
    private static final int SIZE = 8;
    private static final int BUFFER_BYTES = 256;

    private static final String VERTEX_SHADER = """
            #version 450
            void main() {
                vec2 positions[3] = vec2[](
                    vec2(-1.0, -1.0),
                    vec2( 3.0, -1.0),
                    vec2(-1.0,  3.0)
                );
                gl_Position = vec4(positions[gl_VertexIndex], 0.0, 1.0);
            }
            """;

    private static final String FRAGMENT_SHADER = """
            #version 450
            layout(binding = 0) uniform isamplerBuffer u_SectionTimeInfo;
            layout(binding = 1) uniform isamplerBuffer CloudFaces;
            layout(location = 0) out vec4 fragColor;
            void main() {
                int sectionTime = texelFetch(u_SectionTimeInfo, 0).r;
                int cloudFace = texelFetch(CloudFaces, 0).r;
                fragColor = sectionTime == 37 && cloudFace == -5
                        ? vec4(0.0, 1.0, 0.0, 1.0)
                        : vec4(1.0, 0.0, 0.0, 1.0);
            }
            """;

    private MetalDevice device;
    private MetalCommandEncoder encoder;

    @BeforeEach
    void createDevice() {
        MemorySegment nativeDevice = MetalNativeBridge.metallum_create_system_default_device();
        assertFalse(MetalNativeBridge.isNullHandle(nativeDevice), "MTLCreateSystemDefaultDevice returned null");
        ShaderSource shaders = (identifier, type) -> type == ShaderType.VERTEX
                ? VERTEX_SHADER
                : FRAGMENT_SHADER;
        device = new MetalDevice(
                shaders,
                new GpuDebugOptions(2, true, true, true),
                nativeDevice,
                MemorySegment.NULL,
                "Metal fixed Iris texel-buffer integration device",
                MemorySegment.NULL
        );
        encoder = device.createCommandEncoder();
    }

    @AfterEach
    void closeDevice() {
        if (device != null) {
            device.close();
        }
    }

    @Test
    void fixedR8AndR32BuffersBindThroughRenderPassSetUniformAndReadBack() {
        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation("metallum_test/fixed_iris_texel_buffers")
                .withVertexShader("metallum_test/fixed_iris_texel_buffers")
                .withFragmentShader("metallum_test/fixed_iris_texel_buffers")
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform("u_SectionTimeInfo", UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT)
                        .withUniform("CloudFaces", UniformType.TEXEL_BUFFER, GpuFormat.R8_SINT)
                        .build())
                .withColorTargetState(0, new ColorTargetState(
                        Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL
                ))
                .build();

        MetalCompiledRenderPipeline compiled = device.getOrCompilePipeline(pipeline);
        assertTrue(compiled.isValid(), "fixed Iris texel-buffer PSO must be valid");
        assertEquals(GpuFormat.R32_SINT, compiled.resource("u_SectionTimeInfo").texelBufferFormat());
        assertEquals(GpuFormat.R8_SINT, compiled.resource("CloudFaces").texelBufferFormat());

        ByteBuffer sectionData = ByteBuffer.allocateDirect(BUFFER_BYTES).order(ByteOrder.nativeOrder());
        sectionData.putInt(0, 37);
        ByteBuffer cloudData = ByteBuffer.allocateDirect(BUFFER_BYTES).order(ByteOrder.nativeOrder());
        cloudData.put(0, (byte) -5);
        try (MetalGpuBuffer section = (MetalGpuBuffer) device.createBuffer(
                () -> "fixed Iris u_SectionTimeInfo",
                GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST,
                sectionData
        ); MetalGpuBuffer clouds = (MetalGpuBuffer) device.createBuffer(
                () -> "fixed Iris CloudFaces",
                GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST,
                cloudData
        ); MetalGpuTexture target = (MetalGpuTexture) device.createTexture(
                "fixed Iris texel-buffer target",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                GpuFormat.RGBA8_UNORM,
                SIZE,
                SIZE,
                1,
                1
        ); MetalGpuTextureView targetView = new MetalGpuTextureView(target, 0, 1)) {
            RenderPassDescriptor descriptor = RenderPassDescriptor.create(
                            () -> "fixed Iris texel-buffer draw"
                    )
                    .withColorAttachment(
                            targetView,
                            Optional.of(new Vector4f(1.0F, 0.0F, 0.0F, 1.0F))
                    )
                    .withRenderArea(new RenderPass.RenderArea(0, 0, SIZE, SIZE));
            MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor);
            pass.setPipeline(pipeline);
            pass.setUniform("u_SectionTimeInfo", section.slice());
            pass.setUniform("CloudFaces", clouds.slice());
            pass.draw(3, 1, 0, 0);
            encoder.submitRenderPass();
            encoder.submit();
            device.waitForSubmittedGpuWork();

            ByteBuffer pixels = readback(target);
            assertEquals(0, Byte.toUnsignedInt(pixels.get(0)), "red channel");
            assertEquals(255, Byte.toUnsignedInt(pixels.get(1)), "green channel");
            assertEquals(0, Byte.toUnsignedInt(pixels.get(2)), "blue channel");
            assertEquals(255, Byte.toUnsignedInt(pixels.get(3)), "alpha channel");
        }
    }

    private ByteBuffer readback(final MetalGpuTexture texture) {
        int bytes = SIZE * SIZE * texture.pixelSize();
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "fixed Iris texel-buffer readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                bytes
        )) {
            encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
            }, 0);
            encoder.submit();
            device.waitForSubmittedGpuWork();
            ByteBuffer source = buffer.currentStorage().limit(bytes).slice().order(ByteOrder.nativeOrder());
            ByteBuffer copy = ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder());
            copy.put(source).flip();
            return copy;
        }
    }
}
