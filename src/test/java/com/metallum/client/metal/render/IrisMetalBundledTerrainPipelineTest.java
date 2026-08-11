package com.metallum.client.metal.render;

import com.metallum.client.metal.render.IrisMetalPipelineOverrides.TerrainKind;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.pathways.colorspace.ColorSpace;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.vertices.sodium.terrain.FormatAnalyzer;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic, redistributable GPU gate for the Iris -> Sodium terrain ->
 * Metal PSO path. Third-party shader packs remain a separate compatibility
 * audit; this fixture lives in src/test/resources and therefore cannot vanish
 * or change independently of the code under test.
 */
@EnabledOnOs(OS.MAC)
final class IrisMetalBundledTerrainPipelineTest {
    @Test
    void bundledDimensionFixtureCompilesTerrainOverridesToDevicePipelines() throws Exception {
        Iris.testing = true;
        IrisMetalPipelineOverrides.setExtendedTerrainTargets(true);
        WorldRenderingSettings.INSTANCE.setVertexFormat(FormatAnalyzer.createFormat(true, true, true, true));

        MemorySegment nativeDevice = MetalNativeBridge.metallum_create_system_default_device();
        assertFalse(MetalNativeBridge.isNullHandle(nativeDevice), "MTLCreateSystemDefaultDevice returned null");
        ShaderSource fallback = (identifier, type) -> null;
        MetalDevice device = new MetalDevice(
                fallback,
                new GpuDebugOptions(2, true, true, true),
                nativeDevice,
                MemorySegment.NULL,
                "Bundled Iris terrain conformance device",
                MemorySegment.NULL
        );

        IrisMetalPipelineOverrides.Instance instance = null;
        try {
            ShaderPack pack = new ShaderPack(fixturePath(), StandardMacros.createStandardEnvironmentDefines(), false);
            ProgramSet set = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
            IrisMetalPackAdmission.requireSupported(set, ColorSpace.SRGB);
            instance = IrisMetalPipelineOverrides.activateForTests(
                    set,
                    set.getPackDirectives().getTextureMap()
            );

            int compiledCount = 0;
            for (TerrainKind kind : TerrainKind.values()) {
                var program = instance.program(kind);
                if (program == null) {
                    continue;
                }
                RenderPipeline source = fakeSodiumPipeline(kind);
                assertEquals(kind, IrisMetalPipelineOverrides.Instance.discriminate(source));
                RenderPipeline selected = IrisMetalPipelineOverrides.pipelineForTerrain(source);
                assertNotSame(source, selected, kind + " did not select the Iris synthetic terrain pipeline");
                assertEquals(
                        program.drawBuffers().length,
                        selected.getColorTargetStates().length,
                        kind + " synthetic target count differs from DRAWBUFFERS"
                );
                MetalCompiledRenderPipeline compiled = device.getOrCompilePipeline(selected);
                assertNotNull(compiled);
                assertTrue(compiled.isValid(), kind + " Metal PSO is invalid");
                compiledCount++;
            }
            assertTrue(compiledCount > 0, "bundled fixture produced no compiled terrain Metal PSO");
        } finally {
            if (instance != null) {
                IrisMetalPipelineOverrides.deactivate(instance);
            }
            IrisMetalPipelineOverrides.setExtendedTerrainTargets(false);
            WorldRenderingSettings.INSTANCE.setVertexFormat(null);
            MetalFxManager.close();
            device.close();
        }
    }

    private static Path fixturePath() throws URISyntaxException {
        var resource = IrisMetalBundledTerrainPipelineTest.class
                .getResource("/iris-conformance-dimensions/shaders");
        assertNotNull(resource, "missing bundled Iris dimension conformance fixture");
        return Path.of(resource.toURI());
    }

    /** Mirrors the Sodium descriptor properties used by Iris's runtime discriminator. */
    private static RenderPipeline fakeSodiumPipeline(final TerrainKind kind) {
        BindGroupLayout sodiumLayout = BindGroupLayout.builder()
                .withSampler("u_LightTex")
                .withSampler("u_BlockTex")
                .withUniform("u_Globals", UniformType.UNIFORM_BUFFER)
                .withUniform("u_SectionTimeInfo", UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT)
                .build();
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(
                        "sodium", "bundled_conformance_" + kind.name().toLowerCase(Locale.ROOT)))
                .withVertexShader(Identifier.fromNamespaceAndPath("sodium", "bundled_conformance_v"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("sodium", "bundled_conformance_f"))
                .withCull(true)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withBindGroupLayout(sodiumLayout)
                .withVertexBinding(0, DefaultVertexFormat.BLOCK);
        if (kind == TerrainKind.TRANSLUCENT) {
            builder.withColorTargetState(0, new ColorTargetState(
                    Optional.of(BlendFunction.TRANSLUCENT),
                    GpuFormat.RGBA8_UNORM,
                    ColorTargetState.WRITE_ALL
            ));
        } else {
            builder.withColorTargetState(0, new ColorTargetState(
                    Optional.empty(),
                    GpuFormat.RGBA8_UNORM,
                    ColorTargetState.WRITE_ALL
            ));
        }
        if (kind == TerrainKind.CUTOUT) {
            builder.withShaderDefine("CUTOUT");
        }
        return builder.build();
    }
}
