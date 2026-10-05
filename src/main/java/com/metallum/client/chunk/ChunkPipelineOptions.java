package com.metallum.client.chunk;

/** Opt-in exact-semantics chunk/server CPU fast paths. */
public final class ChunkPipelineOptions {
    public static final String SECTION_INDEX_CACHE = "metallum.opt.chunkSectionIndexCache";

    private ChunkPipelineOptions() {}

    public static boolean sectionIndexCacheEnabled() {
        return Boolean.getBoolean(SECTION_INDEX_CACHE);
    }
}
