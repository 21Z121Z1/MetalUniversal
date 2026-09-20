package com.metallum.client.terrain;

import org.junit.jupiter.api.Test;

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

        assertEquals(
                TerrainPublicationGenerationGuard.PublicationDecision.ALLOW_CURRENT,
                f.guard.publicationDecision(7L, mesh)
        );
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
        assertEquals(
                TerrainPublicationGenerationGuard.PublicationDecision.ALLOW_CURRENT,
                f.guard.publicationDecision(9L, null)
        );
        f.guard.exitTask(current);
    }

    @Test
    void dirtyDuringBuildRejectsDirectEmptyOrBlockEntityPublication() {
        Fixture f = new Fixture();
        Task task = new Task();
        f.guard.registerTask(task, 11L);
        assertTrue(f.guard.enterTask(task));
        f.guard.markDirty(11L);

        assertEquals(
                TerrainPublicationGenerationGuard.PublicationDecision.REJECT_STALE,
                f.guard.publicationDecision(11L, null)
        );
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

        assertEquals(
                TerrainPublicationGenerationGuard.PublicationDecision.REJECT_STALE,
                f.guard.publicationDecision(12L, mesh)
        );
    }

    @Test
    void sectionMismatchRejectsRecycledRenderSectionPublication() {
        Fixture f = new Fixture();
        Task task = new Task();
        f.guard.registerTask(task, 13L);
        assertTrue(f.guard.enterTask(task));
        assertEquals(
                TerrainPublicationGenerationGuard.PublicationDecision.REJECT_STALE,
                f.guard.publicationDecision(14L, null)
        );
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
            assertEquals(
                    TerrainPublicationGenerationGuard.PublicationDecision.REJECT_STALE,
                    f.guard.publicationDecision(15L, mesh)
            );
        }
    }

    @Test
    void unknownPublicationFailsOpenInsteadOfDroppingVanillaWork() {
        Fixture f = new Fixture();
        assertEquals(
                TerrainPublicationGenerationGuard.PublicationDecision.BASELINE_ALLOW,
                f.guard.publicationDecision(16L, new Object())
        );
        assertFalse(f.guard.snapshot().active());
        assertEquals(
                TerrainPublicationGenerationGuard.FailOpenReason.UNKNOWN_PUBLICATION,
                f.guard.snapshot().failOpenReason()
        );
        assertEquals(1L, f.guard.snapshot().unknownPublicationFailOpenCount());

        assertEquals(
                TerrainPublicationGenerationGuard.PublicationDecision.BASELINE_ALLOW,
                f.guard.publicationDecision(16L, new Object())
        );
        assertEquals(2L, f.guard.snapshot().baselinePublicationsAfterFailOpen());
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
        boolean cancelled;
    }
}
