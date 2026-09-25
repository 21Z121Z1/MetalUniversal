package com.metallum.e2e;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameWorkloadsTest {
    @Test void vanillaSodiumAndIrisAreDistinctActivationContracts() {
        for (String producer : new String[]{"vanilla", "sodium", "iris"}) {
            for (boolean sodium : new boolean[]{false, true}) for (boolean iris : new boolean[]{false, true}) {
                boolean expected = sodium == !producer.equals("vanilla") && iris == producer.equals("iris");
                if (expected) {
                    assertDoesNotThrow(() -> FrameWorkloads.validateProducer(producer, sodium, iris));
                    assertDoesNotThrow(() -> MetalReadbackControlGameTest.validateRenderer(producer, sodium, iris));
                } else {
                    assertThrows(IllegalStateException.class, () -> FrameWorkloads.validateProducer(producer, sodium, iris));
                    assertThrows(IllegalStateException.class, () -> MetalReadbackControlGameTest.validateRenderer(producer, sodium, iris));
                }
            }
        }
        assertThrows(IllegalStateException.class, () -> FrameWorkloads.validateProducer("unknown", false, false));
        assertThrows(IllegalStateException.class, () -> MetalReadbackControlGameTest.validateRenderer("", false, false));
    }
    @Test void declaredDurationCannotSilentlyFallbackOrOverflow() {
        String key = "metallum.test.frameDuration";
        String previous = System.getProperty(key);
        try {
            System.clearProperty(key);
            assertEquals(30, FrameWorkloads.durationNanos(key, 30, 0, 100));
            System.setProperty(key, "0");
            assertEquals(0, FrameWorkloads.durationNanos(key, 30, 0, 100));
            for (String invalid : new String[]{"", "NaN", "1.5", "-1", "101", "9223372036854775808"}) {
                System.setProperty(key, invalid);
                assertThrows(IllegalArgumentException.class, () -> FrameWorkloads.durationNanos(key, 30, 0, 100));
            }
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }
}
