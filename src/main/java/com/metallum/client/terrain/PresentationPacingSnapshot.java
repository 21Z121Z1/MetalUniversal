package com.metallum.client.terrain;

import java.util.Objects;

/**
 * Immutable presentation-pacing inputs observed at the Java terrain boundary.
 *
 * <p>This is evidence only.  In particular, a frame interval measured around
 * {@code Minecraft.renderFrame} is not a claim about WindowServer scanout and
 * is kept separate from the measured-present field.  A value can be available
 * as a conservative fallback without being marked as measured.</p>
 */
public record PresentationPacingSnapshot(
        long frameIndex,
        int refreshRateHz,
        Value targetPresentInterval,
        Value measuredPresentInterval,
        Value cpuFrameTime,
        Value gpuFrameTime,
        Value drawableWait,
        Value framesInFlight,
        String provenance,
        String fallbackReason
) {
    public static final long UNAVAILABLE_VALUE = -1L;
    public static final int UNAVAILABLE_REFRESH_RATE_HZ = -1;
    public static final String NANOS_UNIT = "nanoseconds";
    public static final String COUNT_UNIT = "count";
    public static final String JAVA_RUNTIME_PROVENANCE = "java-terrain-runtime-signals";
    public static final String REFRESH_RATE_PROVENANCE = "minecraft.window.refresh-rate";
    public static final String TARGET_FALLBACK_PROVENANCE = "conservative-60hz-fallback";
    public static final String TARGET_FALLBACK_REASON = "display-refresh-source-unavailable";
    public static final String PRESENT_INTERVAL_UNAVAILABLE_REASON =
            "native-presented-timestamp-not-exported-at-java-boundary";
    public static final String DRAWABLE_WAIT_UNAVAILABLE_REASON =
            "native-drawable-wait-duration-not-exported-at-java-boundary";
    public static final String FRAMES_IN_FLIGHT_UNAVAILABLE_REASON =
            "dynamic-in-flight-count-not-exported-at-java-boundary";

    public PresentationPacingSnapshot {
        if (frameIndex < 0L) {
            throw new IllegalArgumentException("frameIndex must be non-negative");
        }
        if (refreshRateHz <= 0) {
            refreshRateHz = UNAVAILABLE_REFRESH_RATE_HZ;
        }
        refreshRateHz = Math.min(refreshRateHz, 1000);
        Objects.requireNonNull(targetPresentInterval, "targetPresentInterval");
        Objects.requireNonNull(measuredPresentInterval, "measuredPresentInterval");
        Objects.requireNonNull(cpuFrameTime, "cpuFrameTime");
        Objects.requireNonNull(gpuFrameTime, "gpuFrameTime");
        Objects.requireNonNull(drawableWait, "drawableWait");
        Objects.requireNonNull(framesInFlight, "framesInFlight");
        provenance = requireText(provenance, "provenance");
        if (fallbackReason != null && fallbackReason.isBlank()) {
            fallbackReason = null;
        }
    }

    /** Builds a snapshot from the sources currently available to Java. */
    public static PresentationPacingSnapshot capture(
            final long frameIndex,
            final int refreshRateHz,
            final long cpuFrameTimeNanos,
            final long gpuFrameTimeNanos
    ) {
        return capture(
                frameIndex,
                refreshRateHz,
                cpuFrameTimeNanos,
                gpuFrameTimeNanos,
                UNAVAILABLE_VALUE,
                UNAVAILABLE_VALUE,
                UNAVAILABLE_VALUE
        );
    }

    /**
     * Builds a snapshot with optional future/native sources.  Negative values
     * are unavailable and remain explicitly unavailable in structured output.
     */
    public static PresentationPacingSnapshot capture(
            final long frameIndex,
            final int refreshRateHz,
            final long cpuFrameTimeNanos,
            final long gpuFrameTimeNanos,
            final long measuredPresentIntervalNanos,
            final long drawableWaitNanos,
            final long framesInFlightCount
    ) {
        boolean refreshAvailable = refreshRateHz > 0;
        int normalizedRefreshRate = refreshAvailable ? Math.min(refreshRateHz, 1000) : UNAVAILABLE_REFRESH_RATE_HZ;
        Value target = refreshAvailable
                ? Value.measuredNanos(
                        Math.max(1L, Math.round(1_000_000_000.0 / normalizedRefreshRate)),
                        REFRESH_RATE_PROVENANCE
                )
                : Value.fallbackNanos(
                        TerrainSchedulingController.TARGET_FRAME_NANOS,
                        TARGET_FALLBACK_PROVENANCE,
                        TARGET_FALLBACK_REASON
                );
        Value measuredPresent = measuredNanosOrUnavailable(
                measuredPresentIntervalNanos,
                "native.presented-time"
        );
        Value cpu = measuredNanosOrUnavailable(cpuFrameTimeNanos, "minecraft.latest-render-frame-interval");
        Value gpu = measuredNanosOrUnavailable(gpuFrameTimeNanos, "metal.latest-completed-command-buffer");
        Value drawableWait = measuredNanosOrUnavailable(drawableWaitNanos, "native.drawable-wait");
        Value framesInFlight = measuredCountOrUnavailable(framesInFlightCount, "native.frames-in-flight");
        String fallbackReason = target.measured() ? null : target.fallbackReason();
        return new PresentationPacingSnapshot(
                frameIndex,
                normalizedRefreshRate,
                target,
                measuredPresent,
                cpu,
                gpu,
                drawableWait,
                framesInFlight,
                JAVA_RUNTIME_PROVENANCE,
                fallbackReason
        );
    }

    public static PresentationPacingSnapshot neutral() {
        return capture(0L, UNAVAILABLE_REFRESH_RATE_HZ, UNAVAILABLE_VALUE, UNAVAILABLE_VALUE);
    }

    public record Value(
            long value,
            String unit,
            boolean available,
            boolean measured,
            String provenance,
            String fallbackReason
    ) {
        public Value {
            unit = requireText(unit, "unit");
            provenance = requireText(provenance, "provenance");
            if (available && value < 0L) {
                throw new IllegalArgumentException("available pacing values must be non-negative");
            }
            if (!available) {
                value = UNAVAILABLE_VALUE;
                measured = false;
                if (fallbackReason == null || fallbackReason.isBlank()) {
                    throw new IllegalArgumentException("unavailable pacing values require a fallback reason");
                }
            } else if (measured && (fallbackReason != null && !fallbackReason.isBlank())) {
                throw new IllegalArgumentException("measured pacing values cannot have a fallback reason");
            } else if (fallbackReason != null && fallbackReason.isBlank()) {
                fallbackReason = null;
            }
        }

        public static Value measuredNanos(final long value, final String provenance) {
            return new Value(value, NANOS_UNIT, true, true, provenance, null);
        }

        public static Value fallbackNanos(
                final long value,
                final String provenance,
                final String reason
        ) {
            return new Value(value, NANOS_UNIT, true, false, provenance, reason);
        }

        public static Value unavailableNanos(final String provenance, final String reason) {
            return new Value(UNAVAILABLE_VALUE, NANOS_UNIT, false, false, provenance, reason);
        }

        public static Value measuredCount(final long value, final String provenance) {
            return new Value(value, COUNT_UNIT, true, true, provenance, null);
        }

        public static Value unavailableCount(final String provenance, final String reason) {
            return new Value(UNAVAILABLE_VALUE, COUNT_UNIT, false, false, provenance, reason);
        }
    }

    private static Value measuredNanosOrUnavailable(final long value, final String provenance) {
        return value > 0L
                ? Value.measuredNanos(value, provenance)
                : Value.unavailableNanos(provenance, unavailableReason(provenance));
    }

    private static Value measuredCountOrUnavailable(final long value, final String provenance) {
        return value >= 0L
                ? Value.measuredCount(value, provenance)
                : Value.unavailableCount(provenance, unavailableReason(provenance));
    }

    private static String unavailableReason(final String provenance) {
        return switch (provenance) {
            case "native.presented-time" -> PRESENT_INTERVAL_UNAVAILABLE_REASON;
            case "native.drawable-wait" -> DRAWABLE_WAIT_UNAVAILABLE_REASON;
            case "native.frames-in-flight" -> FRAMES_IN_FLIGHT_UNAVAILABLE_REASON;
            default -> "source-unavailable-at-java-boundary";
        };
    }

    private static String requireText(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
