package com.metallum.mixin.render;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Per-render-thread native arrays for deinterleaving Mojang's compact
 * {@code [firstIndex, indexCount, baseVertex]} records into the existing Metal
 * multi-draw ABI. Capacity grows geometrically and is then reused without
 * per-frame native allocation.
 */
final class MetalMultiDrawScratch {
    static final ThreadLocal<MetalMultiDrawScratch> CURRENT =
            ThreadLocal.withInitial(MetalMultiDrawScratch::new);

    private Arena arena;
    private MemorySegment firstIndexOffsets = MemorySegment.NULL;
    private MemorySegment indexCounts = MemorySegment.NULL;
    private MemorySegment vertexOffsets = MemorySegment.NULL;
    private int capacity;

    void ensureCapacity(final int required) {
        if (required <= capacity) {
            return;
        }
        int next = Math.max(16, capacity);
        while (next < required) {
            next = Math.multiplyExact(next, 2);
        }
        if (arena != null) {
            arena.close();
        }
        arena = Arena.ofConfined();
        firstIndexOffsets = arena.allocate((long) next * Long.BYTES, Long.BYTES);
        indexCounts = arena.allocate((long) next * Integer.BYTES, Integer.BYTES);
        vertexOffsets = arena.allocate((long) next * Integer.BYTES, Integer.BYTES);
        capacity = next;
    }

    void put(final int index, final long firstIndexOffset, final int indexCount, final int vertexOffset) {
        if (index < 0 || index >= capacity) {
            throw new IndexOutOfBoundsException("multi-draw scratch index " + index + " / " + capacity);
        }
        firstIndexOffsets.set(ValueLayout.JAVA_LONG, (long) index * Long.BYTES, firstIndexOffset);
        indexCounts.set(ValueLayout.JAVA_INT, (long) index * Integer.BYTES, indexCount);
        vertexOffsets.set(ValueLayout.JAVA_INT, (long) index * Integer.BYTES, vertexOffset);
    }

    MemorySegment firstIndexOffsets() {
        return firstIndexOffsets;
    }

    MemorySegment indexCounts() {
        return indexCounts;
    }

    MemorySegment vertexOffsets() {
        return vertexOffsets;
    }
}
