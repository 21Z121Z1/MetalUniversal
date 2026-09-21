package com.metallum.client.metal.render;

import java.util.concurrent.atomic.LongAdder;

/** Bounded counters for archive identity, lookup, harvest and flush outcomes. */
public final class MetalPsoArchiveTelemetry {
    private static final LongAdder OPEN_SUCCESSES = new LongAdder();
    private static final LongAdder OPEN_FAILURES = new LongAdder();
    private static final LongAdder FLUSH_SUCCESSES = new LongAdder();
    private static final LongAdder FLUSH_FAILURES = new LongAdder();
    private static final LongAdder PREWARM_SUBMITS = new LongAdder();
    private static final LongAdder PREWARM_FAILURES = new LongAdder();

    private MetalPsoArchiveTelemetry() {
    }

    static void recordOpen(final boolean success) {
        if (success) OPEN_SUCCESSES.increment();
        else OPEN_FAILURES.increment();
    }

    static void recordFlush(final boolean success) {
        if (success) FLUSH_SUCCESSES.increment();
        else FLUSH_FAILURES.increment();
    }

    static void recordPrewarmSubmit() {
        PREWARM_SUBMITS.increment();
    }

    static void recordPrewarmFailure() {
        PREWARM_FAILURES.increment();
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                OPEN_SUCCESSES.sum(),
                OPEN_FAILURES.sum(),
                FLUSH_SUCCESSES.sum(),
                FLUSH_FAILURES.sum(),
                PREWARM_SUBMITS.sum(),
                PREWARM_FAILURES.sum()
        );
    }

    public static void reset() {
        OPEN_SUCCESSES.reset();
        OPEN_FAILURES.reset();
        FLUSH_SUCCESSES.reset();
        FLUSH_FAILURES.reset();
        PREWARM_SUBMITS.reset();
        PREWARM_FAILURES.reset();
    }

    public record Snapshot(
            long openSuccesses,
            long openFailures,
            long flushSuccesses,
            long flushFailures,
            long prewarmSubmits,
            long prewarmFailures
    ) {
        public boolean archiveOpened() {
            return openSuccesses > 0L;
        }
    }
}
