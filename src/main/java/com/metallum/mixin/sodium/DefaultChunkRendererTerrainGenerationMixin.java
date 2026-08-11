package com.metallum.mixin.sodium;

import com.metallum.client.terrain.TerrainMeshGeneration;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Prevents a region containing old-ABI geometry from reaching a draw batch. */
@Mixin(DefaultChunkRenderer.class)
public abstract class DefaultChunkRendererTerrainGenerationMixin {
    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;"
                            + "getStorage("
                            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)"
                            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/data/SectionRenderDataStorage;"
            ),
            remap = false,
            require = 2
    )
    private SectionRenderDataStorage metallum$hideStaleTerrainRegion(
            final RenderRegion region,
            final TerrainRenderPass pass
    ) {
        return TerrainMeshGeneration.isRegionCurrent(region)
                ? region.getStorage(pass)
                : null;
    }
}
