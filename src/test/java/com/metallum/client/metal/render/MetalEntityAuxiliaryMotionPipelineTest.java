package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalEntityAuxiliaryMotionPipelineTest {
    @AfterEach
    void clear() {
        MetalEntityMotionPipeline.clear();
    }

    @Test
    void leashIsExactOnlyWithVerifiedMinecraftAbi() {
        RenderPipeline leash = pipeline("core/rendertype_leash", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP);
        assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(leash));
        assertTrue(MetalEntityMotionPipeline.isSplittableVertexShader(leash));
        assertFalse(MetalEntityMotionPipeline.supports(leash));
        assertEquals("core/leash_previous_motion", MetalEntityMotionPipeline.forPreviousPositions(leash).getShaders().get(ShaderType.VERTEX).getPath());
    }

    @Test
    void worldAndSeeThroughTextUseTheirExactLayouts() {
        RenderPipeline world = pipeline("core/text", DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR);
        RenderPipeline seeThrough = pipeline("core/text", DefaultVertexFormat.POSITION_TEX_COLOR, "IS_SEE_THROUGH");
        RenderPipeline gui = pipeline("core/text", DefaultVertexFormat.POSITION_TEX_COLOR, "IS_GUI");
        assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(world));
        assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(seeThrough));
        assertFalse(MetalEntityMotionPipeline.supportsPreviousPositions(gui));
        assertEquals("core/text_previous_motion", MetalEntityMotionPipeline.forPreviousPositions(world).getShaders().get(ShaderType.VERTEX).getPath());
        assertEquals("core/text_previous_motion", MetalEntityMotionPipeline.forPreviousPositions(seeThrough).getShaders().get(ShaderType.VERTEX).getPath());
    }

    @Test
    void textBackgroundNormalAndSeeThroughUseDifferentVerifiedLayouts() {
        RenderPipeline world = pipeline("core/text_background", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP);
        RenderPipeline seeThrough = pipeline("core/text_background", DefaultVertexFormat.POSITION_COLOR, "IS_SEE_THROUGH");
        assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(world));
        assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(seeThrough));
        assertEquals("core/text_background_previous_motion", MetalEntityMotionPipeline.forPreviousPositions(world).getShaders().get(ShaderType.VERTEX).getPath());
        assertEquals("core/text_background_previous_motion", MetalEntityMotionPipeline.forPreviousPositions(seeThrough).getShaders().get(ShaderType.VERTEX).getPath());
    }

    @Test
    void particleUsesExactPreviousPositionsForOpaqueAndTranslucentPipelines() {
        RenderPipeline particle = pipeline("core/particle", DefaultVertexFormat.PARTICLE);
        assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(particle));
        assertTrue(MetalEntityMotionPipeline.isSplittableVertexShader(particle));
        assertFalse(MetalEntityMotionPipeline.supports(particle));
        RenderPipeline exact = MetalEntityMotionPipeline.forPreviousPositions(particle);
        assertEquals("core/particle_previous_motion", exact.getShaders().get(ShaderType.VERTEX).getPath());
        assertEquals("core/particle_previous_motion", exact.getShaders().get(ShaderType.FRAGMENT).getPath());
    }

    @Test
    void blendedEntityAndOitColorHaveExactGeometryButNoRigidFallback() {
        for (var source : java.util.List.of(net.minecraft.client.renderer.RenderPipelines.ENTITY_TRANSLUCENT,
                net.minecraft.client.renderer.RenderPipelines.OIT_ENTITY.accumulatePipeline())) {
            assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(source));
            assertFalse(MetalEntityMotionPipeline.supports(source));
            assertTrue(MetalEntityMotionPipeline.forPreviousPositions(source).getDepthStencilState().writeDepth(),
                    "transparent overlap resolves against a private depth copy");
        }
    }

    @Test
    void entityShadowUsesExactPreviousPositionsDespiteTranslucentSource() {
        RenderPipeline shadow = pipeline("core/rendertype_entity_shadow", DefaultVertexFormat.ENTITY);
        assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(shadow));
        assertTrue(MetalEntityMotionPipeline.isSplittableVertexShader(shadow));
        assertFalse(MetalEntityMotionPipeline.supports(shadow));
        RenderPipeline exact = MetalEntityMotionPipeline.forPreviousPositions(shadow);
        assertEquals("core/entity_previous_motion", exact.getShaders().get(ShaderType.VERTEX).getPath());
        assertEquals("core/shadow_previous_motion", exact.getShaders().get(ShaderType.FRAGMENT).getPath());
    }

    @Test
    void waterMaskUsesPositionOnlyExactPreviousAbi() {
        RenderPipeline waterMask = pipeline("core/rendertype_water_mask", DefaultVertexFormat.POSITION);
        assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(waterMask));
        assertTrue(MetalEntityMotionPipeline.isSplittableVertexShader(waterMask));
        assertFalse(MetalEntityMotionPipeline.supports(waterMask));
        RenderPipeline exact = MetalEntityMotionPipeline.forPreviousPositions(waterMask);
        assertEquals("core/position_previous_motion", exact.getShaders().get(ShaderType.VERTEX).getPath());
        assertEquals("core/position_previous_motion", exact.getShaders().get(ShaderType.FRAGMENT).getPath());
    }

    @Test
    void specialGlintAndDissolveRetainTheirExactOriginalLayouts() {
        for (var source : java.util.List.of(net.minecraft.client.renderer.RenderPipelines.ITEM_CUTOUT_GLINT_SPECIAL,
                net.minecraft.client.renderer.RenderPipelines.ITEM_TRANSLUCENT_GLINT_SPECIAL,
                net.minecraft.client.renderer.RenderPipelines.OIT_ITEM_GLINT_SPECIAL.accumulatePipeline(),
                net.minecraft.client.renderer.RenderPipelines.ENTITY_CUTOUT_DISSOLVE)) {
            assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(source));
            var exact=MetalEntityMotionPipeline.forPreviousPositionsWithDepth(source);
            assertEquals(source.getVertexFormatBinding(0),exact.getVertexFormatBinding(0));
            assertTrue(exact.getShaderDefines().flags().contains("METALLUM_REACTIVE_MATERIAL"));
        }
        assertFalse(MetalEntityMotionPipeline.supports(net.minecraft.client.renderer.RenderPipelines.ENTITY_CUTOUT_DISSOLVE),
                "Dissolving geometry requires exact history, without a rigid fallback");
        var wrong=pipeline("core/item",DefaultVertexFormat.ENTITY,"GLINT","GLINT_SPECIAL");
        assertFalse(MetalEntityMotionPipeline.supportsPreviousPositions(wrong));
        assertFalse(MetalEntityMotionPipeline.supports(wrong));
        assertFalse(MetalEntityMotionPipeline.supportsPreviousPositions(
                pipeline("core/entity",DefaultVertexFormat.ENTITY_GLINT_SPECIAL,"GLINT","GLINT_SPECIAL")));
    }

    @Test
    void wrongLayoutsRemainFailClosed() {
        RenderPipeline replacement = RenderPipeline.builder().withLocation("test/replacement")
                .withVertexShader(Identifier.fromNamespaceAndPath("another_pack", "core/entity"))
                .withFragmentShader("core/entity").withVertexBinding(0, DefaultVertexFormat.ENTITY)
                .withPrimitiveTopology(PrimitiveTopology.QUADS).build();
        assertFalse(MetalEntityMotionPipeline.supports(replacement));
        assertFalse(MetalEntityMotionPipeline.supportsPreviousPositions(replacement),
                "a matching path in a different namespace does not prove matching vertex semantics");
        assertFalse(MetalEntityMotionPipeline.supportsPreviousPositions(
                pipeline("core/rendertype_leash", DefaultVertexFormat.ENTITY)));
        assertFalse(MetalEntityMotionPipeline.supportsPreviousPositions(
                pipeline("core/text", DefaultVertexFormat.POSITION_TEX_COLOR)));
        assertFalse(MetalEntityMotionPipeline.supportsPreviousPositions(
                pipeline("core/text_background", DefaultVertexFormat.POSITION_COLOR)));
        assertFalse(MetalEntityMotionPipeline.supportsPreviousPositions(
                pipeline("core/rendertype_water_mask", DefaultVertexFormat.ENTITY)));
        assertFalse(MetalEntityMotionPipeline.supportsPreviousPositions(
                pipeline("core/rendertype_entity_shadow", DefaultVertexFormat.BLOCK)));
        assertFalse(MetalEntityMotionPipeline.supportsPreviousPositions(
                pipeline("core/particle", DefaultVertexFormat.ENTITY)));
    }

    private static RenderPipeline pipeline(final String shader, final VertexFormat format, final String... defines) {
        Identifier shaderId = Identifier.fromNamespaceAndPath("minecraft", shader);
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("metallum", "test/" + shader.replace('/', '_') + "/" + format.getVertexSize()))
                .withVertexShader(shaderId)
                .withFragmentShader(shaderId)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withVertexBinding(0, format);
        for (String define : defines) {
            builder.withShaderDefine(define);
        }
        return builder.build();
    }
}
