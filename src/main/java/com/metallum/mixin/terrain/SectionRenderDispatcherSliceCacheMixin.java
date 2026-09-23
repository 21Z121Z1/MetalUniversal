package com.metallum.mixin.terrain;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.metallum.client.terrain.VanillaTerrainSliceCache;
import java.util.Objects;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSectionBufferSlice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SectionRenderDispatcher.class)
abstract class SectionRenderDispatcherSliceCacheMixin {
    @Unique
    private static final boolean metallum$verifySlices = Boolean.getBoolean("metallum.terrain.verifySliceCache");

    @WrapMethod(method = "getRenderSectionSlice")
    private RenderSectionBufferSlice metallum$cachedSlice(SectionMesh mesh, ChunkSectionLayer layer,
                                                          Operation<RenderSectionBufferSlice> original) {
        if (!(mesh instanceof VanillaTerrainSliceCache.Owner owner)) return original.call(mesh, layer);
        VanillaTerrainSliceCache cache = owner.metallum$terrainSliceCache();
        int index = layer.ordinal();
        if (cache.contains(this, index)) {
            RenderSectionBufferSlice slice = cache.get(index);
            if (metallum$verifySlices) {
                // Diagnostic differential oracle; disabled in performance trials.
                if (!Objects.equals(slice, original.call(mesh, layer))) {
                    throw new IllegalStateException("Terrain slice cache diverged from the live allocation: " + layer);
                }
                VanillaTerrainSliceCache.verifiedHits++;
            }
            VanillaTerrainSliceCache.hits++;
            return slice;
        }
        VanillaTerrainSliceCache.misses++;
        RenderSectionBufferSlice slice = original.call(mesh, layer);
        cache.put(this, index, slice);
        return slice;
    }
}
