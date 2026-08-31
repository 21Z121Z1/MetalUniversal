package com.metallum.client.metal.render;

import java.util.List;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded sample-window telemetry for actual render/compute PSO creation.
 *
 * <p>A cache lookup never increments these counters; an entry means the
 * native compiler path was requested.  Validation resets the window after
 * warm-up, so any later entry is a first-use compile or a late variant.  The
 * identity set is bounded to keep a malformed shader pack from growing the
 * validation process without limit.</p>
 */
public final class MetalPipelineCompilationTelemetry {
    private static final int MAX_IDENTITIES = 64;
    private static final boolean ENABLED = Boolean.getBoolean("metallum.hotpath.telemetry");
    private static final LongAdder RENDER_ATTEMPTS = new LongAdder();
    private static final LongAdder COMPUTE_ATTEMPTS = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();
    private static final ConcurrentSkipListSet<String> IDENTITIES = new ConcurrentSkipListSet<>();

    private MetalPipelineCompilationTelemetry() {
    }

    static void recordRender(final String identity, final boolean succeeded) {
        record(identity, true, succeeded, ENABLED);
    }

    static void recordCompute(final String identity, final boolean succeeded) {
        record(identity, false, succeeded, ENABLED);
    }

    static void record(
            final String identity,
            final boolean render,
            final boolean succeeded,
            final boolean enabled
    ) {
        if (!enabled) {
            return;
        }
        if (render) {
            RENDER_ATTEMPTS.increment();
        } else {
            COMPUTE_ATTEMPTS.increment();
        }
        if (!succeeded) {
            FAILURES.increment();
        }
        if (identity != null && !identity.isBlank()) {
            IDENTITIES.add(identity);
            while (IDENTITIES.size() > MAX_IDENTITIES) {
                IDENTITIES.pollLast();
            }
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                RENDER_ATTEMPTS.sum(),
                COMPUTE_ATTEMPTS.sum(),
                FAILURES.sum(),
                List.copyOf(IDENTITIES)
        );
    }

    public static void reset() {
        RENDER_ATTEMPTS.reset();
        COMPUTE_ATTEMPTS.reset();
        FAILURES.reset();
        IDENTITIES.clear();
    }

    public record Snapshot(
            long renderAttempts,
            long computeAttempts,
            long failures,
            List<String> identities
    ) {
        public long attempts() {
            return renderAttempts + computeAttempts;
        }
    }
}
