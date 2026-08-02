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
    private static final LongAdder uniformUploadsTrimmed = new LongAdder();
    private static final LongAdder uniformUploadBytesTrimmed = new LongAdder();
    private static final LongAdder mipmapGenerationsSkipped = new LongAdder();
    private static final LongAdder textureCopiesSkipped = new LongAdder();
    private static final LongAdder textureCopyBytesSkipped = new LongAdder();
    private static final LongAdder bindingClassificationCacheHits = new LongAdder();
    private static final LongAdder uniformLookupCacheHits = new LongAdder();

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

    public static void recordUniformUploadTrimmed(final long originalBytes, final long writtenBytes) {
        if (ENABLED && writtenBytes < originalBytes) {
            uniformUploadsTrimmed.increment();
            uniformUploadBytesTrimmed.add(Math.max(0L, originalBytes - writtenBytes));
        }
    }

    public static void recordMipmapGenerationSkipped() {
        if (ENABLED) {
            mipmapGenerationsSkipped.increment();
        }
    }

    public static void recordTextureCopySkipped(final long bytes) {
        if (ENABLED) {
            textureCopiesSkipped.increment();
            textureCopyBytesSkipped.add(Math.max(0L, bytes));
        }
    }

    public static void recordBindingClassificationCacheHit() {
        if (ENABLED) {
            bindingClassificationCacheHits.increment();
        }
    }

    public static void recordUniformLookupCacheHit() {
        if (ENABLED) {
            uniformLookupCacheHits.increment();
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                descriptorBindingsSkipped.sum(),
                uniformUploadsSkipped.sum(),
                uniformUploadBytesSkipped.sum(),
                uniformUploadsTrimmed.sum(),
                uniformUploadBytesTrimmed.sum(),
                mipmapGenerationsSkipped.sum(),
                textureCopiesSkipped.sum(),
                textureCopyBytesSkipped.sum(),
                bindingClassificationCacheHits.sum(),
                uniformLookupCacheHits.sum()
        );
    }

    public static void reset() {
        descriptorBindingsSkipped.reset();
        uniformUploadsSkipped.reset();
        uniformUploadBytesSkipped.reset();
        uniformUploadsTrimmed.reset();
        uniformUploadBytesTrimmed.reset();
        mipmapGenerationsSkipped.reset();
        textureCopiesSkipped.reset();
        textureCopyBytesSkipped.reset();
        bindingClassificationCacheHits.reset();
        uniformLookupCacheHits.reset();
    }

    public record Snapshot(
            long descriptorBindingsSkipped,
            long uniformUploadsSkipped,
            long uniformUploadBytesSkipped,
            long uniformUploadsTrimmed,
            long uniformUploadBytesTrimmed,
            long mipmapGenerationsSkipped,
            long textureCopiesSkipped,
            long textureCopyBytesSkipped,
            long bindingClassificationCacheHits,
            long uniformLookupCacheHits
    ) {
    }
}
