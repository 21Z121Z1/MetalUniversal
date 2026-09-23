package com.metallum.client.metal.render.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Validation-only counters for the ordinary Metal presentation lifecycle.
 *
 * <p>The counters are disabled unless {@code metallum.presentation.telemetry=true}
 * is set before the renderer starts. They deliberately observe the existing
 * command-buffer lifecycle instead of introducing a second synchronization path.
 */
@Environment(EnvType.CLIENT)
public final class MetalPresentationTelemetry {
    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("metallum.presentation.telemetry", "false"));
    private static final AtomicLong ENCODE_CALLS = new AtomicLong();
    private static final AtomicLong SUBMITTED = new AtomicLong();
    private static final AtomicLong COMPLETED = new AtomicLong();
    private static final AtomicLong FAILED = new AtomicLong();

    private MetalPresentationTelemetry() {
    }

    static void recordEncodeCall() {
        if (ENABLED) ENCODE_CALLS.incrementAndGet();
    }

    static void recordSubmitted() {
        if (ENABLED) SUBMITTED.incrementAndGet();
    }

    static void recordCompletion(boolean success) {
        if (!ENABLED) return;
        if (success) {
            COMPLETED.incrementAndGet();
        } else {
            FAILED.incrementAndGet();
        }
    }

    public static void reset() {
        if (!ENABLED) return;
        ENCODE_CALLS.set(0L);
        SUBMITTED.set(0L);
        COMPLETED.set(0L);
        FAILED.set(0L);
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                ENABLED,
                ENCODE_CALLS.get(),
                SUBMITTED.get(),
                COMPLETED.get(),
                FAILED.get()
        );
    }

    public record Snapshot(
            boolean enabled,
            long encodeCalls,
            long submitted,
            long completed,
            long failed
    ) {
        public boolean completeAndSuccessful() {
            return enabled
                    && encodeCalls > 0L
                    && submitted == encodeCalls
                    && completed == submitted
                    && failed == 0L;
        }
    }
}
