package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalFxDynamicResolutionPolicyTest {
    @Test
    void disabledControllerNeverChangesScale() {
        MetalFxDynamicResolutionPolicy.Controller controller =
                MetalFxDynamicResolutionPolicy.create(0.67F, 10_000_000L, false);
        for (int i = 0; i < 20; i++) {
            MetalFxDynamicResolutionPolicy.Decision decision = controller.update(100_000_000L);
            assertFalse(decision.changed());
            assertEquals(0.67F, decision.scale());
            assertEquals("feature-disabled", decision.reason());
        }
    }

    @Test
    void decreasesOnlyAfterThreeConsecutiveOverBudgetFrames() {
        MetalFxDynamicResolutionPolicy.Controller controller =
                MetalFxDynamicResolutionPolicy.create(1.0F, 10_000_000L, true);
        assertFalse(controller.update(11_000_000L).changed());
        assertFalse(controller.update(11_000_000L).changed());
        MetalFxDynamicResolutionPolicy.Decision decision = controller.update(11_000_000L);
        assertTrue(decision.changed());
        assertEquals(0.9F, decision.scale(), 0.0001F);
        assertEquals("over-budget-decrease", decision.reason());
    }

    @Test
    void increasesOnlyAfterEightConsecutiveUnderBudgetFramesAndClamps() {
        MetalFxDynamicResolutionPolicy.Controller controller =
                MetalFxDynamicResolutionPolicy.create(0.5F, 10_000_000L, true);
        for (int i = 0; i < 7; i++) {
            assertFalse(controller.update(7_000_000L).changed());
        }
        assertTrue(controller.update(7_000_000L).changed());
        assertEquals(0.6F, controller.scale(), 0.0001F);
        for (int step = 0; step < 32; step++) {
            controller.update(7_000_000L);
        }
        assertTrue(controller.scale() <= MetalFxDynamicResolutionPolicy.MAX_SCALE);
        assertEquals(1.0F, controller.scale(), 0.0001F);
    }

    @Test
    void invalidGpuSamplesDoNotMoveScaleOrAccumulateHysteresis() {
        MetalFxDynamicResolutionPolicy.Controller controller =
                MetalFxDynamicResolutionPolicy.create(0.8F, 10_000_000L, true);
        assertEquals("gpu-time-unavailable", controller.update(-1L).reason());
        assertEquals(0, controller.overBudgetFrames());
        assertEquals(0, controller.underBudgetFrames());
        assertEquals(0.8F, controller.scale());
    }
}
