package com.metallum.client.metal.render;

import java.util.concurrent.atomic.LongAdder;

/**
 * Low-overhead counters for Iris-on-Metal fast paths.
 *
 * <p>The optimization paths are always active, while accounting is opt-in via
 * {@code -Dmetallum.iris.performanceCounters=true}. Keeping the branch-free
 * fast path independent from diagnostics avoids turning measurement into a
 * production CPU cost.</p>
 */
public final class IrisMetalPerformanceCounters {
    private static final boolean ENABLED =
            Boolean.getBoolean("metallum.iris.performanceCounters");

    private static final LongAdder descriptorBindingsSkipped = new LongAdder();
    private static final LongAdder uniformUploadsSkipped = new LongAdder();
    private static final LongAdder uniformUploadBytesSkipped = new LongAdder();
    private static final LongAdder mipmapGenerationsSkipped = new LongAdder();
    private static final LongAdder bindingClassificationCacheHits = new LongAdder();

    private IrisMetalPerformanceCounters() {
    }

    public static void recordDescriptorBindingSkipped() {
        if (ENABLED) {
            descriptorBindingsSkipped.increment();
        }
    }

    public static void recordUniformUploadSkipped(final long bytes) {
        if (ENABLED) {
            uniformUploadsSkipped.increment();
            uniformUploadBytesSkipped.add(Math.max(0L, bytes));
        }
    }

    public static void recordMipmapGenerationSkipped() {
        if (ENABLED) {
            mipmapGenerationsSkipped.increment();
        }
    }

    public static void recordBindingClassificationCacheHit() {
        if (ENABLED) {
            bindingClassificationCacheHits.increment();
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                descriptorBindingsSkipped.sum(),
                uniformUploadsSkipped.sum(),
                uniformUploadBytesSkipped.sum(),
                mipmapGenerationsSkipped.sum(),
                bindingClassificationCacheHits.sum()
        );
    }

    public static void reset() {
        descriptorBindingsSkipped.reset();
        uniformUploadsSkipped.reset();
        uniformUploadBytesSkipped.reset();
        mipmapGenerationsSkipped.reset();
        bindingClassificationCacheHits.reset();
    }

    public record Snapshot(
            long descriptorBindingsSkipped,
            long uniformUploadsSkipped,
            long uniformUploadBytesSkipped,
            long mipmapGenerationsSkipped,
            long bindingClassificationCacheHits
    ) {
    }
}
