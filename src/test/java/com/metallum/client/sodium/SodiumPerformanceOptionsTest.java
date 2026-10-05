package com.metallum.client.sodium;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SodiumPerformanceOptionsTest {
    @AfterEach
    void clearProperties() {
        System.clearProperty(SodiumPerformanceOptions.CULL_RECOVERY_PROPERTY);
        System.clearProperty(SodiumPerformanceOptions.CULL_REUSE_VERIFY_PROPERTY);
        System.clearProperty(SodiumPerformanceOptions.DRAW_MERGE_PROPERTY);
        System.clearProperty(SodiumPerformanceOptions.ENTITY_BOX_REUSE_PROPERTY);
        System.clearProperty(SodiumPerformanceOptions.BLOCK_RENDERER_REFS_PROPERTY);
        System.clearProperty(SodiumPerformanceOptions.VISIBILITY_SWEEP_PROPERTY);
        System.clearProperty(SodiumPerformanceOptions.CULL_REUSE_PROPERTY);
        System.clearProperty(SodiumPerformanceOptions.REGION_LOOKUP_CACHE_PROPERTY);
        System.clearProperty(SodiumPerformanceOptions.CLONE_CACHE_CLEANUP_PROPERTY);
        System.clearProperty(SodiumPerformanceOptions.CLONE_CACHE_ENTRIES_PROPERTY);
        System.clearProperty(SodiumPerformanceOptions.SLICE_BOUNDS_PROPERTY);
        System.clearProperty(SodiumPerformanceOptions.BIOME_UNIFORM_PROPERTY);
        System.clearProperty(SodiumPerformanceOptions.SHARED_AIR_PROPERTY);
    }

    @Test
    void semanticMixinsArePinnedToVerifiedSodium093Alpha1() {
        assertTrue(SodiumPerformanceOptions.supportsSemanticMixins("0.9.3-alpha.1"));
        assertTrue(SodiumPerformanceOptions.supportsSemanticMixins("0.9.3-alpha.1+mc26.3"));
        assertTrue(SodiumPerformanceOptions.supportsSemanticMixins(" 0.9.3-alpha.1+mc26.3 "));

        assertFalse(SodiumPerformanceOptions.supportsSemanticMixins(null));
        assertFalse(SodiumPerformanceOptions.supportsSemanticMixins(""));
        assertFalse(SodiumPerformanceOptions.supportsSemanticMixins("0.9.2+mc26.3"));
        assertFalse(SodiumPerformanceOptions.supportsSemanticMixins("0.9.3"));
        assertFalse(SodiumPerformanceOptions.supportsSemanticMixins("0.9.3-alpha.2+mc26.3"));
        assertFalse(SodiumPerformanceOptions.supportsSemanticMixins("0.9.4-alpha.1+mc26.3"));
    }

    @Test
    void featuresAreOptIn() {
        assertFalse(SodiumPerformanceOptions.cullRecoveryEnabled());
        assertFalse(SodiumPerformanceOptions.cullReuseVerifyEnabled());
        assertFalse(SodiumPerformanceOptions.cullReuseEnabled());
        assertFalse(SodiumPerformanceOptions.visibilitySweepAnyModeEnabled());
        assertFalse(SodiumPerformanceOptions.blockRendererRefsEnabled());
        assertFalse(SodiumPerformanceOptions.entityBoxReuseEnabled());
        assertFalse(SodiumPerformanceOptions.drawMergeEnabled());
        assertFalse(SodiumPerformanceOptions.regionLookupCacheEnabled());
        assertFalse(SodiumPerformanceOptions.cloneCacheTuningEnabled());
        assertFalse(SodiumPerformanceOptions.sliceBoundsEnabled());
        assertFalse(SodiumPerformanceOptions.biomeUniformEnabled());
        assertFalse(SodiumPerformanceOptions.sharedAirSliceEnabled());

        System.setProperty(SodiumPerformanceOptions.CULL_RECOVERY_PROPERTY, "true");
        System.setProperty(SodiumPerformanceOptions.CULL_REUSE_VERIFY_PROPERTY, "true");
        System.setProperty(SodiumPerformanceOptions.VISIBILITY_SWEEP_PROPERTY, "verify");
        System.setProperty(SodiumPerformanceOptions.BLOCK_RENDERER_REFS_PROPERTY, "true");
        System.setProperty(SodiumPerformanceOptions.ENTITY_BOX_REUSE_PROPERTY, "true");
        System.setProperty(SodiumPerformanceOptions.DRAW_MERGE_PROPERTY, "true");
        System.setProperty(SodiumPerformanceOptions.REGION_LOOKUP_CACHE_PROPERTY, "true");
        System.setProperty(SodiumPerformanceOptions.CLONE_CACHE_CLEANUP_PROPERTY, "true");
        System.setProperty(SodiumPerformanceOptions.SLICE_BOUNDS_PROPERTY, "true");
        System.setProperty(SodiumPerformanceOptions.BIOME_UNIFORM_PROPERTY, "true");
        System.setProperty(SodiumPerformanceOptions.SHARED_AIR_PROPERTY, "true");

        assertTrue(SodiumPerformanceOptions.cullRecoveryEnabled());
        assertTrue(SodiumPerformanceOptions.cullReuseVerifyEnabled());
        assertTrue(SodiumPerformanceOptions.cullReuseAnyModeEnabled());
        assertTrue(SodiumPerformanceOptions.visibilitySweepVerifyEnabled());
        assertTrue(SodiumPerformanceOptions.blockRendererRefsEnabled());
        assertTrue(SodiumPerformanceOptions.entityBoxReuseEnabled());
        assertTrue(SodiumPerformanceOptions.drawMergeEnabled());
        assertTrue(SodiumPerformanceOptions.regionLookupCacheEnabled());
        assertTrue(SodiumPerformanceOptions.cloneCacheTuningEnabled());
        assertTrue(SodiumPerformanceOptions.sliceBoundsEnabled());
        assertTrue(SodiumPerformanceOptions.biomeUniformEnabled());
        assertTrue(SodiumPerformanceOptions.sharedAirSliceEnabled());
    }

    @Test
    void explicitCloneCapacityEnablesOnlyCloneCacheTuning() {
        System.setProperty(SodiumPerformanceOptions.CLONE_CACHE_ENTRIES_PROPERTY, "4096");

        assertTrue(SodiumPerformanceOptions.cloneCacheTuningEnabled());
        assertFalse(SodiumPerformanceOptions.cloneCacheCleanupEnabled());
        assertFalse(SodiumPerformanceOptions.cullRecoveryEnabled());
        assertFalse(SodiumPerformanceOptions.cullReuseVerifyEnabled());
        assertFalse(SodiumPerformanceOptions.regionLookupCacheEnabled());
    }

    @Test
    void cloneCacheCapacityOnlyExpandsWithinBound() {
        assertEquals(512, SodiumPerformanceOptions.cloneCacheEntries());

        System.setProperty(SodiumPerformanceOptions.CLONE_CACHE_ENTRIES_PROPERTY, "64");
        assertEquals(512, SodiumPerformanceOptions.cloneCacheEntries());

        System.setProperty(SodiumPerformanceOptions.CLONE_CACHE_ENTRIES_PROPERTY, "4096");
        assertEquals(4096, SodiumPerformanceOptions.cloneCacheEntries());

        System.setProperty(SodiumPerformanceOptions.CLONE_CACHE_ENTRIES_PROPERTY, "999999");
        assertEquals(16_384, SodiumPerformanceOptions.cloneCacheEntries());

        System.setProperty(SodiumPerformanceOptions.CLONE_CACHE_ENTRIES_PROPERTY, "invalid");
        assertEquals(512, SodiumPerformanceOptions.cloneCacheEntries());
    }
}
