package com.metallum.client.performance;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DeferredRenderWorkQueueTest {
    @Test
    void executesHigherPriorityWorkFirst() {
        DeferredRenderWorkQueue queue = new DeferredRenderWorkQueue(4);
        List<String> order = new ArrayList<>();
        queue.enqueue("low", IrisMetalFrameBudgetController.WorkCategory.MAINTENANCE,
                DeferredRenderWorkQueue.Priority.LOW, 1L, () -> order.add("low"));
        queue.enqueue("high", IrisMetalFrameBudgetController.WorkCategory.CACHE_WARMUP,
                DeferredRenderWorkQueue.Priority.HIGH, 1L, () -> order.add("high"));
        queue.enqueue("immediate", IrisMetalFrameBudgetController.WorkCategory.CRITICAL_VISIBLE,
                DeferredRenderWorkQueue.Priority.IMMEDIATE, 1L, () -> order.add("immediate"));

        IrisMetalFrameBudgetController disabled = new IrisMetalFrameBudgetController(
                false, IrisMetalFrameBudgetController.Mode.STABLE, 16_666_667L, 750_000L
        );
        assertEquals(3, queue.drain(disabled, 3));
        assertEquals(List.of("immediate", "high", "low"), order);
    }

    @Test
    void leavesWorkQueuedWhenBudgetRejectsIt() {
        DeferredRenderWorkQueue queue = new DeferredRenderWorkQueue(2);
        assertTrue(queue.enqueue("validation", IrisMetalFrameBudgetController.WorkCategory.VALIDATION_ONLY,
                DeferredRenderWorkQueue.Priority.NORMAL, 1L, () -> {}));
        IrisMetalFrameBudgetController controller = new IrisMetalFrameBudgetController(
                true, IrisMetalFrameBudgetController.Mode.STABLE, 16_666_667L, 750_000L
        );
        controller.beginFrame(System.nanoTime());
        assertEquals(0, queue.drain(controller, 1));
        assertEquals(1, queue.size());
        assertEquals(1L, queue.counters().deferredDrains());
    }

    @Test
    void rejectsWorkAfterCapacityIsReached() {
        DeferredRenderWorkQueue queue = new DeferredRenderWorkQueue(1);
        assertTrue(queue.enqueue("first", IrisMetalFrameBudgetController.WorkCategory.MAINTENANCE,
                DeferredRenderWorkQueue.Priority.NORMAL, 1L, () -> {}));
        assertFalse(queue.enqueue("second", IrisMetalFrameBudgetController.WorkCategory.MAINTENANCE,
                DeferredRenderWorkQueue.Priority.NORMAL, 1L, () -> {}));
        assertEquals(1L, queue.counters().rejected());
    }
}
