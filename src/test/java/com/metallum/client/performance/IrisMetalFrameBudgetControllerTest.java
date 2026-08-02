package com.metallum.client.performance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalFrameBudgetControllerTest {
    private static final long PERIOD = 16_666_667L;

    @Test
    void disabledControllerPreservesExistingBudgets() {
        IrisMetalFrameBudgetController controller = new IrisMetalFrameBudgetController(
                false, IrisMetalFrameBudgetController.Mode.STABLE, PERIOD, 750_000L
        );
        assertFalse(controller.beginFrame(1_000_000_000L).available());
        assertEquals(4_000_000L, controller.clampBudget(
                IrisMetalFrameBudgetController.WorkCategory.BACKGROUND_VISIBLE,
                4_000_000L,
                1_000_000_000L
        ));
    }

    @Test
    void slowFrameDoesNotIncreaseTheConfiguredDisplayPeriod() {
        IrisMetalFrameBudgetController controller = new IrisMetalFrameBudgetController(
                true, IrisMetalFrameBudgetController.Mode.STABLE, PERIOD, 750_000L
        );
        controller.beginFrame(1_000_000_000L);
        controller.endFrame(1_050_000_000L, 50_000_000L, 45_000_000L);
        DisplayDeadlineSnapshot next = controller.beginFrame(1_050_000_000L);

        assertEquals(PERIOD, next.framePeriodNanos());
        assertEquals(PERIOD, controller.estimatedFramePeriodNanos());
        assertEquals(0L, controller.availableBudgetNanos(1_050_000_000L));
    }

    @Test
    void reservationsReduceLaterWorkInTheSameFrame() {
        IrisMetalFrameBudgetController controller = new IrisMetalFrameBudgetController(
                true, IrisMetalFrameBudgetController.Mode.STABLE, PERIOD, 750_000L
        );
        long now = 1_000_000_000L;
        controller.beginFrame(now);
        long before = controller.availableBudgetNanos(now);
        assertTrue(before > 1_000_000L);
        assertTrue(controller.reserve(
                IrisMetalFrameBudgetController.WorkCategory.BACKGROUND_VISIBLE,
                1_000_000L,
                now
        ));
        assertEquals(before - 1_000_000L, controller.availableBudgetNanos(now));
    }

    @Test
    void validationWorkIsRejectedDuringPerformanceFrames() {
        IrisMetalFrameBudgetController controller = new IrisMetalFrameBudgetController(
                true, IrisMetalFrameBudgetController.Mode.LOW_LATENCY, PERIOD, 350_000L
        );
        controller.beginFrame(1_000_000_000L);
        assertFalse(controller.reserve(
                IrisMetalFrameBudgetController.WorkCategory.VALIDATION_ONLY,
                1L,
                1_000_000_000L
        ));
        assertEquals(1L, controller.counters().rejectedValidationTasks());
    }

    @Test
    void realDisplayLinkDeadlineReplacesOnlyOlderEstimate() {
        IrisMetalFrameBudgetController controller = new IrisMetalFrameBudgetController(
                true, IrisMetalFrameBudgetController.Mode.STABLE, PERIOD, 750_000L
        );
        DisplayDeadlineSnapshot estimate = controller.beginFrame(1_000_000_000L);
        DisplayDeadlineSnapshot real = new DisplayDeadlineSnapshot(
                estimate.sequence(),
                1_017_000_000L,
                1_016_000_000L,
                17_000_000L,
                DisplayDeadlineSnapshot.Source.METAL_DISPLAY_LINK
        );
        controller.observeDisplayDeadline(real);
        assertEquals(real, controller.currentDeadline());

        controller.observeDisplayDeadline(new DisplayDeadlineSnapshot(
                Math.max(0L, estimate.sequence() - 1L),
                1_018_000_000L,
                1_017_000_000L,
                17_000_000L,
                DisplayDeadlineSnapshot.Source.METAL_DISPLAY_LINK
        ));
        assertEquals(real, controller.currentDeadline());
    }
}
