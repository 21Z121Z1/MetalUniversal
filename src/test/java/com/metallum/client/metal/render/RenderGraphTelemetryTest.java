package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

final class RenderGraphTelemetryTest {
    @Test
    void deferredDepthStoreIsNotCountedUntilKilled() {
        RenderGraphTelemetry.reset();

        RenderGraphTelemetry.onEncoderCreated(
                "deferred-depth", 64, 64,
                new int[] {4}, new boolean[] {false},
                new boolean[] {false}, 4, false, true
        );
        assertEquals(0L, RenderGraphTelemetry.snapshot().get("depthStoreBytesEstimate"));

        RenderGraphTelemetry.onDepthStoreKilled(64 * 64, 4);
        assertEquals(16_384L, RenderGraphTelemetry.snapshot().get("depthStoreKilledBytes"));
    }

    @Test
    void immediateDepthStoreIsCountedAtEncoderCreation() {
        RenderGraphTelemetry.reset();

        RenderGraphTelemetry.onEncoderCreated(
                "immediate-depth", 32, 32,
                new int[0], new boolean[0], new boolean[0],
                4, false, false
        );

        assertEquals(4_096L, RenderGraphTelemetry.snapshot().get("depthStoreBytesEstimate"));
    }

    @Test
    void killedColorStoresMoveBytesFromStoreToKilledEvidence() {
        RenderGraphTelemetry.reset();

        RenderGraphTelemetry.onEncoderCreated(
                "deferred-color", 64, 64,
                new int[] {4, 4}, new boolean[] {false, false},
                new boolean[] {true, false},
                0, false, false
        );

        Map<String, Object> snapshot = RenderGraphTelemetry.snapshot();
        assertEquals(16_384L, snapshot.get("colorStoreBytesEstimate"));
        assertEquals(32_768L, snapshot.get("colorLoadBytesEstimate"));
        assertEquals(16_384L, snapshot.get("colorStoreKilledBytes"));
    }
}
