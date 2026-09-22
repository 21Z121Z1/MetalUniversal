package com.metallum.client.metal.render.mtl;

import java.util.concurrent.atomic.LongAdder;

/** Optional counters for negotiated render-state and encoder-argument paths. */
public final class MetalRenderStatePacketTelemetry {
    private static final boolean ENABLED = Boolean.getBoolean("metallum.hotpath.telemetry");
    /**
     * Narrow, opt-in activation evidence for the paired encoder-state route.
     * This is kept separate from the broad hot-path counters so a gameplay
     * trial can prove reuse without enabling diagnostic telemetry wholesale.
     */
    private static final boolean REUSE_ACTIVATION_ENABLED = Boolean.getBoolean(
            "metallum.ci.reuseActivationTelemetry");
    private static final LongAdder packetCalls = new LongAdder();
    private static final LongAdder packetEntries = new LongAdder();
    private static final LongAdder legacyReplays = new LongAdder();
    private static final LongAdder legacyReplayEntries = new LongAdder();
    private static final LongAdder singleEntryBypasses = new LongAdder();
    private static final LongAdder capacityFlushes = new LongAdder();
    private static final LongAdder packetStorageAllocations = new LongAdder();
    private static final LongAdder packetStorageReuseHits = new LongAdder();
    private static final LongAdder shadowAllocations = new LongAdder();
    private static final LongAdder shadowReuseHits = new LongAdder();
    private static final LongAdder nativeEncoderArgumentReuseCalls = new LongAdder();

    private MetalRenderStatePacketTelemetry() {
    }

    static void recordPacket(final int entries) {
        if (ENABLED) {
            packetCalls.increment();
            packetEntries.add(Math.max(0, entries));
        }
    }

    static void recordLegacyReplay(final int entries) {
        if (ENABLED) {
            legacyReplays.increment();
            legacyReplayEntries.add(Math.max(0, entries));
        }
    }

    static void recordSingleEntryBypass() {
        if (ENABLED) {
            singleEntryBypasses.increment();
        }
    }

    static void recordCapacityFlush() {
        if (ENABLED) {
            capacityFlushes.increment();
        }
    }

    static void recordPacketStorageAllocation() {
        if (REUSE_ACTIVATION_ENABLED) {
            packetStorageAllocations.increment();
        }
    }

    static void recordPacketStorageReuse() {
        if (REUSE_ACTIVATION_ENABLED) {
            packetStorageReuseHits.increment();
        }
    }

    static void recordShadowAllocation() {
        if (REUSE_ACTIVATION_ENABLED) {
            shadowAllocations.increment();
        }
    }

    static void recordShadowReuse() {
        if (REUSE_ACTIVATION_ENABLED) {
            shadowReuseHits.increment();
        }
    }

    /** Records a successful V3 bridge call that used the bounded argument scratch. */
    public static void recordNativeEncoderArgumentReuse() {
        if (REUSE_ACTIVATION_ENABLED) {
            nativeEncoderArgumentReuseCalls.increment();
        }
    }

    public static boolean reuseActivationTelemetryEnabled() {
        return REUSE_ACTIVATION_ENABLED;
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                packetCalls.sum(),
                packetEntries.sum(),
                legacyReplays.sum(),
                legacyReplayEntries.sum(),
                singleEntryBypasses.sum(),
                capacityFlushes.sum(),
                packetStorageAllocations.sum(),
                packetStorageReuseHits.sum(),
                shadowAllocations.sum(),
                shadowReuseHits.sum(),
                nativeEncoderArgumentReuseCalls.sum()
        );
    }

    public static void reset() {
        packetCalls.reset();
        packetEntries.reset();
        legacyReplays.reset();
        legacyReplayEntries.reset();
        singleEntryBypasses.reset();
        capacityFlushes.reset();
        packetStorageAllocations.reset();
        packetStorageReuseHits.reset();
        shadowAllocations.reset();
        shadowReuseHits.reset();
        nativeEncoderArgumentReuseCalls.reset();
    }

    public record Snapshot(
            long packetCalls,
            long packetEntries,
            long legacyReplays,
            long legacyReplayEntries,
            long singleEntryBypasses,
            long capacityFlushes,
            long packetStorageAllocations,
            long packetStorageReuseHits,
            long shadowAllocations,
            long shadowReuseHits,
            long nativeEncoderArgumentReuseCalls
    ) {
        public double averageEntriesPerPacket() {
            return packetCalls == 0L ? 0.0 : (double) packetEntries / packetCalls;
        }

        public long collapsedSetterDowncalls() {
            return Math.max(0L, packetEntries - packetCalls);
        }
    }
}
