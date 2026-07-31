package com.metallum.client.metal.render;

import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniformFixedInputUniformsHolder;
import net.irisshaders.iris.gl.uniform.FloatSupplier;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import com.mojang.blaze3d.pipeline.BlendFunction;
import org.junit.jupiter.api.Test;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector2i;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalUniformValuesTest {
    @Test
    void usesTheCanonicalIrisSystemTimerAndFrameCounter() {
        SystemTimeUniforms.TIMER.reset();
        SystemTimeUniforms.COUNTER.reset();
        try {
            SystemTimeUniforms.TIMER.beginFrame(1_000_000_000L);
            SystemTimeUniforms.COUNTER.beginFrame();
            SystemTimeUniforms.TIMER.beginFrame(1_050_000_000L);
            SystemTimeUniforms.COUNTER.beginFrame();

            IrisMetalUniformValues.SystemFrameTime time =
                    IrisMetalUniformValues.systemFrameTime();

            assertEquals(0.05f, time.frameTime(), 0.0f);
            assertEquals(0.05f, time.frameTimeCounter(), 0.0f);
            assertEquals(2, time.frameCounter());
            assertEquals(2, new IrisMetalUniformValues(0.0f).frameCounter());
        } finally {
            SystemTimeUniforms.TIMER.reset();
            SystemTimeUniforms.COUNTER.reset();
        }
    }

    @Test
    void distinguishesSodiumShaderKeysFromMojangCoreDraws() {
        for (net.irisshaders.iris.pipeline.programs.ShaderKey key : List.of(
                net.irisshaders.iris.pipeline.programs.ShaderKey.SODIUM_TERRAIN_SOLID,
                net.irisshaders.iris.pipeline.programs.ShaderKey.SODIUM_TERRAIN_CUTOUT,
                net.irisshaders.iris.pipeline.programs.ShaderKey.SODIUM_TERRAIN_TRANSLUCENT,
                net.irisshaders.iris.pipeline.programs.ShaderKey.SHADOW_SODIUM_TERRAIN_SOLID,
                net.irisshaders.iris.pipeline.programs.ShaderKey.SHADOW_SODIUM_TERRAIN_CUTOUT,
                net.irisshaders.iris.pipeline.programs.ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT
        )) {
            assertFalse(
                    IrisMetalUniformValues.usesMojangCoreTransforms(key),
                    () -> key + " is a Sodium draw family, not a Mojang core draw"
            );
        }
        assertTrue(IrisMetalUniformValues.usesMojangCoreTransforms(
                net.irisshaders.iris.pipeline.programs.ShaderKey.TERRAIN_SOLID
        ));
    }

    @Test
    void writesRenderStageFromCurrentWorldRenderingPhase() {
        AtomicReference<WorldRenderingPhase> phase =
                new AtomicReference<>(WorldRenderingPhase.TERRAIN_SOLID);
        IrisMetalUniformValues values =
                new IrisMetalUniformValues(0.0f, () -> phase.get().ordinal());
        ByteBuffer block = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
        MetalIrisShaderCompiler.UniformMember member =
                new MetalIrisShaderCompiler.UniformMember("int", "renderStage", 0, 4, 4);

        assertTrue(values.writeOfficialUniform(block, member));
        assertEquals(WorldRenderingPhase.TERRAIN_SOLID.ordinal(), block.getInt(4));

        phase.set(WorldRenderingPhase.ENTITIES);
        assertTrue(values.writeOfficialUniform(block, member));
        assertEquals(WorldRenderingPhase.ENTITIES.ordinal(), block.getInt(4));
    }

    @Test
    void materializesRenderStageAtDrawTime() {
        ByteBuffer base = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        ByteBuffer output = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        List<MetalIrisShaderCompiler.UniformMember> layout = List.of(
                new MetalIrisShaderCompiler.UniformMember("int", "renderStage", 0, 8, 4)
        );

        IrisMetalUniformValues.materializeDrawUniforms(
                base,
                layout,
                output,
                null,
                null,
                WorldRenderingPhase.BLOCK_ENTITIES.ordinal()
        );

        assertEquals(WorldRenderingPhase.BLOCK_ENTITIES.ordinal(), output.getInt(8));
    }

    @Test
    void terrainStageRefreshPreservesFrameSampledMatricesWithoutCoreBindings() {
        ByteBuffer base = ByteBuffer.allocateDirect(96).order(ByteOrder.nativeOrder());
        ByteBuffer output = ByteBuffer.allocateDirect(96).order(ByteOrder.nativeOrder());
        base.putFloat(16, 3.25f);
        List<MetalIrisShaderCompiler.UniformMember> layout = List.of(
                new MetalIrisShaderCompiler.UniformMember("mat4", "iris_ModelViewMatInverse", 0, 16, 64),
                new MetalIrisShaderCompiler.UniformMember("int", "renderStage", 0, 80, 4)
        );

        IrisMetalUniformValues.materializeDrawUniforms(
                base,
                layout,
                output,
                null,
                null,
                WorldRenderingPhase.TERRAIN_SOLID.ordinal()
        );

        assertEquals(3.25f, output.getFloat(16));
        assertEquals(WorldRenderingPhase.TERRAIN_SOLID.ordinal(), output.getInt(80));
    }

    @Test
    void materializesFixedIrisDynamicDrawUniformCatalog() {
        ByteBuffer base = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        ByteBuffer output = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        List<MetalIrisShaderCompiler.UniformMember> layout = List.of(
                new MetalIrisShaderCompiler.UniformMember("int", "entityId", 0, 0, 4),
                new MetalIrisShaderCompiler.UniformMember("ivec2", "atlasSize", 0, 8, 8),
                new MetalIrisShaderCompiler.UniformMember("int", "gtextureId", 0, 16, 4),
                new MetalIrisShaderCompiler.UniformMember("int", "textureReloadCount", 0, 20, 4),
                new MetalIrisShaderCompiler.UniformMember("ivec2", "gtextureSize", 0, 24, 8),
                new MetalIrisShaderCompiler.UniformMember("ivec4", "blendFunc", 0, 32, 16),
                new MetalIrisShaderCompiler.UniformMember("int", "renderStage", 0, 48, 4)
        );

        IrisMetalUniformValues.materializeDrawUniforms(
                base,
                layout,
                output,
                null,
                null,
                WorldRenderingPhase.ENTITIES.ordinal(),
                73,
                11,
                new IrisMetalUniformValues.DrawUniformContext(
                        null, 2048, 1024, Optional.of(BlendFunction.TRANSLUCENT)
                )
        );

        assertEquals(73, output.getInt(0));
        assertEquals(2048, output.getInt(8));
        assertEquals(1024, output.getInt(12));
        assertEquals(0, output.getInt(16));
        assertEquals(11, output.getInt(20));
        assertEquals(0, output.getInt(24));
        assertEquals(0, output.getInt(28));
        assertEquals(0x0302, output.getInt(32));
        assertEquals(0x0303, output.getInt(36));
        assertEquals(1, output.getInt(40));
        assertEquals(0x0303, output.getInt(44));
        assertEquals(WorldRenderingPhase.ENTITIES.ordinal(), output.getInt(48));
    }

    @Test
    void disabledBlendMatchesIrisZeroVectorContract() {
        assertEquals(
                List.of(0, 0, 0, 0),
                java.util.Arrays.stream(IrisMetalUniformValues.irisBlendFunc(Optional.empty()))
                        .boxed()
                        .toList()
        );
    }

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
                0.0f, customUniforms, new FrameUpdateNotifier(), () -> 0
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
    void writesFixedCommonUniformsFromIrisRegisteredSuppliers() {
        CustomUniformFixedInputUniformsHolder.Builder inputBuilder =
                new CustomUniformFixedInputUniformsHolder.Builder();
        inputBuilder.uniform2i(
                UniformUpdateFrequency.PER_FRAME,
                "eyeBrightness",
                () -> new Vector2i(32, 160)
        );
        inputBuilder.uniform1i(
                UniformUpdateFrequency.PER_FRAME,
                "isEyeInWater",
                () -> 2
        );
        inputBuilder.uniform1f(
                UniformUpdateFrequency.PER_FRAME,
                "shadowFade",
                (FloatSupplier) () -> 0.625f
        );
        CustomUniformFixedInputUniformsHolder inputs = inputBuilder.build();
        inputs.updateAll();
        CustomUniforms customUniforms = new CustomUniforms.Builder().build(inputs);
        IrisMetalUniformValues values = new IrisMetalUniformValues(
                0.0f, customUniforms, inputs, new FrameUpdateNotifier(), () -> 0
        );
        ByteBuffer block = ByteBuffer.allocate(32).order(ByteOrder.nativeOrder());

        assertTrue(values.writeOfficialUniform(
                block,
                new MetalIrisShaderCompiler.UniformMember("ivec2", "eyeBrightness", 0, 0, 8)
        ));
        assertTrue(values.writeOfficialUniform(
                block,
                new MetalIrisShaderCompiler.UniformMember("int", "isEyeInWater", 0, 8, 4)
        ));
        assertTrue(values.writeOfficialUniform(
                block,
                new MetalIrisShaderCompiler.UniformMember("float", "shadowFade", 0, 12, 4)
        ));

        assertEquals(32, block.getInt(0));
        assertEquals(160, block.getInt(4));
        assertEquals(2, block.getInt(8));
        assertEquals(0.625f, block.getFloat(12), 0.0f);
    }

    @Test
    void rejectsExplicitArrayFromIrisEvaluator() {
        CustomUniforms.Builder builder = new CustomUniforms.Builder();
        builder.addVariable("float", "phase", "0.25", true);
        CustomUniforms customUniforms = builder.build();
        customUniforms.update();

        IrisMetalUniformValues values = new IrisMetalUniformValues(
                0.0f, customUniforms, new FrameUpdateNotifier(), () -> 0
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
