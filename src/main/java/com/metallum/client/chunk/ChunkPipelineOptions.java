package com.metallum.client.chunk;

/** Opt-in exact-semantics chunk/server CPU fast paths. */
public final class ChunkPipelineOptions {
    public static final String SECTION_INDEX_CACHE = "metallum.opt.chunkSectionIndexCache";
    public static final String PALETTE_PARSE = "metallum.opt.chunkPaletteParse";
    public static final String PALETTE_SERIALIZE = "metallum.opt.chunkPaletteSerialize";
    public static final String POI_SEARCH = "metallum.opt.chunkPoiSearch";
    public static final String LIGHT_SNAPSHOT = "metallum.opt.chunkLightSnapshot";

    private ChunkPipelineOptions() {}

    public static boolean sectionIndexCacheEnabled() {
        return Boolean.getBoolean(SECTION_INDEX_CACHE);
    }

    public static Mode paletteParseMode() {
        return mode(PALETTE_PARSE);
    }

    public static Mode paletteSerializeMode() {
        return mode(PALETTE_SERIALIZE);
    }

    public static boolean paletteCodecEnabled() {
        return paletteParseMode() != Mode.OFF || paletteSerializeMode() != Mode.OFF;
    }

    public static Mode poiSearchMode() {
        return mode(POI_SEARCH);
    }

    public static Mode lightSnapshotMode() {
        return mode(LIGHT_SNAPSHOT);
    }

    private static Mode mode(String property) {
        String value = System.getProperty(property, "").trim().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "true", "fast" -> Mode.FAST;
            case "verify" -> Mode.VERIFY;
            default -> Mode.OFF;
        };
    }

    public enum Mode {
        OFF,
        VERIFY,
        FAST
    }
}
