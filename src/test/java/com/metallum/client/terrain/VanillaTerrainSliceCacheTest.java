package com.metallum.client.terrain;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSectionBufferSlice;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VanillaTerrainSliceCacheTest {
    @Test
    void missingAllocationBecomesVisibleOnlyAfterMutationInvalidatesTheCache() {
        var cache = new VanillaTerrainSliceCache(3);
        var dispatcher = new Object();
        cache.put(dispatcher, 0, null);
        assertTrue(cache.contains(dispatcher, 0));
        assertNull(cache.get(0));
        cache.invalidate();
        assertFalse(cache.contains(dispatcher, 0));
        var uploaded = new RenderSectionBufferSlice(null, 128, null, 0);
        cache.put(dispatcher, 0, uploaded);
        assertSame(uploaded, cache.get(0));
    }

    @Test
    void indexReuploadInvalidatesAllLayersAndReleasesOldSlices() {
        var cache = new VanillaTerrainSliceCache(3);
        var dispatcher = new Object();
        for (int layer = 0; layer < 3; layer++) {
            cache.put(dispatcher, layer, new RenderSectionBufferSlice(null, 128, null, 64));
        }
        cache.invalidate();
        for (int layer = 0; layer < 3; layer++) {
            assertFalse(cache.contains(dispatcher, layer));
            assertNull(cache.get(layer));
        }
        cache.put(dispatcher, 2, new RenderSectionBufferSlice(null, 128, null, 256));
        assertEquals(256, cache.get(2).indexBufferOffset());
    }

    @Test
    void dispatcherReplacementCannotReuseAnotherWorldsAllocations() {
        var cache = new VanillaTerrainSliceCache(3);
        var oldDispatcher = new Object();
        var newDispatcher = new Object();
        cache.put(oldDispatcher, 0, new RenderSectionBufferSlice(null, 32, null, 0));
        assertFalse(cache.contains(newDispatcher, 0));
        cache.put(newDispatcher, 1, null);
        assertFalse(cache.contains(oldDispatcher, 0));
        assertFalse(cache.contains(newDispatcher, 0));
        assertNull(cache.get(0));
        assertTrue(cache.contains(newDispatcher, 1));
    }
}
