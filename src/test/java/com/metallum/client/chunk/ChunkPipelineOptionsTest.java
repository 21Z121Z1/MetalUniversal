package com.metallum.client.chunk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPipelineOptionsTest {
    @AfterEach
    void clear() {
        System.clearProperty(ChunkPipelineOptions.SECTION_INDEX_CACHE);
        System.clearProperty(ChunkPipelineOptions.PALETTE_PARSE);
        System.clearProperty(ChunkPipelineOptions.PALETTE_SERIALIZE);
        System.clearProperty(ChunkPipelineOptions.POI_SEARCH);
    }

    @Test
    void sectionIndexCacheIsOptIn() {
        assertFalse(ChunkPipelineOptions.sectionIndexCacheEnabled());
        System.setProperty(ChunkPipelineOptions.SECTION_INDEX_CACHE, "true");
        assertTrue(ChunkPipelineOptions.sectionIndexCacheEnabled());
    }

    @Test
    void paletteModesFailClosedAndParseExplicitValues() {
        assertFalse(ChunkPipelineOptions.paletteCodecEnabled());
        org.junit.jupiter.api.Assertions.assertEquals(ChunkPipelineOptions.Mode.OFF, ChunkPipelineOptions.poiSearchMode());

        System.setProperty(ChunkPipelineOptions.PALETTE_PARSE, "verify");
        assertTrue(ChunkPipelineOptions.paletteCodecEnabled());
        org.junit.jupiter.api.Assertions.assertEquals(
                ChunkPipelineOptions.Mode.VERIFY,
                ChunkPipelineOptions.paletteParseMode()
        );

        System.setProperty(ChunkPipelineOptions.PALETTE_SERIALIZE, "true");
        org.junit.jupiter.api.Assertions.assertEquals(
                ChunkPipelineOptions.Mode.FAST,
                ChunkPipelineOptions.paletteSerializeMode()
        );

        System.setProperty(ChunkPipelineOptions.POI_SEARCH, "verify");
        org.junit.jupiter.api.Assertions.assertEquals(
                ChunkPipelineOptions.Mode.VERIFY,
                ChunkPipelineOptions.poiSearchMode()
        );
    }
}
