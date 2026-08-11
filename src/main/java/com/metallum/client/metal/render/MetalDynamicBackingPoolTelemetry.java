package com.metallum.client.metal.render;

import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/** Optional counters for the byte-budgeted dynamic backing pool. */
public final class MetalDynamicBackingPoolTelemetry {
    private static final boolean ENABLED = Boolean.getBoolean("metallum.hotpath.telemetry");
    private static final LongAdder trims = new LongAdder();
    private static final LongAdder releasedBytes = new LongAdder();
    private static final LongAdder releasedHandles = new LongAdder();
    private static final LongAdder removedBuckets = new LongAdder();
    private static final LongAccumulator peakObservedBytes = new LongAccumulator(Long::max, 0L);

    private MetalDynamicBackingPoolTelemetry() {
    }

    public static void record(final MetalDynamicBackingPoolBudget.TrimResult result) {
        if (!ENABLED) {
            return;
        }
        trims.increment();
        releasedBytes.add(result.releasedBytes());
        releasedHandles.add(result.releasedHandles());
        removedBuckets.add(result.removedBuckets());
        peakObservedBytes.accumulate(result.beforeBytes());
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                trims.sum(),
                releasedBytes.sum(),
                releasedHandles.sum(),
                removedBuckets.sum(),
                peakObservedBytes.get()
        );
    }

    public static void reset() {
        trims.reset();
        releasedBytes.reset();
        releasedHandles.reset();
        removedBuckets.reset();
        peakObservedBytes.reset();
    }

    public record Snapshot(
            long trims,
            long releasedBytes,
            long releasedHandles,
            long removedBuckets,
            long peakObservedBytes
    ) {
    }
}
