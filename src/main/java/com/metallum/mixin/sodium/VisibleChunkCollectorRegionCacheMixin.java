package com.metallum.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.lists.VisibleChunkCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Reuses the last successful region lookup while one render-list traversal
 * continues through sections belonging to the same Sodium render region.
 *
 * <p>A null lookup is deliberately not cached so concurrent region removal or
 * publication cannot turn this micro-optimization into an authority decision.
 */
@Mixin(value = VisibleChunkCollector.class, remap = false)
public abstract class VisibleChunkCollectorRegionCacheMixin {
    @Unique private boolean metallum$hasCachedRegion;
    @Unique private int metallum$cachedRegionX;
    @Unique private int metallum$cachedRegionY;
    @Unique private int metallum$cachedRegionZ;
    @Unique private RenderRegion metallum$cachedRegion;

    @Redirect(
            method = "visit",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegionManager;getForChunk(III)Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;"
            )
    )
    private RenderRegion metallum$reuseRegionLookup(RenderRegionManager regions, int x, int y, int z) {
        int regionX = x >> RenderRegion.REGION_WIDTH_SH;
        int regionY = y >> RenderRegion.REGION_HEIGHT_SH;
        int regionZ = z >> RenderRegion.REGION_LENGTH_SH;

        if (this.metallum$hasCachedRegion
                && regionX == this.metallum$cachedRegionX
                && regionY == this.metallum$cachedRegionY
                && regionZ == this.metallum$cachedRegionZ) {
            return this.metallum$cachedRegion;
        }

        RenderRegion region = regions.getForChunk(x, y, z);
        if (region != null) {
            this.metallum$hasCachedRegion = true;
            this.metallum$cachedRegionX = regionX;
            this.metallum$cachedRegionY = regionY;
            this.metallum$cachedRegionZ = regionZ;
            this.metallum$cachedRegion = region;
        } else {
            this.metallum$hasCachedRegion = false;
            this.metallum$cachedRegion = null;
        }

        return region;
    }
}
