package com.metallum.e2e;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameWorkloadsTest {
    @Test void vanillaSodiumAndIrisAreDistinctActivationContracts() {
        for (String producer : new String[]{"vanilla", "sodium", "iris"}) {
            for (boolean sodium : new boolean[]{false, true}) for (boolean iris : new boolean[]{false, true}) {
                boolean expected = sodium == !producer.equals("vanilla") && iris == producer.equals("iris");
                if (expected) assertDoesNotThrow(() -> FrameWorkloads.validateProducer(producer, sodium, iris));
                else assertThrows(IllegalStateException.class, () -> FrameWorkloads.validateProducer(producer, sodium, iris));
            }
        }
        assertThrows(IllegalStateException.class, () -> FrameWorkloads.validateProducer("unknown", false, false));
    }
}
