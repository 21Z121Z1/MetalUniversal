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
        System.clearProperty(SodiumPerformanceOptions.REGION_LOOKUP_CACHE_PROPERTY);
        System.clearProperty(SodiumPerformanceOptions.CLONE_CACHE_CLEANUP_PROPERTY);
        System.clearProperty(SodiumPerformanceOptions.CLONE_CACHE_ENTRIES_PROPERTY);
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
        assertFalse(SodiumPerformanceOptions.regionLookupCacheEnabled());
        assertFalse(SodiumPerformanceOptions.cloneCacheTuningEnabled());

        System.setProperty(SodiumPerformanceOptions.CULL_RECOVERY_PROPERTY, "true");
        System.setProperty(SodiumPerformanceOptions.REGION_LOOKUP_CACHE_PROPERTY, "true");
        System.setProperty(SodiumPerformanceOptions.CLONE_CACHE_CLEANUP_PROPERTY, "true");

        assertTrue(SodiumPerformanceOptions.cullRecoveryEnabled());
        assertTrue(SodiumPerformanceOptions.regionLookupCacheEnabled());
        assertTrue(SodiumPerformanceOptions.cloneCacheTuningEnabled());
    }

    @Test
    void explicitCloneCapacityEnablesOnlyCloneCacheTuning() {
        System.setProperty(SodiumPerformanceOptions.CLONE_CACHE_ENTRIES_PROPERTY, "4096");

        assertTrue(SodiumPerformanceOptions.cloneCacheTuningEnabled());
        assertFalse(SodiumPerformanceOptions.cloneCacheCleanupEnabled());
        assertFalse(SodiumPerformanceOptions.cullRecoveryEnabled());
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
