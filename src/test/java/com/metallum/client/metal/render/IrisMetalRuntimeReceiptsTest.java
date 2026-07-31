package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalRuntimeReceiptsTest {
    @Test
    void metricsProveNonBlackAndFrameProgression() {
        byte[] first = {
                0, 0, 0, (byte) 255,
                10, 20, 30, (byte) 255
        };
        byte[] second = {
                0, 0, 0, (byte) 255,
                40, 20, 30, (byte) 255
        };

        IrisMetalRuntimeReceipts.FrameMetrics initial = IrisMetalRuntimeReceipts.analyze(
                first, 2, 1, 4, null
        );
        IrisMetalRuntimeReceipts.FrameMetrics next = IrisMetalRuntimeReceipts.analyze(
                second, 2, 1, 4, first
        );

        assertFalse(initial.hasPreviousFrame());
        assertEquals(1, initial.nonBlackRgbPixels());
        assertEquals(30, initial.maxByte());
        assertEquals(60, initial.sumRgbBytes());
        assertTrue(next.hasPreviousFrame());
        assertEquals(1, next.nonBlackRgbPixels());
        assertEquals(1, next.changedPixels());
        assertEquals(30.0 / 8.0, next.meanAbsoluteByteDelta(), 0.000001);
        assertEquals(90, next.sumRgbBytes());
        assertFalse(next.sha256().equals(initial.sha256()));
    }
}
