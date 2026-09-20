package com.metallum.client.terrain;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BoundedTerrainTaskAdmissionTest {
    @Test
    void disabledOrUnconfiguredAdmissionNeverChangesVanilla() {
        Fixture disabled = new Fixture(new BoundedTerrainTaskAdmission.Config(false, 2, 2));
        assertEquals(BoundedTerrainTaskAdmission.Action.BASELINE, disabled.offer(new Task(new Object(), "compile"), 99, 1).action());

        Fixture missingCapacity = new Fixture(new BoundedTerrainTaskAdmission.Config(true, 0, 0));
        assertEquals(BoundedTerrainTaskAdmission.Action.BASELINE, missingCapacity.offer(new Task(new Object(), "compile"), 99, 1).action());
        assertFalse(missingCapacity.admission.snapshot().active());
    }

    @Test
    void fillsVanillaThenDefersWithoutDroppingWork() {
        Fixture f = new Fixture(new BoundedTerrainTaskAdmission.Config(true, 2, 3));
        assertEquals(BoundedTerrainTaskAdmission.Action.ADMIT, f.offer(new Task(new Object(), "compile"), 0, 10).action());
        assertEquals(BoundedTerrainTaskAdmission.Action.DEFER, f.offer(new Task(new Object(), "compile"), 2, 20).action());
        assertEquals(BoundedTerrainTaskAdmission.Action.DEFER, f.offer(new Task(new Object(), "compile"), 2, 30).action());
        assertEquals(2, f.admission.deferredSize());

        var drained = f.admission.drain(1, 100);
        assertEquals(1, drained.tasks().size());
        assertEquals(80L, drained.oldestWaitNanos());
        assertEquals(1, f.admission.deferredSize());
    }

    @Test
    void replacementKeepsOriginalWaitingAgeAndLatestTask() {
        Fixture f = new Fixture(new BoundedTerrainTaskAdmission.Config(true, 1, 2));
        Object section = new Object();
        Task old = new Task(section, "compile");
        assertEquals(BoundedTerrainTaskAdmission.Action.DEFER, f.offer(old, 1, 10).action());
        old.terminal = true;
        Task latest = new Task(section, "compile");
        assertEquals(BoundedTerrainTaskAdmission.Action.REPLACE_DEFERRED, f.offer(latest, 1, 90).action());

        var drained = f.admission.drain(1, 110);
        assertEquals(List.of(latest), drained.tasks());
        assertEquals(100L, drained.oldestWaitNanos());
        assertEquals(1L, f.admission.snapshot().replacedDeferredTasks());
    }

    @Test
    void refusesToMergeTwoLiveSemanticTasksAndFailsOpen() {
        Fixture f = new Fixture(new BoundedTerrainTaskAdmission.Config(true, 1, 2));
        Object section = new Object();
        Task old = new Task(section, "compile");
        assertEquals(BoundedTerrainTaskAdmission.Action.DEFER, f.offer(old, 1, 10).action());

        Task secondLive = new Task(section, "compile");
        var result = f.offer(secondLive, 1, 20);
        assertEquals(BoundedTerrainTaskAdmission.Action.FAIL_OPEN, result.action());
        assertEquals(List.of(old), result.failOpenTasks());
        assertTrue(f.admission.snapshot().failOpen());
        assertFalse(f.admission.snapshot().active(), "fail-open must report mutation as inactive");
        assertEquals(BoundedTerrainTaskAdmission.Action.BASELINE, f.offer(new Task(new Object(), "compile"), 999, 30).action());
    }

    @Test
    void deferredCapacityExhaustionReturnsEveryOwnedTaskToVanilla() {
        Fixture f = new Fixture(new BoundedTerrainTaskAdmission.Config(true, 1, 2));
        Task first = new Task(new Object(), "compile");
        Task second = new Task(new Object(), "compile");
        Task overflow = new Task(new Object(), "compile");
        f.offer(first, 1, 1);
        f.offer(second, 1, 2);

        var result = f.offer(overflow, 1, 3);
        assertEquals(BoundedTerrainTaskAdmission.Action.FAIL_OPEN, result.action());
        assertEquals(List.of(first, second), result.failOpenTasks());
        assertEquals(0, f.admission.deferredSize());
        assertEquals(1L, f.admission.snapshot().failOpenCount());
    }

    @Test
    void equalFrozenOwnerValuesCoalesceEvenWhenRepresentedByDifferentObjects() {
        Fixture f = new Fixture(new BoundedTerrainTaskAdmission.Config(true, 1, 2));
        Task old = new Task(new String("section-42"), "compile");
        assertEquals(BoundedTerrainTaskAdmission.Action.DEFER, f.offer(old, 1, 10).action());
        old.terminal = true;

        Task replacement = new Task(new String("section-42"), "compile");
        assertEquals(
                BoundedTerrainTaskAdmission.Action.REPLACE_DEFERRED,
                f.offer(replacement, 1, 20).action()
        );
        assertEquals(List.of(replacement), f.admission.drain(1, 30).tasks());
    }

    @Test
    void backlogModeDefersNewArrivalsUntilTheCurrentVanillaCohortDrains() {
        Fixture f = new Fixture(new BoundedTerrainTaskAdmission.Config(true, 2, 4));
        Task firstDeferred = new Task(new Object(), "compile");
        assertEquals(BoundedTerrainTaskAdmission.Action.DEFER, f.offer(firstDeferred, 2, 10).action());

        // Even though the vanilla batch now has a free slot, a new arrival must not jump into it.
        Task newer = new Task(new Object(), "compile");
        assertEquals(BoundedTerrainTaskAdmission.Action.DEFER, f.offer(newer, 1, 20).action());
        assertEquals(2, f.admission.deferredSize());

        // At the cohort boundary the oldest deferred work is admitted first.
        var nextBatch = f.admission.drain(2, 100);
        assertEquals(List.of(firstDeferred, newer), nextBatch.tasks());
        assertEquals(90L, nextBatch.oldestWaitNanos());
    }

    @Test
    void fifoDrainAndStableReplacementPreventStarvation() {
        Fixture f = new Fixture(new BoundedTerrainTaskAdmission.Config(true, 1, 8));
        Object firstSection = new Object();
        Task first = new Task(firstSection, "compile");
        Task second = new Task(new Object(), "compile");
        Task third = new Task(new Object(), "compile");
        f.offer(first, 1, 10);
        f.offer(second, 1, 20);
        f.offer(third, 1, 30);

        first.terminal = true;
        Task latestFirst = new Task(firstSection, "compile");
        f.offer(latestFirst, 1, 80);

        List<Task> order = new ArrayList<>();
        order.addAll(f.admission.drain(1, 100).tasks());
        order.addAll(f.admission.drain(1, 110).tasks());
        order.addAll(f.admission.drain(1, 120).tasks());
        assertEquals(List.of(latestFirst, second, third), order);
    }

    @Test
    void clearCancelsDeferredOwnershipAndRearmsAfterFailOpen() {
        Fixture f = new Fixture(new BoundedTerrainTaskAdmission.Config(true, 1, 1));
        Task deferred = new Task(new Object(), "compile");
        f.offer(deferred, 1, 1);
        f.offer(new Task(new Object(), "compile"), 1, 2);
        assertTrue(f.admission.snapshot().failOpen());

        f.admission.clearDeferredAndReset();
        assertFalse(f.admission.snapshot().failOpen());
        assertEquals(BoundedTerrainTaskAdmission.Action.DEFER,
                f.offer(new Task(new Object(), "compile"), 1, 3).action());
    }

    private static final class Fixture {
        final BoundedTerrainTaskAdmission<Task> admission;

        Fixture(BoundedTerrainTaskAdmission.Config config) {
            admission = new BoundedTerrainTaskAdmission<>(config, new BoundedTerrainTaskAdmission.TaskOps<>() {
                @Override
                public Object ownerIdentity(Task task) {
                    return task.owner;
                }

                @Override
                public Object kindIdentity(Task task) {
                    return task.kind;
                }

                @Override
                public boolean isTerminal(Task task) {
                    return task.terminal;
                }

                @Override
                public void cancel(Task task) {
                    task.terminal = true;
                }
            });
        }

        BoundedTerrainTaskAdmission.OfferResult<Task> offer(Task task, int queued, long now) {
            return admission.offer(task, queued, now);
        }
    }

    private static final class Task {
        final Object owner;
        final Object kind;
        boolean terminal;

        Task(Object owner, Object kind) {
            this.owner = owner;
            this.kind = kind;
        }
    }
}
