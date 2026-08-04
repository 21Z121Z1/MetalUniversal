package com.metallum.client.metal.render;

import java.util.concurrent.atomic.LongAdder;

/**
 * Telemetry for the production render argument-buffer path.
 *
 * <p>The previous implementation mirrored setter calls in a weak-map snapshot
 * without executing from that snapshot. The actual ownership now lives in the
 * compiled pipeline layout and frame-local transient argument buffers; this
 * class intentionally contains counters only.</p>
 */
public final class IrisMetalArgumentBindingRuntime {
    private static final LongAdder LAYOUTS = new LongAdder();
    private static final LongAdder UPDATES = new LongAdder();
    private static final LongAdder ENCODED = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();

    private IrisMetalArgumentBindingRuntime() {
    }

    public static boolean enabled() {
        return MetalCrossShaderCompiler.ARGUMENT_BUFFERS_ENABLED;
    }

    static void recordLayout() {
        LAYOUTS.increment();
    }

    static void recordEncodedSnapshot(final int entries) {
        if (entries <= 0) {
            throw new IllegalArgumentException("Argument snapshot must contain entries");
        }
        UPDATES.add(entries);
        ENCODED.increment();
    }

    static void recordFailure() {
        FAILURES.increment();
    }

    public static Stats stats() {
        return new Stats(LAYOUTS.sum(), UPDATES.sum(), ENCODED.sum(), FAILURES.sum());
    }

    public static void resetStats() {
        LAYOUTS.reset();
        UPDATES.reset();
        ENCODED.reset();
        FAILURES.reset();
    }

    public record Stats(long layouts, long updates, long encodedSnapshots, long failures) {
    }
}
