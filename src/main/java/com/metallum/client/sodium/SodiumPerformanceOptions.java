package com.metallum.client.sodium;

/**
 * Opt-in Sodium performance switches used by the MetalUniversal adapter.
 *
 * <p>These switches intentionally remain disabled until their exact-output and
 * performance gates have been exercised on Minecraft 26.3 with Sodium 0.9.3.
 */
public final class SodiumPerformanceOptions {
    public static final String CULL_RECOVERY_PROPERTY = "metallum.opt.sodiumCullRecovery";
    public static final String REGION_LOOKUP_CACHE_PROPERTY = "metallum.opt.sodiumRegionLookupCache";
    public static final String CLONE_CACHE_CLEANUP_PROPERTY = "metallum.opt.sodiumCloneCacheCleanup";
    public static final String CLONE_CACHE_ENTRIES_PROPERTY = "metallum.opt.sodiumCloneCacheEntries";
    public static final String SLICE_BOUNDS_PROPERTY = "metallum.opt.sodiumSliceBounds";
    public static final String BIOME_UNIFORM_PROPERTY = "metallum.opt.sodiumBiomeUniform";
    public static final String SHARED_AIR_PROPERTY = "metallum.opt.sodiumSharedAirSlice";

    public static final int SODIUM_DEFAULT_CLONE_CACHE_ENTRIES = 512;
    public static final int MAX_CLONE_CACHE_ENTRIES = 16_384;

    private SodiumPerformanceOptions() {
    }

    /**
     * The semantic P0 mixins touch Sodium implementation details which are
     * verified against Sodium 0.9.3-alpha.1 for Minecraft 26.3. Keep them
     * fail-closed on any other Sodium build so an otherwise compatible adapter cannot accidentally
     * apply stale private-field/method assumptions.
     */
    public static boolean supportsSemanticMixins(String version) {
        if (version == null) {
            return false;
        }

        String normalized = version.trim();
        return normalized.equals("0.9.3-alpha.1")
                || normalized.startsWith("0.9.3-alpha.1+");
    }

    public static boolean cullRecoveryEnabled() {
        return Boolean.getBoolean(CULL_RECOVERY_PROPERTY);
    }

    public static boolean regionLookupCacheEnabled() {
        return Boolean.getBoolean(REGION_LOOKUP_CACHE_PROPERTY);
    }

    public static boolean cloneCacheCleanupEnabled() {
        return Boolean.getBoolean(CLONE_CACHE_CLEANUP_PROPERTY);
    }

    public static boolean sliceBoundsEnabled() {
        return Boolean.getBoolean(SLICE_BOUNDS_PROPERTY);
    }

    public static boolean biomeUniformEnabled() {
        return Boolean.getBoolean(BIOME_UNIFORM_PROPERTY);
    }

    public static boolean sharedAirSliceEnabled() {
        return Boolean.getBoolean(SHARED_AIR_PROPERTY);
    }

    public static boolean cloneCacheTuningEnabled() {
        return cloneCacheCleanupEnabled() || System.getProperty(CLONE_CACHE_ENTRIES_PROPERTY) != null;
    }

    /**
     * MetalUniversal only expands Sodium's cache here. A smaller cache would be
     * a memory policy change and belongs in a separately validated profile.
     *
     * <p>The production profile does not currently choose a capacity from
     * physical RAM automatically. Explicit capacities exist for controlled
     * A/B trials until device-tier measurements justify a default policy.
     */
    public static int cloneCacheEntries() {
        String raw = System.getProperty(CLONE_CACHE_ENTRIES_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return SODIUM_DEFAULT_CLONE_CACHE_ENTRIES;
        }

        final int requested;
        try {
            requested = Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return SODIUM_DEFAULT_CLONE_CACHE_ENTRIES;
        }

        return Math.max(
                SODIUM_DEFAULT_CLONE_CACHE_ENTRIES,
                Math.min(MAX_CLONE_CACHE_ENTRIES, requested)
        );
    }
}
