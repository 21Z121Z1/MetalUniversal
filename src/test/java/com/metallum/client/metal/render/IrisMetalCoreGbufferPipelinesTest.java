package com.metallum.client.metal.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeFunction;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.pipeline.IrisPipelines;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalCoreGbufferPipelinesTest {
    private static final IrisMetalCoreGbufferPipelines.RenderState MAIN =
            new IrisMetalCoreGbufferPipelines.RenderState(false, false, false, false);
    private static final IrisMetalCoreGbufferPipelines.RenderState BLOCK_ENTITY =
            new IrisMetalCoreGbufferPipelines.RenderState(false, false, false, true);
    private static final IrisMetalCoreGbufferPipelines.RenderState HAND_SOLID =
            new IrisMetalCoreGbufferPipelines.RenderState(false, true, true, false);
    private static final IrisMetalCoreGbufferPipelines.RenderState HAND_TRANSLUCENT =
            new IrisMetalCoreGbufferPipelines.RenderState(false, true, false, false);
    private static final IrisMetalCoreGbufferPipelines.RenderState SHADOW =
            new IrisMetalCoreGbufferPipelines.RenderState(true, true, true, true);

    private static final Set<RenderPipeline> DYNAMIC_MAIN = Set.of(
            RenderPipelines.ENTITY_CUTOUT,
            RenderPipelines.ENTITY_CUTOUT_CULL,
            RenderPipelines.ENTITY_CUTOUT_DISSOLVE,
            RenderPipelines.ENTITY_TRANSLUCENT_CULL,
            RenderPipelines.ITEM_TRANSLUCENT,
            RenderPipelines.ITEM_CUTOUT,
            RenderPipelines.ENTITY_TRANSLUCENT,
            RenderPipelines.ENTITY_SHADOW,
            RenderPipelines.ARMOR_CUTOUT_NO_CULL,
            RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL,
            RenderPipelines.ARMOR_TRANSLUCENT,
            RenderPipelines.BREEZE_WIND,
            RenderPipelines.ENTITY_SOLID,
            RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD,
            RenderPipelines.TEXT,
            RenderPipelines.TEXT_POLYGON_OFFSET,
            RenderPipelines.TEXT_SEE_THROUGH,
            RenderPipelines.TEXT_GRAYSCALE_SEE_THROUGH,
            RenderPipelines.TEXT_GRAYSCALE,
            RenderPipelines.BANNER_PATTERN
    );

    @Test
    void staticMappingsMatchThePinnedIris112Oracle() throws ReflectiveOperationException {
        Map<RenderPipeline, ?> oracleMain = oracleMap("coreShaderMap");
        assertEquals(oracleMain.size(), IrisMetalCoreGbufferPipelines.mappedPipelineCount(false));
        for (Map.Entry<RenderPipeline, ?> entry : oracleMain.entrySet()) {
            if (DYNAMIC_MAIN.contains(entry.getKey())) {
                continue;
            }
            assertSame(
                    applyOracle(entry.getValue()),
                    IrisMetalCoreGbufferPipelines.resolve(entry.getKey(), MAIN),
                    () -> "main mapping differs for " + entry.getKey().getLocation()
            );
        }

        Map<RenderPipeline, ?> oracleShadow = oracleMap("coreShaderMapShadow");
        assertEquals(oracleShadow.size(), IrisMetalCoreGbufferPipelines.mappedPipelineCount(true));
        for (Map.Entry<RenderPipeline, ?> entry : oracleShadow.entrySet()) {
            assertSame(
                    applyOracle(entry.getValue()),
                    IrisMetalCoreGbufferPipelines.resolve(entry.getKey(), SHADOW),
                    () -> "shadow mapping differs for " + entry.getKey().getLocation()
            );
        }
    }

    @Test
    void dynamicResolversMatchHandAndBlockEntitySemantics() {
        assertFamily(
                Set.of(
                        RenderPipelines.ENTITY_CUTOUT,
                        RenderPipelines.ENTITY_CUTOUT_CULL,
                        RenderPipelines.ENTITY_CUTOUT_DISSOLVE,
                        RenderPipelines.ITEM_CUTOUT,
                        RenderPipelines.ARMOR_CUTOUT_NO_CULL,
                        RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL
                ),
                ShaderKey.ENTITIES_CUTOUT_DIFFUSE,
                ShaderKey.BLOCK_ENTITY_DIFFUSE,
                ShaderKey.HAND_CUTOUT_DIFFUSE,
                ShaderKey.HAND_WATER_DIFFUSE
        );
        assertFamily(
                Set.of(RenderPipelines.ENTITY_SOLID, RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD),
                ShaderKey.ENTITIES_SOLID,
                ShaderKey.BLOCK_ENTITY,
                ShaderKey.HAND_CUTOUT,
                ShaderKey.HAND_TRANSLUCENT
        );
        assertFamily(
                Set.of(
                        RenderPipelines.ENTITY_TRANSLUCENT_CULL,
                        RenderPipelines.ITEM_TRANSLUCENT,
                        RenderPipelines.ENTITY_TRANSLUCENT,
                        RenderPipelines.ENTITY_SHADOW,
                        RenderPipelines.ARMOR_TRANSLUCENT,
                        RenderPipelines.BREEZE_WIND,
                        RenderPipelines.BANNER_PATTERN
                ),
                ShaderKey.ENTITIES_TRANSLUCENT,
                ShaderKey.BE_TRANSLUCENT,
                ShaderKey.HAND_CUTOUT_DIFFUSE,
                ShaderKey.HAND_WATER_DIFFUSE
        );
        assertFamily(
                Set.of(RenderPipelines.TEXT, RenderPipelines.TEXT_POLYGON_OFFSET, RenderPipelines.TEXT_SEE_THROUGH),
                ShaderKey.TEXT,
                ShaderKey.TEXT_BE,
                ShaderKey.HAND_TEXT,
                ShaderKey.HAND_TEXT_TRANSLUCENT
        );

        for (RenderPipeline pipeline : Set.of(RenderPipelines.TEXT_GRAYSCALE, RenderPipelines.TEXT_GRAYSCALE_SEE_THROUGH)) {
            assertSame(ShaderKey.TEXT_INTENSITY, IrisMetalCoreGbufferPipelines.resolve(pipeline, MAIN));
            assertSame(ShaderKey.TEXT_INTENSITY_BE, IrisMetalCoreGbufferPipelines.resolve(pipeline, BLOCK_ENTITY));
            assertSame(ShaderKey.TEXT_INTENSITY, IrisMetalCoreGbufferPipelines.resolve(pipeline, HAND_SOLID));
        }
    }

    @Test
    void coreSyntheticVertexFormatsPreservePreparedBufferAbi() {
        assertSame(DefaultVertexFormat.ENTITY, RenderPipelines.ENTITY_CUTOUT.getVertexFormatBinding(0));
        assertEquals(36, DefaultVertexFormat.ENTITY.getVertexSize());
        VertexFormat entity = IrisMetalCoreGbufferPipelines.physicalVertexFormat(
                RenderPipelines.ENTITY_CUTOUT, ShaderKey.ENTITIES_CUTOUT_DIFFUSE
        );
        assertSame(DefaultVertexFormat.ENTITY, entity);
        assertEquals(36, entity.getVertexSize());
        assertEquals(
                List.of("Position", "Color", "UV0", "UV1", "UV2", "Normal"),
                entity.getElements().stream().map(element -> element.name()).toList()
        );
        assertEquals(
                List.of(0, 12, 16, 24, 28, 32),
                entity.getElements().stream().map(element -> element.offset()).toList()
        );
        assertEquals(
                List.of(
                        GpuFormat.RGB32_FLOAT, GpuFormat.RGBA8_UNORM, GpuFormat.RG32_FLOAT,
                        GpuFormat.RG16_SINT, GpuFormat.RG16_SINT, GpuFormat.RGBA8_SNORM
                ),
                entity.getElements().stream().map(element -> element.format()).toList()
        );

        assertSame(
                RenderPipelines.BEACON_BEAM_OPAQUE.getVertexFormatBinding(0),
                IrisMetalCoreGbufferPipelines.physicalVertexFormat(
                        RenderPipelines.BEACON_BEAM_OPAQUE, ShaderKey.BEACON
                )
        );
        assertSame(
                RenderPipelines.TEXT_SEE_THROUGH.getVertexFormatBinding(0),
                IrisMetalCoreGbufferPipelines.physicalVertexFormat(
                        RenderPipelines.TEXT_SEE_THROUGH, ShaderKey.TEXT
                )
        );
        assertSame(
                RenderPipelines.END_PORTAL.getVertexFormatBinding(0),
                IrisMetalCoreGbufferPipelines.physicalVertexFormat(
                        RenderPipelines.END_PORTAL, ShaderKey.BLOCK_ENTITY
                )
        );
        assertNull(RenderPipelines.CLOUDS.getVertexFormatBinding(0));
        assertNull(
                IrisMetalCoreGbufferPipelines.physicalVertexFormat(
                        RenderPipelines.CLOUDS, ShaderKey.CLOUDS
                ),
                "procedural Mojang draws must not gain an unbound physical vertex stream"
        );
    }

    @Test
    void shadowAndIdentityAreNeverInferredFromNames() {
        assertSame(
                ShaderKey.SHADOW_ENTITIES_CUTOUT,
                IrisMetalCoreGbufferPipelines.resolve(RenderPipelines.ENTITY_SOLID, SHADOW)
        );

        RenderPipeline sameName = RenderPipeline.builder()
                .withLocation(RenderPipelines.ENTITY_SOLID.getLocation())
                .withVertexShader(RenderPipelines.ENTITY_SOLID.getVertexShader())
                .withFragmentShader(RenderPipelines.ENTITY_SOLID.getFragmentShader())
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .build();
        assertNull(IrisMetalCoreGbufferPipelines.resolve(sameName, MAIN));
        assertNull(IrisMetalCoreGbufferPipelines.resolve(sameName, SHADOW));
    }

    @Test
    void vanillaPatchSemanticsComeFromShaderKey() {
        MetalIrisShaderCompiler.VanillaPatchSemantics basic =
                MetalIrisShaderCompiler.vanillaPatchSemantics(ShaderKey.BASIC, false);
        assertSame(ShaderKey.BASIC.getAlphaTest(), basic.fallbackAlpha());
        assertFalse(basic.lines());
        assertFalse(basic.clouds());
        assertFalse(basic.attributes().hasColor());
        assertFalse(basic.attributes().hasTex());
        assertFalse(basic.attributes().hasOverlay());
        assertFalse(basic.attributes().hasLight());
        assertFalse(basic.attributes().hasNormal());

        MetalIrisShaderCompiler.VanillaPatchSemantics entity =
                MetalIrisShaderCompiler.vanillaPatchSemantics(ShaderKey.ENTITIES_CUTOUT_DIFFUSE, false);
        assertTrue(entity.attributes().hasColor());
        assertTrue(entity.attributes().hasTex());
        assertTrue(entity.attributes().hasOverlay());
        assertTrue(entity.attributes().hasLight());
        assertTrue(entity.attributes().hasNormal());

        MetalIrisShaderCompiler.VanillaPatchSemantics fullbright =
                MetalIrisShaderCompiler.vanillaPatchSemantics(ShaderKey.SPS, false);
        assertFalse(fullbright.attributes().hasLight());

        assertFalse(MetalIrisShaderCompiler.vanillaPatchSemantics(ShaderKey.LINES, false).lines());
        assertTrue(MetalIrisShaderCompiler.vanillaPatchSemantics(ShaderKey.LINES, true).lines());
        assertTrue(MetalIrisShaderCompiler.vanillaPatchSemantics(ShaderKey.LINES, true).attributes().isNewLines());
        assertTrue(MetalIrisShaderCompiler.vanillaPatchSemantics(ShaderKey.GLINT, false).attributes().isGlint());
        assertTrue(MetalIrisShaderCompiler.vanillaPatchSemantics(ShaderKey.TEXT, false).attributes().isText());
        assertTrue(MetalIrisShaderCompiler.vanillaPatchSemantics(ShaderKey.CLOUDS, false).clouds());
        assertFalse(MetalIrisShaderCompiler.vanillaPatchSemantics(ShaderKey.SHADOW_CLOUDS, false).clouds());
    }

    @Test
    void vanillaPatchBlocksBindToMojangGpuApiUniforms() {
        String source = """
                layout(std140) uniform iris_DynamicTransforms { mat4 ModelViewMat; } iris_transforms;
                layout(std140) uniform iris_Projection { mat4 iris_ProjMat; };
                layout(std140) uniform iris_Fog { vec4 FogColor; } iris_fogP;
                layout(std140) uniform iris_Globals { vec2 ScreenSize; } iris_globalInfo;
                layout(std140) uniform iris_CloudInfo { vec4 CloudColor; } iris_Clouds;
                layout(std140) uniform pack_Data { vec4 value; } pack_data;
                """;

        String remapped = MetalIrisShaderCompiler.remapVanillaBuiltInUniformBlocks(source);

        assertTrue(remapped.contains("uniform DynamicTransforms {"));
        assertTrue(remapped.contains("uniform Projection {"));
        assertTrue(remapped.contains("uniform Fog {"));
        assertTrue(remapped.contains("uniform Globals {"));
        assertTrue(remapped.contains("uniform CloudInfo {"));
        assertTrue(remapped.contains("uniform pack_Data {"));
        assertFalse(remapped.contains("uniform iris_DynamicTransforms"));
        assertFalse(remapped.contains("uniform iris_Projection"));
        assertFalse(remapped.contains("uniform iris_Fog"));
        assertFalse(remapped.contains("uniform iris_Globals"));
        assertFalse(remapped.contains("uniform iris_CloudInfo"));
    }

    @Test
    void irisBlendOverridesMapExactlyToMojangFactors() {
        assertTrue(IrisMetalPipelineOverrides.irisBlendFunction(BlendModeOverride.OFF).isEmpty());

        BlendMode additiveEyes = new BlendMode(
                BlendModeFunction.SRC_ALPHA.getGlId(),
                BlendModeFunction.ONE.getGlId(),
                BlendModeFunction.ZERO.getGlId(),
                BlendModeFunction.ONE.getGlId()
        );
        BlendFunction mapped = IrisMetalPipelineOverrides.irisBlendFunction(additiveEyes);
        assertEquals(BlendFactor.SRC_ALPHA, mapped.color().sourceFactor());
        assertEquals(BlendFactor.ONE, mapped.color().destFactor());
        assertEquals(BlendFactor.ZERO, mapped.alpha().sourceFactor());
        assertEquals(BlendFactor.ONE, mapped.alpha().destFactor());
        assertEquals(
                mapped,
                IrisMetalPipelineOverrides.irisBlendFunction(new BlendModeOverride(additiveEyes)).orElseThrow()
        );
    }

    @Test
    void packRenderTargetFormatsAreExactAndUnknownValuesFailClosed() {
        assertEquals(
                GpuFormat.RG11B10_FLOAT,
                IrisMetalPipelineOverrides.formatForInternalName("R11F_G11F_B10F")
        );
        assertEquals(GpuFormat.RGBA16_FLOAT, IrisMetalPipelineOverrides.formatForInternalName("RGB16F"));
        assertEquals(GpuFormat.RGBA16_UNORM, IrisMetalPipelineOverrides.formatForInternalName("RGB16"));
        assertEquals(GpuFormat.RGBA16_UNORM, IrisMetalPipelineOverrides.formatForInternalName("RGBA16"));
        assertThrows(
                IllegalArgumentException.class,
                () -> IrisMetalPipelineOverrides.formatForInternalName("NOT_A_REAL_IRIS_FORMAT")
        );
    }

    @Test
    void coreSamplerAliasesMatchIrisVanillaBindings() {
        for (String name : Set.of(
                "gtexture", "tex", "texture", "u_MainSampler", "gcolor", "colortex0"
        )) {
            assertEquals("Sampler0", IrisMetalPipelineOverrides.coreSamplerAlias(name));
        }
        assertEquals("Sampler1", IrisMetalPipelineOverrides.coreSamplerAlias("iris_overlay"));
        assertEquals("Sampler1", IrisMetalPipelineOverrides.coreSamplerAlias("overlay"));
        assertEquals("Sampler2", IrisMetalPipelineOverrides.coreSamplerAlias("lightmap"));
        assertNull(IrisMetalPipelineOverrides.coreSamplerAlias("shadowtex0"));
    }

    @Test
    void coreWhitePixelSelectionMatchesIrisLevelSamplerAbi() {
        // POSITION has no UV. Iris binds its explicit white pixel for both the
        // modern albedo aliases and the legacy gcolor/colortex0 aliases.
        for (String name : Set.of(
                "gtexture", "tex", "texture", "u_MainSampler", "gcolor", "colortex0"
        )) {
            assertTrue(IrisMetalPipelineOverrides.coreUsesWhitePixel(ShaderKey.BASIC, name), name);
            assertFalse(IrisMetalPipelineOverrides.coreUsesWhitePixel(ShaderKey.TEXTURED, name), name);
        }

        // Iris binds the white pixel whenever the selected ShaderKey does not
        // consume a UV2 attribute, whether because it is fullbright or because
        // the vertex format simply has no lightmap coordinate. A particle key
        // with UV2 must keep the real external Sampler2 binding.
        assertTrue(IrisMetalPipelineOverrides.coreUsesWhitePixel(ShaderKey.SPS, "lightmap"));
        assertTrue(IrisMetalPipelineOverrides.coreUsesWhitePixel(ShaderKey.TEXTURED, "lightmap"));
        assertFalse(IrisMetalPipelineOverrides.coreUsesWhitePixel(ShaderKey.PARTICLES, "lightmap"));

        // Entity vertex formats carry overlay UVs: missing Sampler1 is a real
        // input failure, not a reason to manufacture a white overlay.
        assertFalse(IrisMetalPipelineOverrides.coreUsesWhitePixel(
                ShaderKey.ENTITIES_CUTOUT_DIFFUSE, "iris_overlay"
        ));
        assertEquals("Sampler1", IrisMetalPipelineOverrides.coreSamplerAlias("iris_overlay"));
        assertTrue(IrisMetalPipelineOverrides.coreUsesWhitePixel(ShaderKey.TEXTURED, "iris_overlay"));
    }

    @Test
    void gbufferCustomTextureInterceptionMatchesIrisSamplerRegistration() {
        assertEquals(
                List.of("colortex4", "gaux1"),
                IrisMetalPipelineOverrides.gbufferCustomTextureAliases(null, false, "gaux1")
        );
        assertTrue(IrisMetalPipelineOverrides.gbufferCustomTextureAliases(null, false, "colortex0").isEmpty());

        assertEquals(
                List.of("tex", "texture", "gtexture", "u_MainSampler"),
                IrisMetalPipelineOverrides.gbufferCustomTextureAliases(null, false, "gtexture")
        );
        assertTrue(
                IrisMetalPipelineOverrides.gbufferCustomTextureAliases(
                        ShaderKey.TEXTURED, false, "gtexture"
                ).isEmpty(),
                "Iris core level samplers bypass the stage custom-texture interceptor"
        );
        assertEquals(
                List.of("depthtex1"),
                IrisMetalPipelineOverrides.gbufferCustomTextureAliases(
                        ShaderKey.TEXTURED, false, "depthtex1"
                )
        );

        assertEquals(
                List.of("shadowtex0", "shadow"),
                IrisMetalPipelineOverrides.gbufferCustomTextureAliases(null, false, "shadow")
        );
        assertEquals(
                List.of("shadowtex1", "shadow"),
                IrisMetalPipelineOverrides.gbufferCustomTextureAliases(null, true, "shadow")
        );
        assertEquals(
                List.of("shadowtex0", "watershadow"),
                IrisMetalPipelineOverrides.gbufferCustomTextureAliases(null, true, "watershadow")
        );
    }

    @Test
    void worldStatePreservesIrisPhaseOverrideAndCoreDrawGate() {
        MetalWorldRenderingPipeline.FrameState state = new MetalWorldRenderingPipeline.FrameState();

        assertSame(WorldRenderingPhase.NONE, state.phase());
        assertFalse(state.shouldOverrideShaders(true));

        state.beginWorldRendering();
        state.setPhase(WorldRenderingPhase.BLOCK_ENTITIES);
        assertSame(WorldRenderingPhase.BLOCK_ENTITIES, state.phase());
        assertTrue(state.shouldOverrideShaders(true));
        assertFalse(state.shouldOverrideShaders(false));

        state.setOverridePhase(WorldRenderingPhase.ENTITIES);
        assertSame(WorldRenderingPhase.ENTITIES, state.phase());
        state.setOverridePhase(null);
        assertSame(WorldRenderingPhase.BLOCK_ENTITIES, state.phase());

        state.setMainBound(false);
        assertFalse(state.shouldOverrideShaders(true));
        state.setMainBound(true);
        state.setPhase(WorldRenderingPhase.NONE);
        assertSame(WorldRenderingPhase.NONE, state.phase());

        state.endWorldRendering();
        assertFalse(state.shouldOverrideShaders(true));
        assertSame(state.updateNotifier(), state.updateNotifier());
    }

    private static void assertFamily(
            final Set<RenderPipeline> pipelines,
            final ShaderKey main,
            final ShaderKey blockEntity,
            final ShaderKey handSolid,
            final ShaderKey handTranslucent
    ) {
        for (RenderPipeline pipeline : pipelines) {
            assertSame(main, IrisMetalCoreGbufferPipelines.resolve(pipeline, MAIN));
            assertSame(blockEntity, IrisMetalCoreGbufferPipelines.resolve(pipeline, BLOCK_ENTITY));
            assertSame(handSolid, IrisMetalCoreGbufferPipelines.resolve(pipeline, HAND_SOLID));
            assertSame(handTranslucent, IrisMetalCoreGbufferPipelines.resolve(pipeline, HAND_TRANSLUCENT));
        }
    }

    @SuppressWarnings("unchecked")
    private static ShaderKey applyOracle(final Object resolver) {
        return ((Function<Object, ShaderKey>) resolver).apply(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<RenderPipeline, ?> oracleMap(final String fieldName) throws ReflectiveOperationException {
        Field field = IrisPipelines.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<RenderPipeline, ?>) field.get(null);
    }
}
