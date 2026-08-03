package com.metallum.client.metal.render.mtl;

import java.util.concurrent.atomic.LongAdder;

/**
 * Optional counters for the Java-to-FFM encoder hot path.
 *
 * <p>Disabled by default so production builds pay only a predictable static
 * branch. Enable with {@code -Dmetallum.hotpath.telemetry=true}.</p>
 */
public final class MetalHotPathTelemetry {
    static final boolean ENABLED = Boolean.getBoolean("metallum.hotpath.telemetry");

    private static final LongAdder renderForwarded = new LongAdder();
    private static final LongAdder renderSuppressed = new LongAdder();
    private static final LongAdder renderOffsetOnly = new LongAdder();
    private static final LongAdder computeForwarded = new LongAdder();
    private static final LongAdder computeSuppressed = new LongAdder();

    private MetalHotPathTelemetry() {
    }

    static void renderForwarded() {
        if (ENABLED) {
            renderForwarded.increment();
        }
    }

    static void renderSuppressed() {
        if (ENABLED) {
            renderSuppressed.increment();
        }
    }

    static void renderOffsetOnly() {
        if (ENABLED) {
            renderOffsetOnly.increment();
        }
    }

    static void computeForwarded() {
        if (ENABLED) {
            computeForwarded.increment();
        }
    }

    static void computeSuppressed() {
        if (ENABLED) {
            computeSuppressed.increment();
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                renderForwarded.sum(),
                renderSuppressed.sum(),
                renderOffsetOnly.sum(),
                computeForwarded.sum(),
                computeSuppressed.sum()
        );
    }

    public static void reset() {
        renderForwarded.reset();
        renderSuppressed.reset();
        renderOffsetOnly.reset();
        computeForwarded.reset();
        computeSuppressed.reset();
    }

    public record Snapshot(
            long renderForwardedCalls,
            long renderSuppressedCalls,
            long renderOffsetOnlyCalls,
            long computeForwardedCalls,
            long computeSuppressedCalls
    ) {
        public long totalSuppressedFfmCalls() {
            return renderSuppressedCalls + computeSuppressedCalls;
        }

        public double renderSuppressionRatio() {
            long total = renderForwardedCalls + renderSuppressedCalls;
            return total == 0L ? 0.0 : (double) renderSuppressedCalls / total;
        }

        public double computeSuppressionRatio() {
            long total = computeForwardedCalls + computeSuppressedCalls;
            return total == 0L ? 0.0 : (double) computeSuppressedCalls / total;
        }
    }
}
