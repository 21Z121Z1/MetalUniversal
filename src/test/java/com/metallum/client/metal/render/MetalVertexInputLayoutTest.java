package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** RenderPearl 26.3 SPIR-V reflection and Metal generic-input contracts. */
final class MetalVertexInputLayoutTest {
    @Test
    void declaredVertexFormatNamesRemainThePhysicalAttributeMap() {
        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("metallum", "iris_entity_vertex_layout_test"))
                .withVertexShader(Identifier.fromNamespaceAndPath("metallum", "iris_entity_vertex_layout_test"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("metallum", "iris_entity_vertex_layout_test"))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withVertexBinding(0, IrisVertexFormats.ENTITY)
                .build();

        Map<String, GpuFormat> formats = MetalCrossShaderCompiler.vertexAttributeFormats(pipeline);
        assertEquals(GpuFormat.RGB32_FLOAT, formats.get("Position"));
        assertEquals(GpuFormat.RG16_SINT, formats.get("UV1"));
        assertEquals(GpuFormat.RGBA8_SNORM, formats.get("Normal"));
        assertEquals(9, formats.size());
    }

    @Test
    void unboundSpirvInputsBecomeSortedGenericMetalInputs() throws Exception {
        SpvModule.Reflection reflection = reflection(
                input("Position", 0, 13, 3),
                input("iris_Entity", 5, 7, 3),
                input("mc_midTexCoord", 2, 8, 2)
        );
        List<BackendRenderPipeline.CreateInfo.AttribBinding> physical = List.of(
                new BackendRenderPipeline.CreateInfo.AttribBinding(0, 0, 0, GpuFormat.RGB32_FLOAT)
        );

        assertEquals(
                List.of(
                        new MetalCrossShaderCompiler.GenericVertexInput(
                                2, MetalCrossShaderCompiler.BaseType.UINT, 2
                        ),
                        new MetalCrossShaderCompiler.GenericVertexInput(
                                5, MetalCrossShaderCompiler.BaseType.INT, 3
                        )
                ),
                MetalCrossShaderCompiler.genericVertexInputs(reflection, physical)
        );
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

    private static RenderPipeline pipeline(final String name, final VertexFormat format) {
        Identifier shader = Identifier.fromNamespaceAndPath("metallum", name);
        return RenderPipeline.builder()
                .withLocation(shader)
                .withVertexShader(shader)
                .withFragmentShader(shader)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withVertexBinding(0, format)
                .build();
    }

    private static SpvModule.Reflection reflection(final SpvModule.Reflection.InterfaceVariable... inputs) {
        return new SpvModule.Reflection() {
            @Override
            public List<SpvModule.Reflection.InterfaceVariable> inputs() {
                return List.of(inputs);
            }

            @Override
            public List<SpvModule.Reflection.InterfaceVariable> outputs() {
                return List.of();
            }

            @Override
            public List<SpvModule.Reflection.Descriptor> descriptors(final int resourceType) {
                return List.of();
            }

            @Override
            public List<SpvModule.Reflection.Descriptor> descriptors() {
                return List.of();
            }

            @Override
            public List<SpvModule.Reflection.PushConstant> pushConstants() {
                return List.of();
            }
        };
    }

    private static SpvModule.Reflection.InterfaceVariable input(
            final String name, final int location, final int baseType, final int vectorSize
    ) {
        return new SpvModule.Reflection.InterfaceVariable() {
            private int currentLocation = location;

            @Override
            public String name() {
                return name;
            }

            @Override
            public SpvModule.Reflection.Type type() {
                return new TestType(baseType, vectorSize);
            }

            @Override
            public int location() {
                return currentLocation;
            }

            @Override
            public void location(final int value) {
                currentLocation = value;
            }

            @Override
            public int decoration(final int decoration) {
                return 0;
            }
        };
    }

    private record TestType(int baseType, int vectorSize) implements SpvModule.Reflection.Type {
        @Override
        public int dimensions() {
            return 1;
        }

        @Override
        public int arrayDimensions() {
            return 0;
        }

        @Override
        public int arrayLength(final int dimensionIndex) {
            throw new IndexOutOfBoundsException(dimensionIndex);
        }
    }
}