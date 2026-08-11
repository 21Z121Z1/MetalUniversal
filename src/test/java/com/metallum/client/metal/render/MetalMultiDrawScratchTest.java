package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MetalMultiDrawScratchTest {
    @Test
    void deinterleavedArraysUseNativeAbiWidths() {
        MetalMultiDrawScratch scratch = new MetalMultiDrawScratch();
        scratch.ensureCapacity(20);
        scratch.put(0, 24L, 36, -4);
        scratch.put(19, 128L, 12, 7);

        assertEquals(24L, scratch.firstIndexOffsets().get(ValueLayout.JAVA_LONG, 0L));
        assertEquals(36, scratch.indexCounts().get(ValueLayout.JAVA_INT, 0L));
        assertEquals(-4, scratch.vertexOffsets().get(ValueLayout.JAVA_INT, 0L));

        assertEquals(128L, scratch.firstIndexOffsets().get(ValueLayout.JAVA_LONG, 19L * Long.BYTES));
        assertEquals(12, scratch.indexCounts().get(ValueLayout.JAVA_INT, 19L * Integer.BYTES));
        assertEquals(7, scratch.vertexOffsets().get(ValueLayout.JAVA_INT, 19L * Integer.BYTES));
    }
}
