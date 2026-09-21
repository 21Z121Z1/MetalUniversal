package com.metallum.client.metal.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class MetalPreviousVertexCameraHistoryTest {
    @AfterEach
    void resetHistory() {
        MetalPreviousVertexHistory.discardFrame();
        MetalPreviousVertexHistory.reset();
    }

    @Test
    void cameraHistoryAdvancesOnlyOnSuccessfulSourceFrame() {
        MetalPreviousVertexHistory.beginFrame();
        MetalPreviousVertexHistory.observeCamera(10.0, 20.0, 30.0);
        assertNull(MetalPreviousVertexHistory.previousCamera());
        assertEquals(
                new MetalPreviousVertexHistory.CameraPosition(10.0, 20.0, 30.0),
                MetalPreviousVertexHistory.currentCamera()
        );
        MetalPreviousVertexHistory.commitSubmittedFrame();
        assertEquals(
                new MetalPreviousVertexHistory.CameraPosition(10.0, 20.0, 30.0),
                MetalPreviousVertexHistory.previousCamera()
        );

        MetalPreviousVertexHistory.beginFrame();
        MetalPreviousVertexHistory.observeCamera(40.0, 50.0, 60.0);
        MetalPreviousVertexHistory.discardFrame();
        assertEquals(
                new MetalPreviousVertexHistory.CameraPosition(10.0, 20.0, 30.0),
                MetalPreviousVertexHistory.previousCamera(),
                "a failed source frame must not advance previous camera history"
        );
    }

    @Test
    void nonFiniteCameraFailsClosed() {
        MetalPreviousVertexHistory.beginFrame();
        MetalPreviousVertexHistory.observeCamera(Double.NaN, 0.0, 0.0);
        assertNull(MetalPreviousVertexHistory.currentCamera());
        MetalPreviousVertexHistory.commitSubmittedFrame();
        assertNull(MetalPreviousVertexHistory.previousCamera());
    }
}
