package com.metallum.client.metal.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.PrimitiveTopology;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the fixed-Iris core draw catalog without constructing a renderer. */
final class IrisMetalCoreGbufferPipelinesTest {
    @Test
    void routesFixedCoreFamiliesToTheExpectedShaderKeys() {
        assertEquals(
                ShaderKey.TERRAIN_SOLID,
                IrisMetalCoreGbufferPipelines.resolve(
                        RenderPipelines.SOLID_TERRAIN,
                        new IrisMetalCoreGbufferPipelines.RenderState(false, false, false, false)
                )
        );
        assertEquals(
                ShaderKey.HAND_CUTOUT,
                IrisMetalCoreGbufferPipelines.resolve(
                        RenderPipelines.ENTITY_SOLID,
                        new IrisMetalCoreGbufferPipelines.RenderState(false, true, true, false)
                )
        );
        assertEquals(
                ShaderKey.BLOCK_ENTITY_DIFFUSE,
                IrisMetalCoreGbufferPipelines.resolve(
                        RenderPipelines.ENTITY_CUTOUT,
                        new IrisMetalCoreGbufferPipelines.RenderState(false, false, false, true)
                )
        );
        assertEquals(
                ShaderKey.SHADOW_ENTITIES_CUTOUT,
                IrisMetalCoreGbufferPipelines.resolve(
                        RenderPipelines.ENTITY_CUTOUT,
                        new IrisMetalCoreGbufferPipelines.RenderState(true, false, false, false)
                )
        );
    }

    @Test
    void catalogHasBothMainAndShadowRoutesAndFailsClosedForUnknownPipelines() {
        assertTrue(IrisMetalCoreGbufferPipelines.mappedPipelineCount(false) > 0);
        assertTrue(IrisMetalCoreGbufferPipelines.mappedPipelineCount(true) > 0);
        assertTrue(IrisMetalCoreGbufferPipelines.mappedPipelines(false).contains(RenderPipelines.SKY));
        assertTrue(IrisMetalCoreGbufferPipelines.mappedPipelines(true).contains(RenderPipelines.ENTITY_CUTOUT));

        RenderPipeline unknown = RenderPipeline.builder()
                .withLocation("minecraft/test_unmapped_iris_pipeline")
                .withVertexShader("metallum_test/unmapped_vertex")
                .withFragmentShader("metallum_test/unmapped_fragment")
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .build();
        assertNull(
                IrisMetalCoreGbufferPipelines.resolve(
                        unknown,
                        new IrisMetalCoreGbufferPipelines.RenderState(false, false, false, false)
                )
        );
    }
}
