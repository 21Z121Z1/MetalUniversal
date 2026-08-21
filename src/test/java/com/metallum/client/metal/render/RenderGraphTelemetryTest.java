package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RenderGraphTelemetryTest {
    @Test
    void deferredDepthStoreIsNotCountedUntilKilled() {
        RenderGraphTelemetry.reset();

        RenderGraphTelemetry.onEncoderCreated(
                "deferred-depth", 64, 64,
                new int[] {4}, new boolean[] {false},
                4, false, true
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
                new int[0], new boolean[0],
                4, false, false
        );

        assertEquals(4_096L, RenderGraphTelemetry.snapshot().get("depthStoreBytesEstimate"));
    }
}
