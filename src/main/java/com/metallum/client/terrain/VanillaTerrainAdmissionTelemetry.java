package com.metallum.client.terrain;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Low-cost aggregate snapshot for the opt-in vanilla T1a admission layer.
 *
 * <p>This is diagnostic evidence only until a dedicated acceptance schema is added. It never
 * drives scheduling decisions. Queue mutation publishes immutable samples after it has made a
 * decision, allowing validation runs to inspect backlog/defer/fail-open behavior without parsing
 * logs or touching Minecraft queue internals.</p>
 */
public final class VanillaTerrainAdmissionTelemetry {
    public record Snapshot(
            long sampleNanos,
            int vanillaQueuedTasks,
            int logicalQueuedTasks,
            int deferredQueuedTasks,
            long currentOldestDeferredAgeNanos,
            long maxObservedLogicalQueue,
            long maxObservedDeferredQueue,
            long maxObservedDeferredAgeNanos,
            BoundedTerrainTaskAdmission.Snapshot admission
    ) {
        public Snapshot {
            if (sampleNanos < 0L
                    || vanillaQueuedTasks < 0
                    || logicalQueuedTasks < 0
                    || deferredQueuedTasks < 0
                    || currentOldestDeferredAgeNanos < 0L
                    || maxObservedLogicalQueue < 0L
                    || maxObservedDeferredQueue < 0L
                    || maxObservedDeferredAgeNanos < 0L) {
                throw new IllegalArgumentException("terrain admission telemetry values must be non-negative");
            }
        }

        static Snapshot empty() {
            return new Snapshot(
                    0L,
                    0,
                    0,
                    0,
                    0L,
                    0L,
                    0L,
                    0L,
                    new BoundedTerrainTaskAdmission.Snapshot(
                            false, false, false, 0, 0, 0,
                            0L, 0L, 0L, 0L, 0L, 0L,
                            BoundedTerrainTaskAdmission.FailOpenReason.NONE,
                            0L, 0L, 0L,
                            0L, 0, 0L
                    )
            );
        }
    }

    private static final AtomicReference<Snapshot> LAST = new AtomicReference<>(Snapshot.empty());
    private static final AtomicLong MAX_LOGICAL_QUEUE = new AtomicLong();
    private static final AtomicLong MAX_DEFERRED_QUEUE = new AtomicLong();
    private static final AtomicLong MAX_DEFERRED_AGE = new AtomicLong();

    private VanillaTerrainAdmissionTelemetry() {
    }

    public static void publish(
            final BoundedTerrainTaskAdmission<?> admission,
            final int vanillaQueuedTasks,
            final long nowNanos
    ) {
        if (admission == null || vanillaQueuedTasks < 0 || nowNanos < 0L) {
            return;
        }
        int deferred = admission.deferredSize();
        long logicalLong = (long)vanillaQueuedTasks + deferred;
        int logical = logicalLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)logicalLong;
        long oldest = admission.oldestDeferredAgeNanos(nowNanos);

        updateMax(MAX_LOGICAL_QUEUE, logical);
        updateMax(MAX_DEFERRED_QUEUE, deferred);
        updateMax(MAX_DEFERRED_AGE, oldest);

        LAST.set(new Snapshot(
                nowNanos,
                vanillaQueuedTasks,
                logical,
                deferred,
                oldest,
                MAX_LOGICAL_QUEUE.get(),
                MAX_DEFERRED_QUEUE.get(),
                MAX_DEFERRED_AGE.get(),
                admission.snapshot()
        ));
    }

    public static Snapshot snapshot() {
        return LAST.get();
    }

    static void resetForTest() {
        LAST.set(Snapshot.empty());
        MAX_LOGICAL_QUEUE.set(0L);
        MAX_DEFERRED_QUEUE.set(0L);
        MAX_DEFERRED_AGE.set(0L);
    }

    private static void updateMax(final AtomicLong target, final long candidate) {
        long current = target.get();
        while (candidate > current && !target.compareAndSet(current, candidate)) {
            current = target.get();
        }
    }
}
