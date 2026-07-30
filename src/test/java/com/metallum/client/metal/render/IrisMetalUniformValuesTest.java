package com.metallum.client.metal.render;

import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.junit.jupiter.api.Test;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalUniformValuesTest {
    @Test
    void writesCurrentAlphaTestFromIrisCapturedRenderingState() {
        float previous = CapturedRenderingState.INSTANCE.getCurrentAlphaTest();
        try {
            CapturedRenderingState.INSTANCE.setCurrentAlphaTest(0.375f);
            IrisMetalUniformValues values = new IrisMetalUniformValues(0.0f);
            ByteBuffer block = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
            MetalIrisShaderCompiler.UniformMember member =
                    new MetalIrisShaderCompiler.UniformMember("float", "iris_currentAlphaTest", 0, 4, 4);

            assertTrue(values.writeOfficialUniform(block, member));
            assertEquals(0.375f, block.getFloat(4));
        } finally {
            CapturedRenderingState.INSTANCE.setCurrentAlphaTest(previous);
        }
    }

    @Test
    void writesIrisLightmapTextureMatrixForRawSodiumCoordinates() {
        IrisMetalUniformValues values = new IrisMetalUniformValues(0.0f);
        ByteBuffer block = ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder());
        MetalIrisShaderCompiler.UniformMember member =
                new MetalIrisShaderCompiler.UniformMember("mat4", "iris_LightmapTextureMatrix", 0, 16, 64);

        assertTrue(values.writeOfficialUniform(block, member));

        Matrix4f matrix = new Matrix4f().set(16, block);
        assertEquals(1.0f / 256.0f, matrix.m00(), 0.0f);
        assertEquals(1.0f / 256.0f, matrix.m11(), 0.0f);
        assertEquals(1.0f / 256.0f, matrix.m22(), 0.0f);
        assertEquals(1.0f / 32.0f, matrix.m30(), 0.0f);
        assertEquals(1.0f / 32.0f, matrix.m31(), 0.0f);
        assertEquals(1.0f / 32.0f, matrix.m32(), 0.0f);
        assertEquals(1.0f, matrix.m33(), 0.0f);
    }

    @Test
    void writesPackCustomUniformExpressionUsingIrisEvaluator() {
        CustomUniforms.Builder builder = new CustomUniforms.Builder();
        builder.addVariable("float", "phase", "0.25", false);
        builder.addVariable("vec3", "daytime", "vec3(phase, phase * 2.0, phase * 3.0)", true);
        CustomUniforms customUniforms = builder.build();
        customUniforms.update();

        IrisMetalUniformValues values = new IrisMetalUniformValues(
                0.0f, customUniforms, new FrameUpdateNotifier()
        );
        ByteBuffer block = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
        MetalIrisShaderCompiler.UniformMember member =
                new MetalIrisShaderCompiler.UniformMember("vec3", "daytime", 0, 0, 16);

        assertTrue(values.writeOfficialUniform(block, member));
        assertEquals(0.25f, block.getFloat(0));
        assertEquals(0.5f, block.getFloat(4));
        assertEquals(0.75f, block.getFloat(8));
    }

    @Test
    void rejectsExplicitArrayFromIrisEvaluator() {
        CustomUniforms.Builder builder = new CustomUniforms.Builder();
        builder.addVariable("float", "phase", "0.25", true);
        CustomUniforms customUniforms = builder.build();
        customUniforms.update();

        IrisMetalUniformValues values = new IrisMetalUniformValues(
                0.0f, customUniforms, new FrameUpdateNotifier()
        );
        ByteBuffer block = ByteBuffer.allocate(32).order(ByteOrder.nativeOrder());
        MetalIrisShaderCompiler.UniformMember member =
                new MetalIrisShaderCompiler.UniformMember("float", "phase", 2, 0, 32);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> values.writeOfficialUniform(block, member)
        );
        assertTrue(failure.getMessage().contains("array member 'phase' (count=2)"));
    }

    @Test
    void materializesCoreMatricesFromCurrentMojangUniformBlocks() {
        Matrix4f modelView = new Matrix4f()
                .translate(3.0f, -2.0f, 5.0f)
                .rotateXYZ(0.2f, -0.4f, 0.1f)
                .scale(2.0f, 3.0f, 4.0f);
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70.0), 16.0f / 9.0f, 0.05f, 512.0f);
        ByteBuffer dynamicTransforms = matrixBlock(modelView, 160);
        ByteBuffer projectionBlock = matrixBlock(projection, 64);
        ByteBuffer base = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
        base.putInt(240, 0x12345678);
        ByteBuffer output = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
        List<MetalIrisShaderCompiler.UniformMember> layout = List.of(
                new MetalIrisShaderCompiler.UniformMember("mat4", "iris_ModelViewMatInverse", 0, 16, 64),
                new MetalIrisShaderCompiler.UniformMember("mat4", "iris_ProjMatInverse", 0, 80, 64),
                new MetalIrisShaderCompiler.UniformMember("mat3", "iris_NormalMat", 0, 144, 48)
        );

        IrisMetalUniformValues.materializeCoreDrawUniforms(
                base, layout, output, dynamicTransforms, projectionBlock
        );

        assertMatrix4Equals(new Matrix4f(modelView).invert(), new Matrix4f().set(16, output));
        assertMatrix4Equals(new Matrix4f(projection).invert(), new Matrix4f().set(80, output));
        assertMatrix3Std140Equals(
                new Matrix4f(modelView).invert().transpose3x3(new Matrix3f()), output, 144
        );
        assertEquals(0x12345678, output.getInt(240));
    }

    @Test
    void rejectsMissingPerDrawMojangUniformSource() {
        ByteBuffer base = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        ByteBuffer output = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        List<MetalIrisShaderCompiler.UniformMember> layout = List.of(
                new MetalIrisShaderCompiler.UniformMember("mat4", "iris_ModelViewMatInverse", 0, 0, 64)
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> IrisMetalUniformValues.materializeCoreDrawUniforms(base, layout, output, null, null)
        );
        assertTrue(failure.getMessage().contains("bound DynamicTransforms"));
    }

    private static ByteBuffer matrixBlock(final Matrix4f matrix, final int size) {
        ByteBuffer block = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
        matrix.get(0, block);
        return block;
    }

    private static void assertMatrix4Equals(final Matrix4f expected, final Matrix4f actual) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                assertEquals(expected.get(column, row), actual.get(column, row), 1.0e-5f);
            }
        }
    }

    private static void assertMatrix3Std140Equals(
            final Matrix3f expected,
            final ByteBuffer actual,
            final int offset
    ) {
        for (int column = 0; column < 3; column++) {
            for (int row = 0; row < 3; row++) {
                assertEquals(
                        expected.get(column, row),
                        actual.getFloat(offset + column * 16 + row * Float.BYTES),
                        1.0e-5f
                );
            }
        }
    }
}
