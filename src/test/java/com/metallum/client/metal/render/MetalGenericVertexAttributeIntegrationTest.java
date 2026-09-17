package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.MAC)
final class MetalGenericVertexAttributeIntegrationTest {
    private static final int SIZE = 8;

    private static final String VERTEX_SHADER = """
            #version 450
            layout(location = 0) in vec3 Position;
            layout(location = 3) in vec2 UV0;
            layout(location = 5) in ivec3 iris_Entity;
            layout(location = 0) flat out ivec3 entityValue;
            void main() {
                gl_Position = vec4(Position, 1.0);
                entityValue = iris_Entity;
            }
            """;

    private static final String FRAGMENT_SHADER = """
            #version 450
            layout(location = 0) flat in ivec3 entityValue;
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
        ShaderSource shaders = MetalShaderSourceAdapters.from((identifier, type) -> type == ShaderType.VERTEX
                ? VERTEX_SHADER
                : FRAGMENT_SHADER);
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
    void renderPearlRejectsMissingActiveInputBeforeMetalBackend() {
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

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> device.getOrCompilePipeline(pipeline)
        );
        assertTrue(failure.getMessage().contains("RenderPearl rejected pipeline"));
    }
}
