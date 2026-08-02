package com.metallum.client.performance;

/**
 * Immutable timing contract for one display opportunity.
 *
 * <p>{@link Source#ESTIMATED_CADENCE} is deliberately distinct from a real
 * {@code CAMetalDisplayLink} callback. Consumers must not report an estimated
 * deadline as display-link evidence.</p>
 */
public record DisplayDeadlineSnapshot(
        long sequence,
        long targetPresentationNanos,
        long commitDeadlineNanos,
        long framePeriodNanos,
        Source source
) {
    public DisplayDeadlineSnapshot {
        sequence = Math.max(0L, sequence);
        targetPresentationNanos = Math.max(0L, targetPresentationNanos);
        commitDeadlineNanos = Math.max(0L, commitDeadlineNanos);
        framePeriodNanos = Math.max(0L, framePeriodNanos);
        source = source == null ? Source.UNAVAILABLE : source;
        if (commitDeadlineNanos > targetPresentationNanos && targetPresentationNanos > 0L) {
            throw new IllegalArgumentException("commit deadline must not follow presentation target");
        }
    }

    public static DisplayDeadlineSnapshot unavailable() {
        return new DisplayDeadlineSnapshot(0L, 0L, 0L, 0L, Source.UNAVAILABLE);
    }

    public boolean available() {
        return source != Source.UNAVAILABLE
                && targetPresentationNanos > 0L
                && commitDeadlineNanos > 0L
                && framePeriodNanos > 0L;
    }

    public long nanosUntilCommitDeadline(final long nowNanos) {
        return available() ? Math.max(0L, commitDeadlineNanos - nowNanos) : 0L;
    }

    public boolean missedAt(final long timestampNanos) {
        return available() && timestampNanos > commitDeadlineNanos;
    }

    public enum Source {
        UNAVAILABLE,
        ESTIMATED_CADENCE,
        METAL_DISPLAY_LINK
    }
}
