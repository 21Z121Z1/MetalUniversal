package com.metallum.client.storage;

/** Opt-in storage optimizations that retain vanilla on-disk semantics. */
public final class ChunkStorageOptions {
    public static final String CHUNK_SAVE_SKIP_PROPERTY = "metallum.opt.chunkSaveSkip";

    private ChunkStorageOptions() {
    }

    public static boolean chunkSaveSkipEnabled() {
        return Boolean.getBoolean(CHUNK_SAVE_SKIP_PROPERTY);
    }
}
