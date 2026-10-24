package com.metallum.client.sodium;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SodiumMultiDrawMergeTest {
    @Test
    void mergesOnlySharedIndexRangesWithContiguousVertices() {
        assertTrue(SodiumMultiDrawMerge.canMerge(60, 0, 100, 30, 0, 140));
        assertFalse(SodiumMultiDrawMerge.canMerge(60, 1, 100, 30, 0, 140));
        assertFalse(SodiumMultiDrawMerge.canMerge(60, 0, 100, 30, 2, 140));
        assertFalse(SodiumMultiDrawMerge.canMerge(60, 0, 100, 30, 0, 141));
        assertFalse(SodiumMultiDrawMerge.canMerge(61, 0, 100, 30, 0, 140));
    }

    @Test
    void neverExceedsMinimumSharedQuadIndexCapacity() {
        long max = SodiumMultiDrawMerge.MAX_SHARED_INDEX_ELEMENTS;
        assertTrue(SodiumMultiDrawMerge.canMerge(max - 6, 0, 100, 6, 0,
                100 + ((max - 6) / 6) * 4));
        assertFalse(SodiumMultiDrawMerge.canMerge(max, 0, 100, 6, 0,
                100 + (max / 6) * 4));
    }
}
