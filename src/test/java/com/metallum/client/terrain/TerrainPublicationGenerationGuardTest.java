package com.metallum.client.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerrainPublicationGenerationGuardTest {
    @Test
    void currentTaskAndMeshMayPublishExactlyAtCapturedGeneration() {
        Fixture f = new Fixture();
        Task task = new Task();
        Object mesh = new Object();
        f.guard.registerTask(task, 7L);
        assertTrue(f.guard.enterTask(task));
        f.guard.bindMeshFromActiveTask(mesh);
        f.guard.exitTask(task);

        assertPublication(f.guard, 7L, mesh, Expected.ALLOW_CURRENT);
        assertEquals(1L, f.guard.snapshot().allowedPublications());
        assertEquals(0, f.guard.snapshot().trackedMeshes());
    }

    @Test
    void dirtyCancelsQueuedTaskAndNewGenerationCanProceed() {
        Fixture f = new Fixture();
        Task stale = new Task();
        f.guard.registerTask(stale, 9L);
        f.guard.markDirty(9L);
        assertTrue(stale.cancelled);
        assertEquals(1L, f.guard.snapshot().cancelledObsoleteTasks());
        assertFalse(f.guard.enterTask(stale));
        assertTrue(f.guard.snapshot().active());
        assertEquals(
                TerrainPublicationGenerationGuard.FailOpenReason.NONE,
                f.guard.snapshot().failOpenReason()
        );

        Task current = new Task();
        f.guard.registerTask(current, 9L);
        assertTrue(f.guard.enterTask(current));
        assertPublication(f.guard, 9L, null, Expected.ALLOW_CURRENT);
        f.guard.exitTask(current);
    }

    @Test
    void dirtyDuringBuildRejectsDirectEmptyOrBlockEntityPublication() {
        Fixture f = new Fixture();
        Task task = new Task();
        f.guard.registerTask(task, 11L);
        assertTrue(f.guard.enterTask(task));
        f.guard.markDirty(11L);

        assertPublication(f.guard, 11L, null, Expected.REJECT_STALE);
        assertEquals(1L, f.guard.snapshot().rejectedStalePublications());
        f.guard.exitTask(task);
    }

    @Test
    void dirtyAfterStagingRejectsCallbackDrivenMeshPublication() {
        Fixture f = new Fixture();
        Task task = new Task();
        Object mesh = new Object();
        f.guard.registerTask(task, 12L);
        assertTrue(f.guard.enterTask(task));
        f.guard.bindMeshFromActiveTask(mesh);
        f.guard.exitTask(task);
        f.guard.markDirty(12L);

        assertPublication(f.guard, 12L, mesh, Expected.REJECT_STALE);
    }

    @Test
    void sectionMismatchRejectsRecycledRenderSectionPublication() {
        Fixture f = new Fixture();
        Task task = new Task();
        f.guard.registerTask(task, 13L);
        assertTrue(f.guard.enterTask(task));
        assertPublication(f.guard, 14L, null, Expected.REJECT_STALE);
        f.guard.exitTask(task);
    }

    @Test
    void worldAndMaterialChangesCancelTasksAndRejectInFlightResults() {
        for (boolean world : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            Task task = new Task();
            Object mesh = new Object();
            f.guard.registerTask(task, 15L);
            assertTrue(f.guard.enterTask(task));
            f.guard.bindMeshFromActiveTask(mesh);
            f.guard.exitTask(task);

            if (world) {
                f.guard.advanceWorldEpoch();
            } else {
                f.guard.advanceMaterialGeneration();
            }
            assertTrue(task.cancelled);
            assertPublication(f.guard, 15L, mesh, Expected.REJECT_STALE);
        }
    }

    @Test
    void unknownPublicationFailsOpenInsteadOfDroppingVanillaWork() {
        Fixture f = new Fixture();
        assertPublication(f.guard, 16L, new Object(), Expected.BASELINE_ALLOW);
        assertFalse(f.guard.snapshot().active());
        assertEquals(
                TerrainPublicationGenerationGuard.FailOpenReason.UNKNOWN_PUBLICATION,
                f.guard.snapshot().failOpenReason()
        );
        assertEquals(1L, f.guard.snapshot().unknownPublicationFailOpenCount());

        assertPublication(f.guard, 16L, new Object(), Expected.BASELINE_ALLOW);
        assertEquals(2L, f.guard.snapshot().baselinePublicationsAfterFailOpen());
    }

    @Test
    void unreferencedSectionMetadataEvictsWithinTheConfiguredBound() {
        var bounded = new TerrainPublicationGenerationGuard<>(
                new TerrainPublicationGenerationGuard.Config(true, 1, 4, 4),
                OPS
        );
        Task first = new Task();
        bounded.registerTask(first, 21L);
        assertTrue(bounded.enterTask(first));
        assertPublication(bounded, 21L, null, Expected.ALLOW_CURRENT);
        bounded.exitTask(first);

        Task second = new Task();
        bounded.registerTask(second, 22L);
        assertTrue(bounded.snapshot().active());
        assertEquals(1, bounded.snapshot().sectionVersionEntries());
        assertTrue(bounded.enterTask(second));
        assertPublication(bounded, 22L, null, Expected.ALLOW_CURRENT);
        bounded.exitTask(second);
    }

    @Test
    void liveSectionCapacityFailsOpenInsteadOfGrowingWithoutBound() {
        var bounded = new TerrainPublicationGenerationGuard<>(
                new TerrainPublicationGenerationGuard.Config(true, 1, 4, 4),
                OPS
        );
        Task first = new Task();
        Object mesh = new Object();
        bounded.registerTask(first, 31L);
        assertTrue(bounded.enterTask(first));
        bounded.bindMeshFromActiveTask(mesh);
        bounded.exitTask(first);

        bounded.registerTask(new Task(), 32L);
        assertFalse(bounded.snapshot().active());
        assertEquals(
                TerrainPublicationGenerationGuard.FailOpenReason.SECTION_CAPACITY,
                bounded.snapshot().failOpenReason()
        );
        assertEquals(1L, bounded.snapshot().sectionCapacityFailOpenCount());
        assertEquals(0, bounded.snapshot().sectionVersionEntries());
        assertEquals(0, bounded.snapshot().trackedTasks());
        assertEquals(0, bounded.snapshot().trackedMeshes());

        bounded.markDirty(33L);
        assertEquals(0, bounded.snapshot().sectionVersionEntries(),
                "fail-open must not rebuild diagnostic version state");
        assertPublication(bounded, 31L, mesh, Expected.BASELINE_ALLOW);
    }

    @Test
    void evictedSectionCannotRecreateARevisionForAStaleWorkerToken() {
        var bounded = new TerrainPublicationGenerationGuard<>(
                new TerrainPublicationGenerationGuard.Config(true, 1, 4, 4),
                OPS
        );
        Task stale = new Task();
        bounded.registerTask(stale, 41L);
        assertTrue(bounded.enterTask(stale));
        bounded.markDirty(41L);
        assertTrue(stale.cancelled);

        Task replacementSection = new Task();
        bounded.registerTask(replacementSection, 42L);
        assertTrue(bounded.snapshot().active());
        assertEquals(1, bounded.snapshot().sectionVersionEntries());

        assertPublication(bounded, 41L, null, Expected.REJECT_STALE);
        assertEquals(1, bounded.snapshot().sectionVersionEntries(),
                "stale validation must be lookup-only and must not recreate evicted metadata");

        bounded.exitTask(stale);
        assertTrue(bounded.enterTask(replacementSection));
        assertPublication(bounded, 42L, null, Expected.ALLOW_CURRENT);
        bounded.exitTask(replacementSection);
    }

    @Test
    void meshRebindAndCapacityFailuresNeverGuessOwnership() {
        var tiny = new TerrainPublicationGenerationGuard<>(
                new TerrainPublicationGenerationGuard.Config(true, 2, 1),
                OPS
        );
        Task first = new Task();
        Task second = new Task();
        Object mesh = new Object();

        tiny.registerTask(first, 1L);
        assertTrue(tiny.enterTask(first));
        tiny.bindMeshFromActiveTask(mesh);
        tiny.exitTask(first);

        tiny.registerTask(second, 2L);
        assertTrue(tiny.enterTask(second));
        tiny.bindMeshFromActiveTask(new Object());
        assertFalse(tiny.snapshot().active());
        assertEquals(TerrainPublicationGenerationGuard.FailOpenReason.MESH_CAPACITY, tiny.snapshot().failOpenReason());
        tiny.exitTask(second);
    }

    private enum Expected { BASELINE_ALLOW, ALLOW_CURRENT, REJECT_STALE }

    /** Verify actual pointer exchange, callback cardinality, returned ownership and accounting. */
    private static void assertPublication(TerrainPublicationGenerationGuard<Task> guard, long section,
                                          Object candidate, Expected expected) {
        Object old = new Object();
        var current = new java.util.concurrent.atomic.AtomicReference<>(old);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var before = guard.snapshot();
        Object retired = guard.publish(section, candidate, () -> {
            calls.incrementAndGet();
            return current.getAndSet(candidate);
        });
        var after = guard.snapshot();
        if (expected == Expected.REJECT_STALE) {
            assertSame(candidate, retired, "vanilla must retire the rejected candidate, not the displayed mesh");
            assertSame(old, current.get());
            assertEquals(0, calls.get());
        } else {
            assertSame(old, retired);
            assertSame(candidate, current.get());
            assertEquals(1, calls.get());
        }
        assertEquals(before.allowedPublications() + (expected == Expected.ALLOW_CURRENT ? 1 : 0),
                after.allowedPublications());
        assertEquals(before.rejectedStalePublications() + (expected == Expected.REJECT_STALE ? 1 : 0),
                after.rejectedStalePublications());
        assertEquals(before.baselinePublicationsAfterFailOpen() + (expected == Expected.BASELINE_ALLOW ? 1 : 0),
                after.baselinePublicationsAfterFailOpen());
    }

    private static final TerrainPublicationGenerationGuard.TaskOps<Task> OPS =
            new TerrainPublicationGenerationGuard.TaskOps<>() {
                @Override
                public boolean isCancelled(Task task) {
                    return task.cancelled;
                }

                @Override
                public void cancel(Task task) {
                    task.cancelled = true;
                }
            };

    private static final class Fixture {
        final TerrainPublicationGenerationGuard<Task> guard =
                new TerrainPublicationGenerationGuard<>(
                        new TerrainPublicationGenerationGuard.Config(true, 32, 32),
                        OPS
                );
    }

    private static final class Task {
        volatile boolean cancelled;
    }
}
