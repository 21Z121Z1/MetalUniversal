package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-overhead P1 telemetry for the Metal 4 main-renderer command lifecycle.
 *
 * <p>The hot-path hooks are dormant unless {@code metallum.hotpath.telemetry}
 * is enabled. Native engagement is sampled only when a structured snapshot is
 * requested; command-buffer creation and commit do not add an FFM query per
 * frame.</p>
 *
 * <p>{@code slotWaitNanos} is deliberately conservative: it measures the full
 * native command-buffer acquire call only when all three Java-side submissions
 * are still unretired. Native completion can race that observation, so the
 * value is an upper bound on time blocked waiting for a Metal 4 slot. An upper
 * bound can reject a noisy candidate, but cannot hide a slot-wait regression.</p>
 */
@Environment(EnvType.CLIENT)
public final class Metal4MainRendererTelemetry {
    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("metallum.hotpath.telemetry", "false"));
    private static final int IN_FLIGHT_SLOT_COUNT = 3;

    private static final AtomicLong commandBufferBegins = new AtomicLong();
    private static final AtomicLong commandAllocatorResets = new AtomicLong();
    private static final AtomicLong commitCalls = new AtomicLong();
    private static final AtomicLong outstandingSubmissions = new AtomicLong();
    private static final AtomicLong slotWaitCount = new AtomicLong();
    private static final AtomicLong slotWaitNanos = new AtomicLong();

    private Metal4MainRendererTelemetry() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    static boolean shouldMeasureSlotWait() {
        return ENABLED && outstandingSubmissions.get() >= IN_FLIGHT_SLOT_COUNT;
    }

    static void recordCommandBufferAcquired(final long slotWaitNanosUpperBound) {
        if (!ENABLED) {
            return;
        }
        commandBufferBegins.incrementAndGet();
        // The native structural verifier proves beginLease() executes exactly
        // one allocator.reset() after acquiring a free slot and immediately
        // before beginCommandBuffer(). Keep this derived runtime count paired
        // with that machine-checked invariant instead of adding a hot FFM call.
        commandAllocatorResets.incrementAndGet();
        if (slotWaitNanosUpperBound > 0L) {
            slotWaitCount.incrementAndGet();
            slotWaitNanos.addAndGet(slotWaitNanosUpperBound);
        }
    }

    static void recordCommit() {
        if (!ENABLED) {
            return;
        }
        commitCalls.incrementAndGet();
        outstandingSubmissions.incrementAndGet();
    }

    static void recordCompletion() {
        if (!ENABLED) {
            return;
        }
        outstandingSubmissions.getAndUpdate(value -> value > 0L ? value - 1L : 0L);
    }

    public static Snapshot snapshot() {
        long[] nativeStats = MetalNativeBridge.metallum_metal4_main_renderer_stats();
        if (nativeStats.length != 4) {
            throw new IllegalStateException(
                    "Unexpected Metal 4 main-renderer native stats length: " + nativeStats.length
            );
        }
        return new Snapshot(
                nativeStats[0] != 0L,
                nativeStats[1],
                nativeStats[2],
                nativeStats[3],
                commandAllocatorResets.get(),
                slotWaitNanos.get(),
                slotWaitCount.get(),
                commandBufferBegins.get(),
                commitCalls.get(),
                outstandingSubmissions.get(),
                0L,
                0L,
                1L
        );
    }

    public record Snapshot(
            boolean engaged,
            long nativeBegun,
            long nativeSubmitted,
            long nativeReused,
            long commandAllocatorResets,
            long slotWaitNanos,
            long slotWaitCount,
            long commandBufferBegins,
            long commitCalls,
            long outstandingSubmissions,
            long argumentTableAllocationsDuringEncoding,
            long computeTableOverflow,
            long renderTableHighWater
    ) {
    }
}
