package com.metallum.client.metal.render;

import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniformFixedInputUniformsHolder;
import net.irisshaders.iris.gl.uniform.FloatSupplier;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import com.mojang.blaze3d.pipeline.BlendFunction;
import net.minecraft.client.renderer.fog.FogData;
import org.junit.jupiter.api.Test;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalUniformValuesTest {
    @Test
    void clampsFogDensityLikeFixedIrisSupplier() {
        assertEquals(0.0f, IrisMetalUniformValues.irisFogDensity(-1.0f), 0.0f);
        assertEquals(0.375f, IrisMetalUniformValues.irisFogDensity(0.375f), 0.0f);
    }

    @Test
    void readsInternalFogColorFromSodiumFogParametersLikeFixedIris() {
        var color = IrisMetalUniformValues.irisFogColor(
                new FogParameters(0.1f, 0.2f, 0.3f, 0.4f, 0.0f, 256.0f, 0.0f, 256.0f)
        );
        assertEquals(0.1f, color.x, 0.0f);
        assertEquals(0.2f, color.y, 0.0f);
        assertEquals(0.3f, color.z, 0.0f);
        assertEquals(0.4f, color.w, 0.0f);
        var none = IrisMetalUniformValues.irisFogColor(FogParameters.NONE);
        assertEquals(1.0f, none.x, 0.0f);
        assertEquals(1.0f, none.y, 0.0f);
        assertEquals(1.0f, none.z, 0.0f);
        assertEquals(1.0f, none.w, 0.0f);
    }

    @Test
    void convertsTheCameraFogRecordWithoutRoundingOrReordering() {
        FogData data = new FogData();
        data.color.set(0.11f, 0.22f, 0.33f, 0.44f);
        data.environmentalStart = 12.0f;
        data.environmentalEnd = 384.0f;
        data.renderDistanceStart = 20.0f;
        data.renderDistanceEnd = 512.0f;

        FogParameters parameters = IrisMetalUniformValues.fogParameters(data);

        assertEquals(0.11f, parameters.red(), 0.0f);
        assertEquals(0.22f, parameters.green(), 0.0f);
        assertEquals(0.33f, parameters.blue(), 0.0f);
        assertEquals(0.44f, parameters.alpha(), 0.0f);
        assertEquals(12.0f, parameters.environmentalStart(), 0.0f);
        assertEquals(384.0f, parameters.environmentalEnd(), 0.0f);
        assertEquals(20.0f, parameters.renderStart(), 0.0f);
        assertEquals(512.0f, parameters.renderEnd(), 0.0f);
    }

    @Test
    void materializesLiveFogSuppliersAtDrawBoundary() {
        ByteBuffer output = ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder());
        List<MetalIrisShaderCompiler.UniformMember> layout = List.of(
                new MetalIrisShaderCompiler.UniformMember("vec3", "fogColor", 0, 0, 12),
                new MetalIrisShaderCompiler.UniformMember("vec4", "iris_FogColor", 0, 16, 16),
                new MetalIrisShaderCompiler.UniformMember("float", "iris_FogDensity", 0, 32, 4),
                new MetalIrisShaderCompiler.UniformMember("float", "iris_FogStart", 0, 36, 4),
                new MetalIrisShaderCompiler.UniformMember("float", "iris_FogEnd", 0, 40, 4)
        );
        FogParameters parameters = new FogParameters(
                0.1f, 0.2f, 0.3f, 0.4f, 12.0f, 384.0f, 20.0f, 512.0f
        );

        IrisMetalUniformValues.writeLiveFogUniforms(
                output,
                layout,
                parameters,
                new org.joml.Vector3d(0.6, 0.5, 0.4),
                -0.25f
        );

        assertEquals(0.6f, output.getFloat(0), 0.0f);
        assertEquals(0.5f, output.getFloat(4), 0.0f);
        assertEquals(0.4f, output.getFloat(8), 0.0f);
        assertEquals(0.1f, output.getFloat(16), 0.0f);
        assertEquals(0.2f, output.getFloat(20), 0.0f);
        assertEquals(0.3f, output.getFloat(24), 0.0f);
        assertEquals(0.4f, output.getFloat(28), 0.0f);
        assertEquals(0.0f, output.getFloat(32), 0.0f);
        assertEquals(12.0f, output.getFloat(36), 0.0f);
        assertEquals(384.0f, output.getFloat(40), 0.0f);
        assertTrue(IrisMetalUniformValues.requiresDrawContext(layout));
    }

    @Test
    void materializesPinnedIrisDynamicSuppliersWithoutNameFallback() {
        CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        float previousDensity = state.getFogDensity();
        float previousAlpha = state.getCurrentAlphaTest();
        try {
            state.setFogDensity(0.375f);
            state.setCurrentAlphaTest(0.625f);

            IrisMetalDynamicUniforms dynamic = IrisMetalDynamicUniforms.create(() -> 7);
            ByteBuffer output = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
            assertTrue(dynamic.write(
                    new MetalIrisShaderCompiler.UniformMember("int", "fogMode", 0, 0, 4),
                    output,
                    IrisMetalUniformValues.DrawUniformContext.empty()
            ));
            assertTrue(dynamic.write(
                    new MetalIrisShaderCompiler.UniformMember("int", "fogShape", 0, 4, 4),
                    output,
                    IrisMetalUniformValues.DrawUniformContext.empty()
            ));
            assertTrue(dynamic.write(
                    new MetalIrisShaderCompiler.UniformMember("float", "fogDensity", 0, 8, 4),
                    output,
                    IrisMetalUniformValues.DrawUniformContext.empty()
            ));
            assertTrue(dynamic.write(
                    new MetalIrisShaderCompiler.UniformMember("float", "alphaTestRef", 0, 32, 4),
                    output,
                    IrisMetalUniformValues.DrawUniformContext.empty()
            ));

            assertEquals(2049, output.getInt(0));
            assertEquals(1, output.getInt(4));
            assertEquals(0.375f, output.getFloat(8), 0.0f);
            assertEquals(0.625f, output.getFloat(32), 0.0f);
        } finally {
            state.setFogDensity(previousDensity);
            state.setCurrentAlphaTest(previousAlpha);
        }
    }

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
    void acceptsIrisUniform3dSuppliersAtTheStd140Vec3Boundary() {
        CustomUniformFixedInputUniformsHolder.Builder inputBuilder =
                new CustomUniformFixedInputUniformsHolder.Builder();
        inputBuilder.uniform3d(
                UniformUpdateFrequency.PER_FRAME,
                "skyColor",
                () -> new Vector3d(0.11, 0.22, 0.33)
        );
        CustomUniformFixedInputUniformsHolder inputs = inputBuilder.build();
        inputs.updateAll();
        CustomUniforms customUniforms = new CustomUniforms.Builder().build(inputs);
        IrisMetalUniformValues values = new IrisMetalUniformValues(
                0.0f, customUniforms, inputs, new FrameUpdateNotifier(), () -> 0
        );
        ByteBuffer block = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());

        assertTrue(values.writeOfficialUniform(
                block,
                new MetalIrisShaderCompiler.UniformMember("vec3", "skyColor", 0, 0, 16)
        ));
        assertEquals(0.11f, block.getFloat(0), 0.0f);
        assertEquals(0.22f, block.getFloat(4), 0.0f);
        assertEquals(0.33f, block.getFloat(8), 0.0f);
    }

    @Test
    void strictProductionUniformBlocksRejectMembersOutsideIrisGraphs() {
        CustomUniformFixedInputUniformsHolder inputs =
                new CustomUniformFixedInputUniformsHolder.Builder().build();
        CustomUniforms customUniforms = new CustomUniforms.Builder().build(inputs);
        IrisMetalDynamicUniforms dynamic = IrisMetalDynamicUniforms.create(() -> 0);
        IrisMetalUniformValues values = new IrisMetalUniformValues(
                0.0f, customUniforms, inputs, dynamic, new FrameUpdateNotifier(), () -> 0
        );
        MetalIrisShaderCompiler.GlslProgram program = new MetalIrisShaderCompiler.GlslProgram(
                "strict-unknown-uniform",
                "", "", "", "",
                List.of(new MetalIrisShaderCompiler.UniformMember("float", "notRegistered", 0, 0, 4)),
                16,
                List.of(),
                List.of(),
                List.of(MetalIrisShaderCompiler.UNIFORM_BLOCK_NAME),
                new int[]{0},
                java.util.OptionalDouble.empty()
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> values.register("strict-unknown", "strict-unknown", program)
        );
        assertTrue(failure.getMessage().contains("absent from the fixed/custom/dynamic supplier graph"));
    }

    @Test
    void strictProductionUniformBlocksAcceptIrisFixedInputSuppliers() {
        CustomUniformFixedInputUniformsHolder.Builder inputBuilder =
                new CustomUniformFixedInputUniformsHolder.Builder();
        inputBuilder.uniform3i(
                UniformUpdateFrequency.PER_TICK,
                "currentTime",
                () -> new Vector3i(2026, 8, 1)
        );
        inputBuilder.uniform2i(
                UniformUpdateFrequency.PER_TICK,
                "currentYearTime",
                () -> new Vector2i(123, 456)
        );
        CustomUniformFixedInputUniformsHolder inputs = inputBuilder.build();
        CustomUniforms customUniforms = new CustomUniforms.Builder().build(inputs);
        IrisMetalUniformValues values = new IrisMetalUniformValues(
                0.0f, customUniforms, inputs, IrisMetalDynamicUniforms.create(() -> 0),
                new FrameUpdateNotifier(), () -> 0
        );
        MetalIrisShaderCompiler.GlslProgram program = new MetalIrisShaderCompiler.GlslProgram(
                "strict-fixed-inputs",
                "", "", "", "",
                List.of(
                        new MetalIrisShaderCompiler.UniformMember("ivec3", "currentTime", 0, 0, 12),
                        new MetalIrisShaderCompiler.UniformMember("ivec2", "currentYearTime", 0, 16, 8)
                ),
                32,
                List.of(),
                List.of(),
                List.of(MetalIrisShaderCompiler.UNIFORM_BLOCK_NAME),
                new int[]{0},
                java.util.OptionalDouble.empty()
        );

        values.register("strict-fixed-inputs", "strict-fixed-inputs", program);
        inputs.updateAll();
        ByteBuffer output = ByteBuffer.allocate(32).order(ByteOrder.nativeOrder());
        assertTrue(values.writeOfficialUniform(
                output,
                new MetalIrisShaderCompiler.UniformMember("ivec3", "currentTime", 0, 0, 12)
        ));
        assertTrue(values.writeOfficialUniform(
                output,
                new MetalIrisShaderCompiler.UniformMember("ivec2", "currentYearTime", 0, 16, 8)
        ));
        assertEquals(2026, output.getInt(0));
        assertEquals(8, output.getInt(4));
        assertEquals(1, output.getInt(8));
        assertEquals(123, output.getInt(16));
        assertEquals(456, output.getInt(20));
    }

    @Test
    void updatesFixedInputsOutsideCustomOrderWithoutDoubleRunningDependencies() {
        AtomicInteger dependencyCalls = new AtomicInteger();
        AtomicInteger independentCalls = new AtomicInteger();
        CustomUniformFixedInputUniformsHolder.Builder inputBuilder =
                new CustomUniformFixedInputUniformsHolder.Builder();
        inputBuilder.uniform1i(
                UniformUpdateFrequency.PER_FRAME,
                "dependency",
                dependencyCalls::incrementAndGet
        );
        inputBuilder.uniform1i(
                UniformUpdateFrequency.PER_FRAME,
                "independent",
                independentCalls::incrementAndGet
        );
        CustomUniformFixedInputUniformsHolder inputs = inputBuilder.build();
        CustomUniforms.Builder customBuilder = new CustomUniforms.Builder();
        customBuilder.addVariable("int", "derived", "dependency + 1", true);
        CustomUniforms customUniforms = customBuilder.build(inputs);

        customUniforms.update();
        IrisMetalUniformValues.updateUnvisitedFixedInputs(customUniforms, inputs);

        assertEquals(1, dependencyCalls.get(), "CustomUniforms dependency must not be updated twice");
        assertEquals(1, independentCalls.get(), "unvisited fixed input must be refreshed once");
    }

    @Test
    void registeredProgramPlanOwnsFixedUniformLifecyclePerProgram() {
        SystemTimeUniforms.COUNTER.reset();
        AtomicInteger gameTime = new AtomicInteger(1);
        AtomicInteger graphCalls = new AtomicInteger();
        AtomicInteger onceCalls = new AtomicInteger();
        AtomicInteger tickCalls = new AtomicInteger();
        AtomicInteger frameCalls = new AtomicInteger();
        AtomicInteger onceValue = new AtomicInteger(11);
        AtomicInteger tickValue = new AtomicInteger(21);
        AtomicInteger frameValue = new AtomicInteger(31);

        CustomUniformFixedInputUniformsHolder.Builder graphBuilder =
                new CustomUniformFixedInputUniformsHolder.Builder();
        graphBuilder.uniform1i(
                UniformUpdateFrequency.PER_FRAME,
                "onceValue",
                () -> {
                    graphCalls.incrementAndGet();
                    return 900;
                }
        );
        CustomUniforms customUniforms = new CustomUniforms.Builder().build(graphBuilder.build());

        CustomUniformFixedInputUniformsHolder.Builder programBuilder =
                new CustomUniformFixedInputUniformsHolder.Builder();
        programBuilder.uniform1i(
                UniformUpdateFrequency.ONCE,
                "onceValue",
                () -> {
                    onceCalls.incrementAndGet();
                    return onceValue.get();
                }
        );
        programBuilder.uniform1i(
                UniformUpdateFrequency.PER_TICK,
                "tickValue",
                () -> {
                    tickCalls.incrementAndGet();
                    return tickValue.get();
                }
        );
        programBuilder.uniform1i(
                UniformUpdateFrequency.PER_FRAME,
                "frameValue",
                () -> {
                    frameCalls.incrementAndGet();
                    return frameValue.get();
                }
        );
        CustomUniformFixedInputUniformsHolder programInputs = programBuilder.build();
        IrisMetalUniformValues values = new IrisMetalUniformValues(
                0.0f,
                customUniforms,
                programInputs,
                new FrameUpdateNotifier(),
                () -> 0,
                () -> gameTime.get()
        );

        List<MetalIrisShaderCompiler.UniformMember> layout = List.of(
                new MetalIrisShaderCompiler.UniformMember("int", "onceValue", 0, 0, 4),
                new MetalIrisShaderCompiler.UniformMember("int", "tickValue", 0, 4, 4),
                new MetalIrisShaderCompiler.UniformMember("int", "frameValue", 0, 8, 4)
        );
        MetalIrisShaderCompiler.GlslProgram firstProgram = new MetalIrisShaderCompiler.GlslProgram(
                "registered-program-one",
                "", "", "", "",
                layout,
                16,
                List.of(),
                List.of(),
                List.of(MetalIrisShaderCompiler.UNIFORM_BLOCK_NAME),
                new int[]{0},
                java.util.OptionalDouble.empty()
        );
        MetalIrisShaderCompiler.GlslProgram secondProgram = new MetalIrisShaderCompiler.GlslProgram(
                "registered-program-two",
                "", "", "", "",
                layout,
                16,
                List.of(),
                List.of(),
                List.of(MetalIrisShaderCompiler.UNIFORM_BLOCK_NAME),
                new int[]{0},
                java.util.OptionalDouble.empty()
        );
        Object firstToken = new Object();
        Object secondToken = new Object();
        values.register(firstToken, "registered-program-one", firstProgram);
        values.register(secondToken, "registered-program-two", secondProgram);
        ByteBuffer output = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());

        try {
            SystemTimeUniforms.COUNTER.beginFrame();
            values.beginProgramForTests(firstToken, IrisMetalUniformValues.DrawUniformContext.empty());
            assertEquals(1, onceCalls.get());
            assertEquals(1, tickCalls.get());
            assertEquals(1, frameCalls.get());
            assertEquals(0, graphCalls.get(), "program reads must not use the CustomUniforms graph cache");
            assertTrue(values.writeOfficialUniform(output, layout.get(0)));
            assertEquals(11, output.getInt(0));
            assertTrue(values.writeOfficialUniform(output, layout.get(1)));
            assertEquals(21, output.getInt(4));
            assertTrue(values.writeOfficialUniform(output, layout.get(2)));
            assertEquals(31, output.getInt(8));

            values.beginProgramForTests(firstToken, IrisMetalUniformValues.DrawUniformContext.empty());
            assertEquals(1, onceCalls.get(), "ONCE must not rerun for the same registered program");
            assertEquals(1, tickCalls.get(), "PER_TICK must not rerun within one tick");
            assertEquals(1, frameCalls.get(), "PER_FRAME must not rerun within one frame");
            assertEquals(2, values.programUpdateCount(firstToken));

            onceValue.set(12);
            tickValue.set(22);
            frameValue.set(32);
            gameTime.set(2);
            SystemTimeUniforms.COUNTER.beginFrame();
            values.beginProgramForTests(firstToken, IrisMetalUniformValues.DrawUniformContext.empty());
            assertEquals(1, onceCalls.get(), "ONCE must remain committed for the program");
            assertEquals(2, tickCalls.get());
            assertEquals(2, frameCalls.get());
            assertTrue(values.writeOfficialUniform(output, layout.get(0)));
            assertEquals(11, output.getInt(0));
            assertTrue(values.writeOfficialUniform(output, layout.get(1)));
            assertEquals(22, output.getInt(4));
            assertTrue(values.writeOfficialUniform(output, layout.get(2)));
            assertEquals(32, output.getInt(8));

            values.beginProgramForTests(secondToken, IrisMetalUniformValues.DrawUniformContext.empty());
            assertEquals(2, onceCalls.get(), "a second registered program has its own ONCE phase");
            assertEquals(3, tickCalls.get(), "a second registered program has its own tick boundary");
            assertEquals(3, frameCalls.get(), "a second registered program has its own frame boundary");
            assertEquals(1, values.programUpdateCount(secondToken));
        } finally {
            values.close();
            SystemTimeUniforms.COUNTER.reset();
        }
    }

    @Test
    void dynamicSnapshotEvaluatesOnceAndDetachesNotifierAcrossProgramUses() {
        AtomicInteger supplierCalls = new AtomicInteger();
        AtomicInteger listenerClears = new AtomicInteger();
        AtomicReference<Runnable> listener = new AtomicReference<>();
        net.irisshaders.iris.gl.state.ValueUpdateNotifier notifier = runnable -> {
            if (runnable == null) {
                listenerClears.incrementAndGet();
            }
            listener.set(runnable);
        };
        IrisMetalDynamicUniforms dynamic = IrisMetalDynamicUniforms.create(() -> 0);
        dynamic.uniform1i("testDynamic", supplierCalls::incrementAndGet, notifier);
        MetalIrisShaderCompiler.UniformMember member =
                new MetalIrisShaderCompiler.UniformMember("int", "testDynamic", 0, 0, 4);
        IrisMetalUniformValues.DrawUniformContext context = IrisMetalUniformValues.DrawUniformContext.empty();
        ByteBuffer output = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());

        dynamic.beginProgram(List.of(member));
        assertEquals(UniformUpdateFrequency.CUSTOM, dynamic.frequency("testDynamic"));
        assertNotNull(listener.get());
        IrisMetalDynamicUniforms.DrawSnapshot first = dynamic.snapshot(List.of(member), context);
        assertSame(first, dynamic.snapshot(List.of(member), context), "one program-use commit has one dynamic snapshot");
        assertTrue(dynamic.write(member, output, context, first));
        assertTrue(dynamic.write(member, output, context, first));
        assertEquals(1, supplierCalls.get(), "trace/write must reuse one committed dynamic value");
        listener.get().run();
        IrisMetalDynamicUniforms.DrawSnapshot invalidated = dynamic.snapshot(List.of(member), context);
        assertTrue(dynamic.write(member, output, context, invalidated));
        assertEquals(2, supplierCalls.get(), "a notifier must invalidate the committed dynamic value");
        Runnable firstListener = listener.get();

        dynamic.beginProgram(List.of(member));
        assertEquals(1, listenerClears.get(), "switching programs must remove the old notifier listener");
        assertNotNull(listener.get());
        assertNotSame(firstListener, listener.get(), "each program use gets a fresh listener closure");
        IrisMetalDynamicUniforms.DrawSnapshot second = dynamic.snapshot(List.of(member), context);
        assertThrows(
                IllegalStateException.class,
                () -> dynamic.write(member, output, context, first),
                "a snapshot from an earlier Program.use must not cross the commit boundary"
        );
        assertTrue(dynamic.write(member, output, context, second));
        assertEquals(3, supplierCalls.get(), "the next program use must update the dynamic supplier once");

        dynamic.beginProgram(List.of());
        assertNull(listener.get(), "a program without the member must detach its notifier");
        assertEquals(2, listenerClears.get());
        dynamic.close();
        assertNull(listener.get());
    }

    @Test
    void lowersEveryIrisMatrixUniformProjectionAlias() {
        Matrix4f zeroToOne = new Matrix4f().setPerspective(
                (float) Math.toRadians(70.0),
                16.0f / 9.0f,
                0.05f,
                512.0f,
                true
        );
        Matrix4f expected = MetalIrisDepthConvention.zeroToOneToOpenGl(zeroToOne);
        Matrix4f expectedInverse = new Matrix4f(expected).invert();

        for (String name : List.of(
                "gbufferProjection",
                "gbufferPreviousProjection",
                "dhProjection",
                "dhPreviousProjection",
                "iris_ProjectionMatrix"
        )) {
            assertMatrix4Equals(
                    expected,
                    new Matrix4f(IrisMetalUniformValues.packProjectionUniform(name, zeroToOne, true))
            );
        }
        for (String name : List.of(
                "gbufferProjectionInverse",
                "dhProjectionInverse",
                "iris_ProjectionMatrixInverse"
        )) {
            assertMatrix4Equals(
                    expectedInverse,
                    new Matrix4f(IrisMetalUniformValues.packProjectionUniform(name, new Matrix4f(zeroToOne).invert(), true))
            );
        }
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
