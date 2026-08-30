package com.metallum.client.metal.render;

import java.util.List;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.LongAdder;

/**
 * Sample-window telemetry for actual render and compute pipeline-state
 * creation requests made by the Java renderer.
 *
 * <p>The validation driver resets this state after warmup. Any entry reported
 * afterwards is therefore a late PSO creation, not a cache lookup. Identities
 * are bounded and sorted so a failed zero-runtime-compile gate remains
 * diagnosable without allowing unbounded validation memory.</p>
 */
public final class MetalPipelineCompilationTelemetry {
    private static final int MAX_IDENTITIES = 32;
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
