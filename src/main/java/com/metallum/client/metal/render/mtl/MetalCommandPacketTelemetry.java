package com.metallum.client.metal.render.mtl;

import java.util.concurrent.atomic.LongAdder;

/** Optional counters for experimental render/compute packets and terrain ICB. */
public final class MetalCommandPacketTelemetry {
    private static final boolean ENABLED = Boolean.getBoolean("metallum.hotpath.telemetry");

    private static final LongAdder renderPacketCalls = new LongAdder();
    private static final LongAdder renderOperations = new LongAdder();
    private static final LongAdder renderLegacyReplays = new LongAdder();
    private static final LongAdder computePacketCalls = new LongAdder();
    private static final LongAdder computeOperations = new LongAdder();
    private static final LongAdder computeLegacyReplays = new LongAdder();
    private static final LongAdder terrainIcbAttempts = new LongAdder();
    private static final LongAdder terrainIcbAccepted = new LongAdder();
    private static final LongAdder terrainIcbDraws = new LongAdder();
    private static final LongAdder terrainIcbFallbacks = new LongAdder();

    private MetalCommandPacketTelemetry() {
    }

    static void renderPacket(final int operations) {
        if (!ENABLED) return;
        renderPacketCalls.increment();
        renderOperations.add(operations);
    }

    static void renderReplay() {
        if (ENABLED) renderLegacyReplays.increment();
    }

    static void computePacket(final int operations) {
        if (!ENABLED) return;
        computePacketCalls.increment();
        computeOperations.add(operations);
    }

    static void computeReplay() {
        if (ENABLED) computeLegacyReplays.increment();
    }

    public static void terrainIcbAttempt(final int drawCount) {
        if (!ENABLED) return;
        terrainIcbAttempts.increment();
        terrainIcbDraws.add(drawCount);
    }

    public static void terrainIcbAccepted() {
        if (ENABLED) terrainIcbAccepted.increment();
    }

    public static void terrainIcbFallback() {
        if (ENABLED) terrainIcbFallbacks.increment();
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                renderPacketCalls.sum(),
                renderOperations.sum(),
                renderLegacyReplays.sum(),
                computePacketCalls.sum(),
                computeOperations.sum(),
                computeLegacyReplays.sum(),
                terrainIcbAttempts.sum(),
                terrainIcbAccepted.sum(),
                terrainIcbDraws.sum(),
                terrainIcbFallbacks.sum()
        );
    }

    public static void reset() {
        renderPacketCalls.reset();
        renderOperations.reset();
        renderLegacyReplays.reset();
        computePacketCalls.reset();
        computeOperations.reset();
        computeLegacyReplays.reset();
        terrainIcbAttempts.reset();
        terrainIcbAccepted.reset();
        terrainIcbDraws.reset();
        terrainIcbFallbacks.reset();
    }

    public record Snapshot(
            long renderPacketCalls,
            long renderOperations,
            long renderLegacyReplays,
            long computePacketCalls,
            long computeOperations,
            long computeLegacyReplays,
            long terrainIcbAttempts,
            long terrainIcbAccepted,
            long terrainIcbDraws,
            long terrainIcbFallbacks
    ) {
    }
}
