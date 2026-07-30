package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
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

@EnabledOnOs(OS.MAC)
final class MetalGenericVertexAttributeIntegrationTest {
    private static final int SIZE = 8;

    private static final String VERTEX_SHADER = """
            #version 450
            in vec3 Position;
            in vec2 UV0;
            in ivec3 iris_Entity;
            flat out ivec3 entityValue;
            void main() {
                gl_Position = vec4(Position, 1.0);
                entityValue = iris_Entity;
            }
            """;

    private static final String FRAGMENT_SHADER = """
            #version 450
            flat in ivec3 entityValue;
            layout(location = 0) out vec4 fragColor;
            void main() {
                bool isDefault = all(equal(entityValue, ivec3(0)));
                fragColor = isDefault ? vec4(0.0, 1.0, 0.0, 1.0) : vec4(1.0, 0.0, 0.0, 1.0);
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
                "Generic vertex attribute integration device",
                MemorySegment.NULL
        );
        encoder = device.commandEncoder();
    }

    @AfterEach
    void closeDevice() {
        MetalFxManager.close();
        if (device != null) {
            device.close();
        }
    }

    @Test
    void constantStepBufferSuppliesMissingActiveInput() {
        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation("metallum_test/generic_vertex_current")
                .withVertexShader("metallum_test/generic_vertex_current")
                .withFragmentShader("metallum_test/generic_vertex_current")
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
                .withColorTargetState(0, new ColorTargetState(
                        Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL
                ))
                .build();

        MetalCompiledRenderPipeline compiled = device.getOrCompilePipeline(pipeline);
        assertTrue(compiled.isValid(), "constant-step generic-input PSO must be valid");
        assertEquals(
                compiled.firstAvailableVertexBufferSlot() + compiled.vertexBufferCount(),
                compiled.genericVertexBufferSlot(),
                "generic-current buffer must follow resource and physical vertex-buffer slots"
        );
        assertTrue(compiled.genericVertexBufferSlot() < MetalCompiledRenderPipeline.MAX_METAL_VERTEX_SLOTS);
        assertTrue((device.genericVertexAttributeBuffer().usage() & GpuBuffer.USAGE_VERTEX) != 0);

        ByteBuffer vertices = fullScreenTriangle();
        try (MetalGpuBuffer vertexBuffer = (MetalGpuBuffer) device.createBuffer(
                () -> "generic-current triangle",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                vertices
        ); MetalGpuTexture target = (MetalGpuTexture) device.createTexture(
                "generic-current target",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                GpuFormat.RGBA8_UNORM,
                SIZE,
                SIZE,
                1,
                1
        ); MetalGpuTextureView view = new MetalGpuTextureView(target, 0, 1)) {
            RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "generic current attribute draw")
                    .withColorAttachment(view, Optional.of(new Vector4f(1.0F, 0.0F, 0.0F, 1.0F)))
                    .withRenderArea(new RenderPass.RenderArea(0, 0, SIZE, SIZE));
            MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor);
            pass.setPipeline(pipeline);
            pass.setVertexBuffer(0, vertexBuffer.slice());
            pass.draw(3, 1, 0, 0);
            encoder.submitRenderPass();
            encoder.submit();
            device.waitForSubmittedGpuWork();

            ByteBuffer pixels = readback(target);
            assertPixel(pixels, 0);
            assertPixel(pixels, (SIZE * SIZE / 2) * 4);
        }
    }

    private ByteBuffer readback(final MetalGpuTexture texture) {
        int bytes = SIZE * SIZE * texture.pixelSize();
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "generic-current readback",
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

    private static ByteBuffer fullScreenTriangle() {
        int stride = DefaultVertexFormat.POSITION_TEX.getVertexSize();
        VertexFormatElement position = element("Position");
        VertexFormatElement uv = element("UV0");
        ByteBuffer data = ByteBuffer.allocateDirect(3 * stride).order(ByteOrder.nativeOrder());
        float[][] points = {{-1.0F, -1.0F}, {3.0F, -1.0F}, {-1.0F, 3.0F}};
        for (int index = 0; index < points.length; index++) {
            int base = index * stride;
            data.putFloat(base + position.offset(), points[index][0]);
            data.putFloat(base + position.offset() + Float.BYTES, points[index][1]);
            data.putFloat(base + position.offset() + 2 * Float.BYTES, 0.0F);
            data.putFloat(base + uv.offset(), 0.0F);
            data.putFloat(base + uv.offset() + Float.BYTES, 0.0F);
        }
        return data;
    }

    private static VertexFormatElement element(final String name) {
        return DefaultVertexFormat.POSITION_TEX.getElements().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static void assertPixel(final ByteBuffer pixels, final int offset) {
        assertEquals(0, Byte.toUnsignedInt(pixels.get(offset)), "red");
        assertEquals(255, Byte.toUnsignedInt(pixels.get(offset + 1)), "green");
        assertEquals(0, Byte.toUnsignedInt(pixels.get(offset + 2)), "blue");
        assertEquals(255, Byte.toUnsignedInt(pixels.get(offset + 3)), "alpha");
    }
}
