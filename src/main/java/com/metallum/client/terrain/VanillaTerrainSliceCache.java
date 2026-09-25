package com.metallum.client.terrain;

import java.util.Arrays;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSectionBufferSlice;

/** Mesh-owned allocation metadata; callers retain Vanilla's dispatcher copy lock. */
public final class VanillaTerrainSliceCache {
    public interface Owner {
        VanillaTerrainSliceCache metallum$terrainSliceCache();
    }

    // Lookups and evidence snapshots run on the render thread. No frame allocations.
    public static long hits;
    public static long misses;
    public static long verifiedHits;

    private final RenderSectionBufferSlice[] slices;
    private Object dispatcher;
    private int validLayers;

    public VanillaTerrainSliceCache(int layerCount) {
        slices = new RenderSectionBufferSlice[layerCount];
    }

    public boolean contains(Object owner, int layer) {
        return dispatcher == owner && (validLayers & (1 << layer)) != 0;
    }

    public RenderSectionBufferSlice get(int layer) {
        return slices[layer];
    }

    public void put(Object owner, int layer, RenderSectionBufferSlice slice) {
        if (dispatcher != owner) {
            invalidate();
            dispatcher = owner;
        }
        slices[layer] = slice;
        validLayers |= 1 << layer; // A missing allocation is cached until its next mutation too.
    }

    public void invalidate() {
        validLayers = 0;
        dispatcher = null;
        Arrays.fill(slices, null); // Never retain retired GPU resources through a live mesh.
    }

    public static void invalidate(Object key) {
        if (key instanceof Owner owner) owner.metallum$terrainSliceCache().invalidate();
    }
}
