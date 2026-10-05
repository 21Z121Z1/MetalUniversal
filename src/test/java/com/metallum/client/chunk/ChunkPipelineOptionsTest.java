package com.metallum.client.chunk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPipelineOptionsTest {
    @AfterEach
    void clear() {
        System.clearProperty(ChunkPipelineOptions.SECTION_INDEX_CACHE);
    }

    @Test
    void sectionIndexCacheIsOptIn() {
        assertFalse(ChunkPipelineOptions.sectionIndexCacheEnabled());
        System.setProperty(ChunkPipelineOptions.SECTION_INDEX_CACHE, "true");
        assertTrue(ChunkPipelineOptions.sectionIndexCacheEnabled());
    }
}
