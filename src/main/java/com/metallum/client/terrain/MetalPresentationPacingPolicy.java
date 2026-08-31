package com.metallum.client.terrain;

/**
 * Conservative policy seam for ordinary (non-frame-generation) presentation.
 *
 * <p>The native bridge already reports drawable/present timestamps. This
 * class only decides whether a future display-link scheduler may consume those
 * values; it does not call CAMetalDisplayLink and therefore cannot change
 * pacing by accident. The switch is opt-in and invalid telemetry always
 * returns the legacy queue policy.</p>
 */
public final class MetalPresentationPacingPolicy {
    public static final String ENABLE_PROPERTY = "metallum.presentation.displayLinkPacing";
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty(ENABLE_PROPERTY, "false")
    );
    private static final int MIN_FRAMES_IN_FLIGHT = 2;
    private static final int MAX_FRAMES_IN_FLIGHT = 3;

    private MetalPresentationPacingPolicy() {
    }

    public static Decision decide(
            final PresentationPacingSnapshot snapshot,
            final int requestedFramesInFlight
    ) {
        return decide(snapshot, requestedFramesInFlight, ENABLED);
    }

    /** Pure overload used by focused tests and offline admission tooling. */
    public static Decision decide(
            final PresentationPacingSnapshot snapshot,
            final int requestedFramesInFlight,
            final boolean featureEnabled
    ) {
        if (!featureEnabled) {
            return disabled("feature-disabled", requestedFramesInFlight);
        }
        if (snapshot == null || snapshot.refreshRateHz() <= 0) {
            return disabled("refresh-rate-unavailable", requestedFramesInFlight);
        }
        PresentationPacingSnapshot.Value target = snapshot.targetPresentInterval();
        if (target == null || !target.available() || target.value() <= 0L) {
            return disabled("target-interval-unavailable", requestedFramesInFlight);
        }
        if (requestedFramesInFlight < MIN_FRAMES_IN_FLIGHT
                || requestedFramesInFlight > MAX_FRAMES_IN_FLIGHT) {
            return disabled("invalid-queue-depth", requestedFramesInFlight);
        }
        // Keep the caller's queue depth bounded. A two-buffer queue is the
        // latency-oriented option; three remains available for throughput
        // experiments and is never selected implicitly by this policy.
        return new Decision(
                true,
                snapshot.refreshRateHz(),
                target.value(),
                requestedFramesInFlight,
                "admitted-target-timing"
        );
    }

    private static Decision disabled(final String reason, final int requestedFramesInFlight) {
        return new Decision(
                false,
                PresentationPacingSnapshot.UNAVAILABLE_REFRESH_RATE_HZ,
                PresentationPacingSnapshot.UNAVAILABLE_VALUE,
                clampQueueDepth(requestedFramesInFlight),
                reason
        );
    }

    private static int clampQueueDepth(final int requestedFramesInFlight) {
        if (requestedFramesInFlight < MIN_FRAMES_IN_FLIGHT) return MIN_FRAMES_IN_FLIGHT;
        return Math.min(requestedFramesInFlight, MAX_FRAMES_IN_FLIGHT);
    }

    public record Decision(
            boolean enabled,
            int refreshRateHz,
            long targetIntervalNanos,
            int framesInFlight,
            String reason
    ) {
        public Decision {
            if (framesInFlight < MIN_FRAMES_IN_FLIGHT || framesInFlight > MAX_FRAMES_IN_FLIGHT) {
                throw new IllegalArgumentException("framesInFlight must be bounded to 2..3");
            }
            if (enabled && (refreshRateHz <= 0 || targetIntervalNanos <= 0L)) {
                throw new IllegalArgumentException("enabled pacing requires a valid display target");
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("pacing decision reason must not be blank");
            }
        }
    }
}
