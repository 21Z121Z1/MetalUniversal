package com.metallum.client.metal.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
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
        assertEquals("core/leash_previous_motion", MetalEntityMotionPipeline.forPreviousPositions(leash).getVertexShader().getPath());
    }

    @Test
    void worldAndSeeThroughTextUseTheirExactLayouts() {
        RenderPipeline world = pipeline("core/text", DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR);
        RenderPipeline seeThrough = pipeline("core/text", DefaultVertexFormat.POSITION_TEX_COLOR, "IS_SEE_THROUGH");
        RenderPipeline gui = pipeline("core/text", DefaultVertexFormat.POSITION_TEX_COLOR, "IS_GUI");
        assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(world));
        assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(seeThrough));
        assertFalse(MetalEntityMotionPipeline.supportsPreviousPositions(gui));
        assertEquals("core/text_previous_motion", MetalEntityMotionPipeline.forPreviousPositions(world).getVertexShader().getPath());
        assertEquals("core/text_previous_motion", MetalEntityMotionPipeline.forPreviousPositions(seeThrough).getVertexShader().getPath());
    }

    @Test
    void textBackgroundNormalAndSeeThroughUseDifferentVerifiedLayouts() {
        RenderPipeline world = pipeline("core/text_background", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP);
        RenderPipeline seeThrough = pipeline("core/text_background", DefaultVertexFormat.POSITION_COLOR, "IS_SEE_THROUGH");
        assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(world));
        assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(seeThrough));
        assertEquals("core/text_background_previous_motion", MetalEntityMotionPipeline.forPreviousPositions(world).getVertexShader().getPath());
        assertEquals("core/text_background_previous_motion", MetalEntityMotionPipeline.forPreviousPositions(seeThrough).getVertexShader().getPath());
    }

    @Test
    void particleUsesExactPreviousPositionsForOpaqueAndTranslucentPipelines() {
        RenderPipeline particle = pipeline("core/particle", DefaultVertexFormat.PARTICLE);
        assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(particle));
        assertTrue(MetalEntityMotionPipeline.isSplittableVertexShader(particle));
        assertFalse(MetalEntityMotionPipeline.supports(particle));
        RenderPipeline exact = MetalEntityMotionPipeline.forPreviousPositions(particle);
        assertEquals("core/particle_previous_motion", exact.getVertexShader().getPath());
        assertEquals("core/particle_previous_motion", exact.getFragmentShader().getPath());
    }

    @Test
    void entityShadowUsesExactPreviousPositionsDespiteTranslucentSource() {
        RenderPipeline shadow = pipeline("core/rendertype_entity_shadow", DefaultVertexFormat.ENTITY);
        assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(shadow));
        assertTrue(MetalEntityMotionPipeline.isSplittableVertexShader(shadow));
        assertFalse(MetalEntityMotionPipeline.supports(shadow));
        RenderPipeline exact = MetalEntityMotionPipeline.forPreviousPositions(shadow);
        assertEquals("core/entity_previous_motion", exact.getVertexShader().getPath());
        assertEquals("core/shadow_previous_motion", exact.getFragmentShader().getPath());
    }

    @Test
    void waterMaskUsesPositionOnlyExactPreviousAbi() {
        RenderPipeline waterMask = pipeline("core/rendertype_water_mask", DefaultVertexFormat.POSITION);
        assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(waterMask));
        assertTrue(MetalEntityMotionPipeline.isSplittableVertexShader(waterMask));
        assertFalse(MetalEntityMotionPipeline.supports(waterMask));
        RenderPipeline exact = MetalEntityMotionPipeline.forPreviousPositions(waterMask);
        assertEquals("core/position_previous_motion", exact.getVertexShader().getPath());
        assertEquals("core/position_previous_motion", exact.getFragmentShader().getPath());
    }

    @Test
    void wrongLayoutsRemainFailClosed() {
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
