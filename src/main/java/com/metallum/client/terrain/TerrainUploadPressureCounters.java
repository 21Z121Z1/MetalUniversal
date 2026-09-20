package com.metallum.client.terrain;

/**
 * Fixed-space counters for the T3a terrain upload-pressure diagnostic.
 *
 * <p>The counters describe CPU-side attempts and calls only. In particular,
 * {@code requestedBytesIncludingRetries} is the sum of bytes requested by every staging
 * attempt, so a retry is intentionally counted again; it is not a measurement of bytes that
 * reached the GPU. Likewise, upload CPU time ends when the upload call returns and does not
 * imply GPU completion.</p>
 *
 * <p>Recording and snapshotting use one short monitor. This keeps snapshots strongly consistent
 * while retaining a fixed amount of state and avoiding allocation on the recording path. A
 * snapshot is therefore a point-in-time view at the monitor boundary, not a pause of any work
 * outside this counter.</p>
 */
public final class TerrainUploadPressureCounters {
    private long attempts;
    private long successes;
    private long failures;
    private long exceptions;
    private long requestedBytesIncludingRetries;
    private long attemptCpuNanos;
    private long failedAttemptCpuNanos;
    private long copyLockAcquisitions;
    private long waitNanos;
    private long maxWaitNanos;
    private long uploadCalls;
    private long uploadExceptions;
    private long uploadCpuNanos;

    /**
     * Records one staging attempt.
     *
     * <p>An attempt that does not complete normally is classified as an exception regardless of
     * the supplied success flag. For a normally completed attempt, {@code succeeded} selects the
     * success or failure bucket. This preserves the invariant that attempts equal successes plus
     * failures plus exceptions.</p>
     *
     * @param requestedBytes bytes requested for this attempt; retries count again
     * @param elapsedNanos CPU-side duration of this attempt
     * @param succeeded whether a normally completed attempt succeeded
     * @param completedNormally whether the attempt returned normally
     */
    public synchronized void recordStagingAttempt(
            final long requestedBytes,
            final long elapsedNanos,
            final boolean succeeded,
            final boolean completedNormally
    ) {
        requireNonNegative("requestedBytes", requestedBytes);
        requireNonNegative("elapsedNanos", elapsedNanos);

        attempts = saturatedIncrement(attempts);
        requestedBytesIncludingRetries = saturatedAdd(requestedBytesIncludingRetries, requestedBytes);
        attemptCpuNanos = saturatedAdd(attemptCpuNanos, elapsedNanos);

        if (!completedNormally) {
            exceptions = saturatedIncrement(exceptions);
            failedAttemptCpuNanos = saturatedAdd(failedAttemptCpuNanos, elapsedNanos);
        } else if (succeeded) {
            successes = saturatedIncrement(successes);
        } else {
            failures = saturatedIncrement(failures);
            failedAttemptCpuNanos = saturatedAdd(failedAttemptCpuNanos, elapsedNanos);
        }
    }

    /**
     * Records acquisition of the staging copy lock and the CPU time spent waiting to acquire it.
     */
    public synchronized void recordCopyLockWait(final long elapsedNanos) {
        requireNonNegative("elapsedNanos", elapsedNanos);
        copyLockAcquisitions = saturatedIncrement(copyLockAcquisitions);
        waitNanos = saturatedAdd(waitNanos, elapsedNanos);
        if (elapsedNanos > maxWaitNanos) {
            maxWaitNanos = elapsedNanos;
        }
    }

    /**
     * Records one CPU-side upload call. A non-normal return is an upload exception; the elapsed
     * time is included in upload CPU time in either case.
     */
    public synchronized void recordUploadCall(final long elapsedNanos, final boolean completedNormally) {
        requireNonNegative("elapsedNanos", elapsedNanos);
        uploadCalls = saturatedIncrement(uploadCalls);
        uploadCpuNanos = saturatedAdd(uploadCpuNanos, elapsedNanos);
        if (!completedNormally) {
            uploadExceptions = saturatedIncrement(uploadExceptions);
        }
    }

    /**
     * Returns all counters at one monitor boundary. The result is immutable and its values never
     * change after construction.
     */
    public synchronized Snapshot snapshot() {
        return new Snapshot(
                attempts,
                successes,
                failures,
                exceptions,
                requestedBytesIncludingRetries,
                attemptCpuNanos,
                failedAttemptCpuNanos,
                copyLockAcquisitions,
                waitNanos,
                maxWaitNanos,
                uploadCalls,
                uploadExceptions,
                uploadCpuNanos
        );
    }

    /** Immutable point-in-time diagnostic values. */
    public record Snapshot(
            long attempts,
            long successes,
            long failures,
            long exceptions,
            long requestedBytesIncludingRetries,
            long attemptCpuNanos,
            long failedAttemptCpuNanos,
            long copyLockAcquisitions,
            long waitNanos,
            long maxWaitNanos,
            long uploadCalls,
            long uploadExceptions,
            long uploadCpuNanos
    ) {
        public Snapshot {
            requireNonNegative("attempts", attempts);
            requireNonNegative("successes", successes);
            requireNonNegative("failures", failures);
            requireNonNegative("exceptions", exceptions);
            requireNonNegative("requestedBytesIncludingRetries", requestedBytesIncludingRetries);
            requireNonNegative("attemptCpuNanos", attemptCpuNanos);
            requireNonNegative("failedAttemptCpuNanos", failedAttemptCpuNanos);
            requireNonNegative("copyLockAcquisitions", copyLockAcquisitions);
            requireNonNegative("waitNanos", waitNanos);
            requireNonNegative("maxWaitNanos", maxWaitNanos);
            requireNonNegative("uploadCalls", uploadCalls);
            requireNonNegative("uploadExceptions", uploadExceptions);
            requireNonNegative("uploadCpuNanos", uploadCpuNanos);
        }

        /** Explicitly named alias for callers that keep multiple wait metrics together. */
        public long copyLockWaitNanos() {
            return waitNanos;
        }

        /** Explicitly named alias for callers that keep multiple wait metrics together. */
        public long copyLockMaxWaitNanos() {
            return maxWaitNanos;
        }
    }

    private static void requireNonNegative(final String name, final long value) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative: " + value);
        }
    }

    private static long saturatedIncrement(final long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private static long saturatedAdd(final long left, final long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
