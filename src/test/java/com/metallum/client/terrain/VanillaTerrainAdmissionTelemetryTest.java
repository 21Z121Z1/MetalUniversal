package com.metallum.client.terrain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VanillaTerrainAdmissionTelemetryTest {
    @AfterEach
    void reset() {
        VanillaTerrainAdmissionTelemetry.resetForTest();
    }

    @Test
    void reportsLogicalBacklogAndCurrentOldestWaitWithoutDrivingPolicy() {
        class Task {
            final Object owner = new Object();
            boolean terminal;
        }
        var admission = new BoundedTerrainTaskAdmission<>(
                new BoundedTerrainTaskAdmission.Config(true, 1, 2),
                new BoundedTerrainTaskAdmission.TaskOps<Task>() {
                    @Override public Object ownerIdentity(Task task) { return task.owner; }
                    @Override public Object kindIdentity(Task task) { return Task.class; }
                    @Override public boolean isTerminal(Task task) { return task.terminal; }
                    @Override public void cancel(Task task) { task.terminal = true; }
                }
        );

        admission.offer(new Task(), 1, 10L);
        VanillaTerrainAdmissionTelemetry.publish(admission, 1, 110L);
        var first = VanillaTerrainAdmissionTelemetry.snapshot();
        assertEquals(2, first.logicalQueuedTasks());
        assertEquals(1, first.deferredQueuedTasks());
        assertEquals(100L, first.currentOldestDeferredAgeNanos());

        admission.drain(1, 150L);
        VanillaTerrainAdmissionTelemetry.publish(admission, 0, 150L);
        var second = VanillaTerrainAdmissionTelemetry.snapshot();
        assertEquals(0, second.logicalQueuedTasks());
        assertEquals(2L, second.maxObservedLogicalQueue());
        assertEquals(1L, second.maxObservedDeferredQueue());
        assertTrue(second.maxObservedDeferredAgeNanos() >= 100L);
    }
}
