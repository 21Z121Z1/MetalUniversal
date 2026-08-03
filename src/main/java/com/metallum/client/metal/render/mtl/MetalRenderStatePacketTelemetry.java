package com.metallum.client.metal.render.mtl;

import java.util.concurrent.atomic.LongAdder;

/** Optional counters for the negotiated render-state packet path. */
public final class MetalRenderStatePacketTelemetry {
    private static final boolean ENABLED = Boolean.getBoolean("metallum.hotpath.telemetry");
    private static final LongAdder packetCalls = new LongAdder();
    private static final LongAdder packetEntries = new LongAdder();
    private static final LongAdder legacyReplays = new LongAdder();
    private static final LongAdder legacyReplayEntries = new LongAdder();
    private static final LongAdder singleEntryBypasses = new LongAdder();
    private static final LongAdder capacityFlushes = new LongAdder();

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

    public static Snapshot snapshot() {
        return new Snapshot(
                packetCalls.sum(),
                packetEntries.sum(),
                legacyReplays.sum(),
                legacyReplayEntries.sum(),
                singleEntryBypasses.sum(),
                capacityFlushes.sum()
        );
    }

    public static void reset() {
        packetCalls.reset();
        packetEntries.reset();
        legacyReplays.reset();
        legacyReplayEntries.reset();
        singleEntryBypasses.reset();
        capacityFlushes.reset();
    }

    public record Snapshot(
            long packetCalls,
            long packetEntries,
            long legacyReplays,
            long legacyReplayEntries,
            long singleEntryBypasses,
            long capacityFlushes
    ) {
        public double averageEntriesPerPacket() {
            return packetCalls == 0L ? 0.0 : (double) packetEntries / packetCalls;
        }

        public long collapsedSetterDowncalls() {
            return Math.max(0L, packetEntries - packetCalls);
        }
    }
}
