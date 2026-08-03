package com.metallum.client.metal.render;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalDynamicBackingPoolBudgetTest {
    @Test
    void trimsLargestAllocationsUntilByteBudgetHolds() {
        Long2ObjectOpenHashMap<ArrayDeque<MemorySegment>> pool = new Long2ObjectOpenHashMap<>();
        pool.put(key(4 * 1024L, 1L), bucket(1L, 2L));
        pool.put(key(1024 * 1024L, 1L), bucket(3L, 4L));
        List<MemorySegment> released = new ArrayList<>();

        MetalDynamicBackingPoolBudget.TrimResult result = MetalDynamicBackingPoolBudget.trim(
                pool,
                8 * 1024L,
                8,
                released::add
        );

        assertEquals(2L * 1024L * 1024L + 8L * 1024L, result.beforeBytes());
        assertEquals(8L * 1024L, result.afterBytes());
        assertEquals(2, result.releasedHandles());
        assertEquals(1, result.retainedBuckets());
        assertEquals(2, released.size());
        assertTrue(pool.containsKey(key(4 * 1024L, 1L)));
    }

    @Test
    void bucketBudgetEvictsWholeLargestBucket() {
        Long2ObjectOpenHashMap<ArrayDeque<MemorySegment>> pool = new Long2ObjectOpenHashMap<>();
        pool.put(key(4 * 1024L, 0L), bucket(10L));
        pool.put(key(8 * 1024L, 0L), bucket(11L, 12L));
        pool.put(key(16 * 1024L, 0L), bucket(13L));
        List<MemorySegment> released = new ArrayList<>();

        MetalDynamicBackingPoolBudget.TrimResult result = MetalDynamicBackingPoolBudget.trim(
                pool,
                Long.MAX_VALUE,
                2,
                released::add
        );

        assertEquals(2, result.retainedBuckets());
        assertEquals(1, result.releasedHandles());
        assertEquals(16L * 1024L, result.releasedBytes());
        assertTrue(!pool.containsKey(key(16 * 1024L, 0L)));
    }

    @Test
    void poolKeyDecodesAllocationSizeWithoutResourceOptions() {
        assertEquals(65_536L, MetalDynamicBackingPoolBudget.allocationSize(key(65_536L, 0xABCL)));
    }

    private static long key(final long size, final long resourceOptions) {
        return (size << 12) | (resourceOptions & 0xFFFL);
    }

    private static ArrayDeque<MemorySegment> bucket(final long... addresses) {
        ArrayDeque<MemorySegment> result = new ArrayDeque<>();
        for (long address : addresses) {
            result.add(MemorySegment.ofAddress(address));
        }
        return result;
    }
}
