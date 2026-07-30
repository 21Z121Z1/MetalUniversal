package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.SpvVariable;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalVertexInputLayoutTest {
    @Test
    void irisAliasesKeepEntityAttributesInPhysicalOrderAndFormat() {
        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("metallum", "iris_entity_vertex_layout_test"))
                .withVertexShader(Identifier.fromNamespaceAndPath("metallum", "iris_entity_vertex_layout_test"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("metallum", "iris_entity_vertex_layout_test"))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withVertexBinding(0, IrisVertexFormats.ENTITY)
                .build();

        List<SpvVariable> reflectedInputs = List.of(
                new SpvVariable("iris_Entity", 0),
                new SpvVariable("iris_UV1", 0),
                new SpvVariable("iris_Color", 0),
                new SpvVariable("iris_UV0", 0),
                new SpvVariable("iris_UV2", 0),
                new SpvVariable("iris_Position", 0),
                new SpvVariable("iris_Normal", 0),
                new SpvVariable("mc_midTexCoord", 0),
                new SpvVariable("at_tangent", 0)
        );

        MetalCrossShaderCompiler.VertexInputLayout layout =
                MetalCrossShaderCompiler.vertexInputLayout(pipeline, reflectedInputs);

        assertEquals(
                List.of(
                        "iris_Position", "iris_Color", "iris_UV0", "iris_UV1", "iris_UV2",
                        "iris_Normal", "iris_Entity", "mc_midTexCoord", "at_tangent"
                ),
                layout.names()
        );
        assertEquals(GpuFormat.RGB32_FLOAT, layout.formats().get("iris_Position"));
        assertEquals(GpuFormat.RG16_SINT, layout.formats().get("iris_UV1"));
        assertEquals(GpuFormat.RGBA16_UINT, layout.formats().get("iris_Entity"));
        assertEquals(GpuFormat.RG32_FLOAT, layout.formats().get("mc_midTexCoord"));
        assertEquals(GpuFormat.RGBA8_SNORM, layout.formats().get("at_tangent"));
    }

    @Test
    void missingIrisEntityUsesReboundInt3GenericInput() throws Exception {
        RenderPipeline pipeline = pipeline("missing_iris_entity", DefaultVertexFormat.POSITION_TEX);
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
                     "missing_iris_entity", source, ShaderType.VERTEX
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
    void physicallyBackedIrisEntityIsNeverGeneric() throws Exception {
        RenderPipeline pipeline = pipeline("backed_iris_entity", IrisVertexFormats.ENTITY);
        String source = """
                #version 450
                layout(location = 0) in vec3 iris_Position;
                layout(location = 1) in ivec3 iris_Entity;
                void main() {
                    gl_Position = vec4(iris_Position + vec3(iris_Entity) * 0.000001, 1.0);
                }
                """;

        try (GlslCompiler compiler = new GlslCompiler();
             IntermediaryShaderModule module = compiler.createIntermediary(
                     "backed_iris_entity", source, ShaderType.VERTEX
             )) {
            MetalCrossShaderCompiler.VertexInputLayout physical =
                    MetalCrossShaderCompiler.vertexInputLayout(pipeline, module.inputs());
            module.rebind(
                    MetalCrossShaderCompiler.tolerateUnprovidedInputs(physical.names(), module.inputs()),
                    List.of()
            );
            MetalCrossShaderCompiler.applyVertexInputLocations(module, physical);

            assertTrue(MetalCrossShaderCompiler.genericVertexInputs(module.spirv(), physical.names()).isEmpty());
        }
    }

    @Test
    void genericFormatsCoverFloatIntAndUintVectors() {
        MetalCrossShaderCompiler.BaseType[] baseTypes = MetalCrossShaderCompiler.BaseType.values();
        com.metallum.client.metal.render.mtl.MTLVertexFormat[][] expected = {
                {
                        com.metallum.client.metal.render.mtl.MTLVertexFormat.Float,
                        com.metallum.client.metal.render.mtl.MTLVertexFormat.Float2,
                        com.metallum.client.metal.render.mtl.MTLVertexFormat.Float3,
                        com.metallum.client.metal.render.mtl.MTLVertexFormat.Float4
                },
                {
                        com.metallum.client.metal.render.mtl.MTLVertexFormat.Int,
                        com.metallum.client.metal.render.mtl.MTLVertexFormat.Int2,
                        com.metallum.client.metal.render.mtl.MTLVertexFormat.Int3,
                        com.metallum.client.metal.render.mtl.MTLVertexFormat.Int4
                },
                {
                        com.metallum.client.metal.render.mtl.MTLVertexFormat.UInt,
                        com.metallum.client.metal.render.mtl.MTLVertexFormat.UInt2,
                        com.metallum.client.metal.render.mtl.MTLVertexFormat.UInt3,
                        com.metallum.client.metal.render.mtl.MTLVertexFormat.UInt4
                }
        };

        for (int type = 0; type < baseTypes.length; type++) {
            for (int components = 1; components <= 4; components++) {
                MetalCrossShaderCompiler.GenericVertexInput input =
                        new MetalCrossShaderCompiler.GenericVertexInput(0, baseTypes[type], components);
                assertEquals(expected[type][components - 1], input.metalFormat());
            }
        }
    }

    @Test
    void genericDefaultBufferEncodesGlCurrentValueForEveryBaseType() {
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

    private static RenderPipeline pipeline(final String name, final com.mojang.blaze3d.vertex.VertexFormat format) {
        Identifier shader = Identifier.fromNamespaceAndPath("metallum", name);
        return RenderPipeline.builder()
                .withLocation(shader)
                .withVertexShader(shader)
                .withFragmentShader(shader)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withVertexBinding(0, format)
                .build();
    }
}
