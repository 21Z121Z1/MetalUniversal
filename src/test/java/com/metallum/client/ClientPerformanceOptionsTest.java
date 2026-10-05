package com.metallum.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPerformanceOptionsTest {
    @AfterEach
    void clear() {
        System.clearProperty(ClientPerformanceOptions.MODEL_PART_INDEXED_LOOP_PROPERTY);
        System.clearProperty(ClientPerformanceOptions.LOADING_FPS_PROPERTY);
    }

    @Test
    void genericClientOptimizationsAreOptInAndBounded() {
        assertFalse(ClientPerformanceOptions.modelPartIndexedLoopEnabled());
        assertEquals(0, ClientPerformanceOptions.loadingFps());

        System.setProperty(ClientPerformanceOptions.MODEL_PART_INDEXED_LOOP_PROPERTY, "true");
        System.setProperty(ClientPerformanceOptions.LOADING_FPS_PROPERTY, "45");
        assertTrue(ClientPerformanceOptions.modelPartIndexedLoopEnabled());
        assertEquals(45, ClientPerformanceOptions.loadingFps());

        System.setProperty(ClientPerformanceOptions.LOADING_FPS_PROPERTY, "999");
        assertEquals(240, ClientPerformanceOptions.loadingFps());

        System.setProperty(ClientPerformanceOptions.LOADING_FPS_PROPERTY, "bad");
        assertEquals(0, ClientPerformanceOptions.loadingFps());
    }
}
