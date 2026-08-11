package com.metallum.client.metal.render;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.lang.foreign.MemorySegment;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Byte- and bucket-budget policy for GPU-safe recycled dynamic buffer backings.
 *
 * <p>Callers must invoke this only after the deferred destruction queue has
 * proved the pooled handles are no longer referenced by in-flight GPU work.</p>
 */
public final class MetalDynamicBackingPoolBudget {
    private MetalDynamicBackingPoolBudget() {
    }

    public static TrimResult trim(
            final Long2ObjectOpenHashMap<ArrayDeque<MemorySegment>> pool,
            final long maxRetainedBytes,
            final int maxBuckets,
            final Consumer<MemorySegment> releaser
    ) {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(releaser, "releaser");
        if (maxRetainedBytes < 0L) {
            throw new IllegalArgumentException("maxRetainedBytes must be non-negative");
        }
        if (maxBuckets < 0) {
            throw new IllegalArgumentException("maxBuckets must be non-negative");
        }

        removeEmptyBuckets(pool);
        long beforeBytes = retainedBytes(pool);
        long retainedBytes = beforeBytes;
        int releasedHandles = 0;
        int removedBuckets = 0;

        while (!pool.isEmpty() && pool.size() > maxBuckets) {
            long key = largestNonEmptyBucketKey(pool);
            ArrayDeque<MemorySegment> bucket = pool.get(key);
            if (bucket == null) {
                break;
            }
            long size = allocationSize(key);
            while (!bucket.isEmpty()) {
                MemorySegment handle = bucket.peek();
                releaser.accept(handle);
                bucket.pop();
                releasedHandles++;
                retainedBytes = subtractSaturated(retainedBytes, size);
            }
            pool.remove(key);
            removedBuckets++;
        }

        while (!pool.isEmpty() && retainedBytes > maxRetainedBytes) {
            long key = largestNonEmptyBucketKey(pool);
            ArrayDeque<MemorySegment> bucket = pool.get(key);
            if (bucket == null || bucket.isEmpty()) {
                pool.remove(key);
                removedBuckets++;
                continue;
            }
            long size = allocationSize(key);
            MemorySegment handle = bucket.peek();
            releaser.accept(handle);
            bucket.pop();
            releasedHandles++;
            retainedBytes = subtractSaturated(retainedBytes, size);
            if (bucket.isEmpty()) {
                pool.remove(key);
                removedBuckets++;
            }
        }

        return new TrimResult(beforeBytes, retainedBytes, releasedHandles, removedBuckets, pool.size());
    }

    public static long retainedBytes(
            final Long2ObjectOpenHashMap<ArrayDeque<MemorySegment>> pool
    ) {
        long total = 0L;
        for (Long2ObjectMap.Entry<ArrayDeque<MemorySegment>> entry : pool.long2ObjectEntrySet()) {
            ArrayDeque<MemorySegment> bucket = entry.getValue();
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            long size = allocationSize(entry.getLongKey());
            total = addProductSaturated(total, size, bucket.size());
        }
        return total;
    }

    public static long allocationSize(final long poolKey) {
        return poolKey >>> 12;
    }

    private static void removeEmptyBuckets(
            final Long2ObjectOpenHashMap<ArrayDeque<MemorySegment>> pool
    ) {
        var iterator = pool.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            ArrayDeque<MemorySegment> bucket = iterator.next().getValue();
            if (bucket == null || bucket.isEmpty()) {
                iterator.remove();
            }
        }
    }

    private static long largestNonEmptyBucketKey(
            final Long2ObjectOpenHashMap<ArrayDeque<MemorySegment>> pool
    ) {
        long selectedKey = 0L;
        long selectedSize = -1L;
        for (Long2ObjectMap.Entry<ArrayDeque<MemorySegment>> entry : pool.long2ObjectEntrySet()) {
            ArrayDeque<MemorySegment> bucket = entry.getValue();
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            long size = allocationSize(entry.getLongKey());
            if (size > selectedSize) {
                selectedSize = size;
                selectedKey = entry.getLongKey();
            }
        }
        return selectedKey;
    }

    private static long addProductSaturated(final long current, final long size, final int count) {
        if (size <= 0L || count <= 0) {
            return current;
        }
        if (size > Long.MAX_VALUE / count) {
            return Long.MAX_VALUE;
        }
        long increment = size * count;
        return current > Long.MAX_VALUE - increment ? Long.MAX_VALUE : current + increment;
    }

    private static long subtractSaturated(final long current, final long decrement) {
        if (decrement <= 0L) {
            return current;
        }
        return current <= decrement ? 0L : current - decrement;
    }

    public record TrimResult(
            long beforeBytes,
            long afterBytes,
            int releasedHandles,
            int removedBuckets,
            int retainedBuckets
    ) {
        public long releasedBytes() {
            return Math.max(0L, beforeBytes - afterBytes);
        }
    }
}
