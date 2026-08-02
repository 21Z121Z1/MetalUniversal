package com.metallum.client.metal.render;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalStableTerrainSamplerTest {
    private static final Identifier SODIUM_TERRAIN =
            Identifier.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque");

    @Test
    void matchesOnlySodiumTerrainBlockTexture() {
        assertTrue(MetalCompiledRenderPipeline.isSodiumTerrainBlockSampler("u_BlockTex", SODIUM_TERRAIN));
        assertFalse(MetalCompiledRenderPipeline.isSodiumTerrainBlockSampler("u_LightTex", SODIUM_TERRAIN));
        assertFalse(MetalCompiledRenderPipeline.isSodiumTerrainBlockSampler(
                "u_BlockTex",
                Identifier.fromNamespaceAndPath("minecraft", "core/entity")
        ));
        assertFalse(MetalCompiledRenderPipeline.isSodiumTerrainBlockSampler(
                "u_BlockTex",
                Identifier.fromNamespaceAndPath("iris", "terrain/custom")
        ));
    }
}
