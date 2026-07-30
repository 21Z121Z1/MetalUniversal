package com.metallum.client.metal.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MetalVertexInputLayoutTest {
    @Test
    void missingAttributeUsesReboundGenericInput() throws Exception {
        RenderPipeline pipeline = pipeline("missing_generic");
        String source = """
                #version 450
                layout(location = 0) in vec3 Position;
                layout(location = 1) in ivec3 iris_Entity;
                void main() {
                    float keepActive = float(iris_Entity.x + iris_Entity.y + iris_Entity.z);
                    gl_Position = vec4(Position + vec3(keepActive * 0.000001), 1.0);
                }
                """;

        try (GlslCompiler compiler = new GlslCompiler();
             IntermediaryShaderModule module = compiler.createIntermediary(
                     "missing_generic", source, ShaderType.VERTEX
             )) {
            MetalCrossShaderCompiler.VertexInputLayout physical =
                    MetalCrossShaderCompiler.vertexInputLayout(pipeline, module.inputs());
            module.rebind(
                    MetalCrossShaderCompiler.tolerateUnprovidedInputs(physical.names(), module.inputs()),
                    List.of()
            );
            MetalCrossShaderCompiler.applyVertexInputLocations(module, physical);

            assertEquals(
                    List.of(new MetalCrossShaderCompiler.GenericVertexInput(
                            2, MetalCrossShaderCompiler.BaseType.INT, 3
                    )),
                    MetalCrossShaderCompiler.genericVertexInputs(module.spirv(), physical.names())
            );
        }
    }

    @Test
    void defaultBufferEncodesGlCurrentValueForEveryBaseType() {
        ByteBuffer values = ByteBuffer.allocate(MetalCrossShaderCompiler.GENERIC_VERTEX_DEFAULT_VALUES_SIZE)
                .order(ByteOrder.nativeOrder());
        MetalCrossShaderCompiler.writeGenericVertexDefaultValues(values);

        int floatOffset = MetalCrossShaderCompiler.BaseType.FLOAT.defaultValueOffset();
        assertEquals(0.0F, values.getFloat(floatOffset));
        assertEquals(0.0F, values.getFloat(floatOffset + 4));
        assertEquals(0.0F, values.getFloat(floatOffset + 8));
        assertEquals(1.0F, values.getFloat(floatOffset + 12));

        for (MetalCrossShaderCompiler.BaseType type : List.of(
                MetalCrossShaderCompiler.BaseType.INT,
                MetalCrossShaderCompiler.BaseType.UINT
        )) {
            int offset = type.defaultValueOffset();
            assertEquals(0, values.getInt(offset));
            assertEquals(0, values.getInt(offset + 4));
            assertEquals(0, values.getInt(offset + 8));
            assertEquals(1, values.getInt(offset + 12));
        }
    }

    @Test
    void genericBufferSlotFailsClosedPastMetalLimit() {
        assertEquals(-1, MetalCompiledRenderPipeline.resolveGenericVertexBufferSlot(30, 1, false));
        assertEquals(30, MetalCompiledRenderPipeline.resolveGenericVertexBufferSlot(29, 1, true));
        assertThrows(
                IllegalStateException.class,
                () -> MetalCompiledRenderPipeline.resolveGenericVertexBufferSlot(30, 1, true)
        );
    }

    private static RenderPipeline pipeline(final String name) {
        Identifier shader = Identifier.fromNamespaceAndPath("metallum", name);
        return RenderPipeline.builder()
                .withLocation(shader)
                .withVertexShader(shader)
                .withFragmentShader(shader)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
                .build();
    }
}
