package com.metallum.client.metal.render.bridge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts actual Java-to-native downcalls while hot-path telemetry is enabled.
 *
 * <p>The counter is installed on the {@link MethodHandle} returned by the FFM
 * linker, rather than being inferred from higher-level setters. This keeps the
 * production-disabled path untouched and counts packet calls, legacy calls,
 * resource operations, synchronization, and presentation through the same
 * mechanism.</p>
 */
public final class MetalFfmCallTelemetry {
    private static final boolean ENABLED = Boolean.getBoolean("metallum.hotpath.telemetry");
    private static final LongAdder DOWNCALLS = new LongAdder();
    private static final MethodHandle RECORD_CALL = recordCallHandle();

    private MetalFfmCallTelemetry() {
    }

    static MethodHandle instrumentDowncall(final MethodHandle target) {
        return instrumentDowncall(target, ENABLED);
    }

    static MethodHandle instrumentDowncall(final MethodHandle target, final boolean enabled) {
        Objects.requireNonNull(target, "target");
        if (!enabled) {
            return target;
        }
        // A void, zero-argument combiner executes before the target while
        // preserving the target's complete parameter and return signature.
        return MethodHandles.foldArguments(target, RECORD_CALL);
    }

    public static Snapshot snapshot() {
        return new Snapshot(DOWNCALLS.sum());
    }

    public static void reset() {
        DOWNCALLS.reset();
    }

    private static void recordCall() {
        DOWNCALLS.increment();
    }

    private static MethodHandle recordCallHandle() {
        try {
            return MethodHandles.lookup().findStatic(
                    MetalFfmCallTelemetry.class,
                    "recordCall",
                    MethodType.methodType(void.class)
            );
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    public record Snapshot(long downcalls) {
        public double perFrame(final int measuredFrames) {
            return measuredFrames <= 0 ? 0.0 : (double) downcalls / measuredFrames;
        }
    }
}
