package com.metallum.client.metal.render;

import java.util.concurrent.atomic.LongAdder;

/** Optional counters for the frame-local transient arena. */
public final class MetalTransientArenaTelemetry {
    private static final boolean ENABLED = Boolean.getBoolean("metallum.hotpath.telemetry");
    private static final LongAdder wrapperHits = new LongAdder();
    private static final LongAdder wrapperMisses = new LongAdder();
    private static final LongAdder multiUploadCalls = new LongAdder();
    private static final LongAdder multiUploadItems = new LongAdder();

    private MetalTransientArenaTelemetry() {
    }

    static void recordWrapperHit() {
        if (ENABLED) {
            wrapperHits.increment();
        }
    }

    static void recordWrapperMiss() {
        if (ENABLED) {
            wrapperMisses.increment();
        }
    }

    static void recordMultiUpload(final int itemCount) {
        if (ENABLED) {
            multiUploadCalls.increment();
            multiUploadItems.add(Math.max(0, itemCount));
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                wrapperHits.sum(),
                wrapperMisses.sum(),
                multiUploadCalls.sum(),
                multiUploadItems.sum()
        );
    }

    public static void reset() {
        wrapperHits.reset();
        wrapperMisses.reset();
        multiUploadCalls.reset();
        multiUploadItems.reset();
    }

    public record Snapshot(
            long wrapperHits,
            long wrapperMisses,
            long multiUploadCalls,
            long multiUploadItems
    ) {
        public double wrapperReuseRatio() {
            long total = wrapperHits + wrapperMisses;
            return total == 0L ? 0.0 : (double) wrapperHits / total;
        }
    }
}
