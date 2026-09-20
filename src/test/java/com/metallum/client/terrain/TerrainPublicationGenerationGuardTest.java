package com.metallum.client.terrain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void unreferencedSectionMetadataEvictsWithinTheConfiguredBound() {
        var bounded = new TerrainPublicationGenerationGuard<>(
                new TerrainPublicationGenerationGuard.Config(true, 1, 4, 4),
                OPS
        );
        Task first = new Task();
        bounded.registerTask(first, 21L);
        assertTrue(bounded.enterTask(first));
        assertEquals(
                TerrainPublicationGenerationGuard.PublicationDecision.ALLOW_CURRENT,
                bounded.publicationDecision(21L, null)
        );
        bounded.exitTask(first);

        Task second = new Task();
        bounded.registerTask(second, 22L);
        assertTrue(bounded.snapshot().active());
        assertEquals(1, bounded.snapshot().sectionVersionEntries());
        assertTrue(bounded.enterTask(second));
        assertEquals(
                TerrainPublicationGenerationGuard.PublicationDecision.ALLOW_CURRENT,
                bounded.publicationDecision(22L, null)
        );
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
        assertEquals(
                TerrainPublicationGenerationGuard.PublicationDecision.BASELINE_ALLOW,
                bounded.publicationDecision(31L, mesh)
        );
    }

    @Test
    void untrackedInvalidationDoesNotCreateMetadataOrTraceEntries() {
        TerrainPublicationGenerationGuard<Task> guard = new TerrainPublicationGenerationGuard<>(
                new TerrainPublicationGenerationGuard.Config(true, 1, 4, 4),
                OPS,
                8
        );
        for (long sectionId = 100_000L; sectionId < 200_000L; sectionId++) {
            guard.markDirty(sectionId);
        }

        TerrainPublicationGenerationGuard.Evidence evidence = guard.snapshotEvidence();
        assertTrue(evidence.snapshot().active());
        assertEquals(0, evidence.snapshot().sectionVersionEntries());
        assertEquals(100_000L, evidence.snapshot().untrackedInvalidations());
        assertTrue(evidence.events().isEmpty());
        assertEquals(0L, evidence.droppedEvents());
    }

    @Test
    void unknownInvalidationDoesNotEvictLiveSectionOrFailOpen() {
        TerrainPublicationGenerationGuard<Task> guard = new TerrainPublicationGenerationGuard<>(
                new TerrainPublicationGenerationGuard.Config(true, 1, 4, 4),
                OPS
        );
        Task task = new Task();
        guard.registerTask(task, 201L);
        assertTrue(guard.enterTask(task));

        guard.markDirty(202L);
        assertTrue(guard.snapshot().active());
        assertEquals(1, guard.snapshot().sectionVersionEntries());
        assertEquals(1L, guard.snapshot().untrackedInvalidations());
        assertEquals(
                TerrainPublicationGenerationGuard.PublicationDecision.ALLOW_CURRENT,
                guard.publicationDecision(201L, null)
        );
        guard.exitTask(task);
    }

    @Test
    void evictedWorkerTokenStillRejectsAfterUnknownInvalidationAndReregistration() {
        TerrainPublicationGenerationGuard<Task> guard = new TerrainPublicationGenerationGuard<>(
                new TerrainPublicationGenerationGuard.Config(true, 1, 4, 4),
                OPS
        );
        Task stale = new Task();
        guard.registerTask(stale, 301L);
        assertTrue(guard.enterTask(stale));
        guard.markDirty(301L);

        Task other = new Task();
        guard.registerTask(other, 302L);
        guard.markDirty(302L);
        guard.markDirty(301L);
        assertEquals(1L, guard.snapshot().untrackedInvalidations());

        Task replacement = new Task();
        guard.registerTask(replacement, 301L);
        assertTrue(guard.snapshot().active());
        assertEquals(1, guard.snapshot().sectionVersionEntries());
        assertEquals(
                TerrainPublicationGenerationGuard.PublicationDecision.REJECT_STALE,
                guard.publicationDecision(301L, null)
        );
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

        assertEquals(
                TerrainPublicationGenerationGuard.PublicationDecision.REJECT_STALE,
                bounded.publicationDecision(41L, null)
        );
        assertEquals(1, bounded.snapshot().sectionVersionEntries(),
                "stale validation must be lookup-only and must not recreate evicted metadata");

        bounded.exitTask(stale);
        assertTrue(bounded.enterTask(replacementSection));
        assertEquals(
                TerrainPublicationGenerationGuard.PublicationDecision.ALLOW_CURRENT,
                bounded.publicationDecision(42L, null)
        );
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

    @Test
    void evidenceCapturesLifecycleAndPublicationVersionsWithoutIdentities() {
        TerrainPublicationGenerationGuard<Task> guard = new TerrainPublicationGenerationGuard<>(
                new TerrainPublicationGenerationGuard.Config(true, 8, 8, 8),
                OPS,
                16
        );
        Task task = new Task();
        Object mesh = new Object();

        guard.registerTask(task, 51L);
        assertTrue(guard.enterTask(task));
        guard.bindMeshFromActiveTask(mesh);
        guard.exitTask(task);
        assertEquals(
                TerrainPublicationGenerationGuard.PublicationDecision.ALLOW_CURRENT,
                guard.publicationDecision(51L, mesh)
        );

        TerrainPublicationGenerationGuard.Evidence evidence = guard.snapshotEvidence();
        assertEquals(16, evidence.eventCapacity());
        assertEquals(0L, evidence.droppedEvents());
        assertEquals(
                List.of(
                        TerrainPublicationGenerationGuard.EventKind.TASK_REGISTERED,
                        TerrainPublicationGenerationGuard.EventKind.MESH_BOUND,
                        TerrainPublicationGenerationGuard.EventKind.PUBLICATION
                ),
                evidence.events().stream()
                        .map(TerrainPublicationGenerationGuard.Event::kind)
                        .toList()
        );
        TerrainPublicationGenerationGuard.Event publication = evidence.events().get(2);
        assertEquals(3L, publication.sequence());
        assertEquals(51L, publication.sectionId());
        assertEquals(
                TerrainPublicationGenerationGuard.PublicationDecision.ALLOW_CURRENT,
                publication.decision()
        );
        assertNotNull(publication.captured());
        assertNotNull(publication.current());
        assertEquals(publication.captured(), publication.current());
    }

    @Test
    void generationEventsAndStalePublicationRetainCrossGenerationEvidence() {
        TerrainPublicationGenerationGuard<Task> guard = new TerrainPublicationGenerationGuard<>(
                new TerrainPublicationGenerationGuard.Config(true, 8, 8, 8),
                OPS,
                32
        );
        Task materialTask = new Task();
        Object materialMesh = new Object();
        guard.registerTask(materialTask, 61L);
        assertTrue(guard.enterTask(materialTask));
        guard.bindMeshFromActiveTask(materialMesh);
        guard.exitTask(materialTask);
        guard.advanceMaterialGeneration();
        assertEquals(
                TerrainPublicationGenerationGuard.PublicationDecision.REJECT_STALE,
                guard.publicationDecision(61L, materialMesh)
        );

        Task worldTask = new Task();
        guard.registerTask(worldTask, 62L);
        assertTrue(guard.enterTask(worldTask));
        guard.advanceWorldEpoch();
        assertEquals(
                TerrainPublicationGenerationGuard.PublicationDecision.REJECT_STALE,
                guard.publicationDecision(62L, null)
        );

        TerrainPublicationGenerationGuard.Evidence evidence = guard.snapshotEvidence();
        assertTrue(evidence.events().stream().anyMatch(event ->
                event.kind() == TerrainPublicationGenerationGuard.EventKind.MATERIAL_GENERATION_ADVANCED
                        && event.cancelled()));
        assertTrue(evidence.events().stream().anyMatch(event ->
                event.kind() == TerrainPublicationGenerationGuard.EventKind.WORLD_EPOCH_ADVANCED
                        && event.cancelled()));
        TerrainPublicationGenerationGuard.Event stale = evidence.events().stream()
                .filter(event -> event.kind() == TerrainPublicationGenerationGuard.EventKind.PUBLICATION
                        && event.decision() == TerrainPublicationGenerationGuard.PublicationDecision.REJECT_STALE)
                .findFirst()
                .orElseThrow();
        assertNotNull(stale.captured());
        assertNotNull(stale.current());
        assertTrue(stale.captured().materialGeneration() < stale.current().materialGeneration()
                || stale.captured().worldEpoch() < stale.current().worldEpoch());
    }

    @Test
    void overflowDropsTraceEntriesWithoutChangingPublicationDecision() {
        TerrainPublicationGenerationGuard<Task> guard = new TerrainPublicationGenerationGuard<>(
                new TerrainPublicationGenerationGuard.Config(true, 8, 8, 8),
                OPS,
                1
        );
        Task task = new Task();
        Object mesh = new Object();
        guard.registerTask(task, 71L);
        assertTrue(guard.enterTask(task));
        guard.bindMeshFromActiveTask(mesh);
        guard.exitTask(task);
        assertEquals(
                TerrainPublicationGenerationGuard.PublicationDecision.ALLOW_CURRENT,
                guard.publicationDecision(71L, mesh)
        );
        TerrainPublicationGenerationGuard.Evidence evidence = guard.snapshotEvidence();
        assertEquals(1, evidence.events().size());
        assertTrue(evidence.droppedEvents() >= 2L);
        assertEquals(1L, evidence.snapshot().allowedPublications());
        assertFalse(evidence.snapshot().failOpen());
    }

    @Test
    void disabledTraceUsesNoEventBufferAndUnknownPublicationStillUsesBaseline() {
        TerrainPublicationGenerationGuard<Task> guard = new TerrainPublicationGenerationGuard<>(
                new TerrainPublicationGenerationGuard.Config(true, 8, 8, 8),
                OPS
        );
        assertEquals(
                TerrainPublicationGenerationGuard.PublicationDecision.BASELINE_ALLOW,
                guard.publicationDecision(81L, new Object())
        );
        TerrainPublicationGenerationGuard.Evidence evidence = guard.snapshotEvidence();
        assertEquals(0, evidence.eventCapacity());
        assertTrue(evidence.events().isEmpty());
        assertEquals(0L, evidence.droppedEvents());
        assertEquals(
                TerrainPublicationGenerationGuard.FailOpenReason.UNKNOWN_PUBLICATION,
                evidence.snapshot().failOpenReason()
        );
    }

    @Test
    void unknownFailOpenIsExplicitlyRecordedWhenTraceIsEnabled() {
        TerrainPublicationGenerationGuard<Task> guard = new TerrainPublicationGenerationGuard<>(
                new TerrainPublicationGenerationGuard.Config(true, 8, 8, 8),
                OPS,
                8
        );
        assertEquals(
                TerrainPublicationGenerationGuard.PublicationDecision.BASELINE_ALLOW,
                guard.publicationDecision(91L, new Object())
        );
        TerrainPublicationGenerationGuard.Evidence evidence = guard.snapshotEvidence();
        assertTrue(evidence.events().stream().anyMatch(event ->
                event.kind() == TerrainPublicationGenerationGuard.EventKind.FAIL_OPEN
                        && event.reason() == TerrainPublicationGenerationGuard.EventReason.UNKNOWN_PUBLICATION));
        TerrainPublicationGenerationGuard.Event publication = evidence.events().stream()
                .filter(event -> event.kind() == TerrainPublicationGenerationGuard.EventKind.PUBLICATION)
                .findFirst()
                .orElseThrow();
        assertEquals(
                TerrainPublicationGenerationGuard.EventReason.UNKNOWN_PUBLICATION,
                publication.reason()
        );
        assertEquals(
                TerrainPublicationGenerationGuard.PublicationDecision.BASELINE_ALLOW,
                publication.decision()
        );
    }

    @Test
    void publicationActionHoldsGuardMonitorUntilInvalidationCanComplete() throws Exception {
        TerrainPublicationGenerationGuard<Task> guard = new TerrainPublicationGenerationGuard<>(
                new TerrainPublicationGenerationGuard.Config(true, 8, 8, 8),
                OPS
        );
        Task first = new Task();
        Task second = new Task();
        Object firstMesh = new Object();
        Object secondMesh = new Object();
        stageMesh(guard, first, firstMesh, 101L);
        stageMesh(guard, second, secondMesh, 101L);

        AtomicReference<Thread> mutationThread = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "terrain-publication-mutation");
            mutationThread.set(thread);
            return thread;
        });
        CountDownLatch publicationEntered = new CountDownLatch(1);
        CountDownLatch mutationAttempted = new CountDownLatch(1);
        CountDownLatch mutationDone = new CountDownLatch(1);
        Future<?> mutation;
        AtomicInteger actionCalls = new AtomicInteger();
        try {
            mutation = executor.submit(() -> {
                awaitLatch(publicationEntered);
                mutationAttempted.countDown();
                guard.markDirty(101L);
                mutationDone.countDown();
            });

            TerrainPublicationGenerationGuard.PublicationDecision decision =
                    guard.withPublicationDecision(101L, firstMesh, publicationDecision -> {
                        actionCalls.incrementAndGet();
                        assertTrue(Thread.holdsLock(guard));
                        publicationEntered.countDown();
                        awaitLatch(mutationAttempted);
                        Thread contender = mutationThread.get();
                        assertNotNull(contender);
                        awaitBlocked(contender);
                        assertEquals(1L, mutationDone.getCount());
                        return publicationDecision;
                    });

            assertEquals(
                    TerrainPublicationGenerationGuard.PublicationDecision.ALLOW_CURRENT,
                    decision
            );
            assertEquals(1, actionCalls.get());
            assertTrue(mutationDone.await(1, TimeUnit.SECONDS));
            mutation.get(1, TimeUnit.SECONDS);
            assertEquals(
                    TerrainPublicationGenerationGuard.PublicationDecision.REJECT_STALE,
                    guard.publicationDecision(101L, secondMesh)
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void stalePublicationActionReceivesRejectExactlyOnce() {
        TerrainPublicationGenerationGuard<Task> guard = new TerrainPublicationGenerationGuard<>(
                new TerrainPublicationGenerationGuard.Config(true, 8, 8, 8),
                OPS
        );
        Task task = new Task();
        Object mesh = new Object();
        stageMesh(guard, task, mesh, 111L);
        guard.markDirty(111L);
        AtomicInteger actionCalls = new AtomicInteger();

        TerrainPublicationGenerationGuard.PublicationDecision decision =
                guard.withPublicationDecision(111L, mesh, publicationDecision -> {
                    assertTrue(Thread.holdsLock(guard));
                    assertEquals(
                            TerrainPublicationGenerationGuard.PublicationDecision.REJECT_STALE,
                            publicationDecision
                    );
                    actionCalls.incrementAndGet();
                    return publicationDecision;
                });

        assertEquals(
                TerrainPublicationGenerationGuard.PublicationDecision.REJECT_STALE,
                decision
        );
        assertEquals(1, actionCalls.get());
    }

    @Test
    void publicationActionExceptionPropagatesAndGuardCanInvalidateAfterward() throws Exception {
        TerrainPublicationGenerationGuard<Task> guard = new TerrainPublicationGenerationGuard<>(
                new TerrainPublicationGenerationGuard.Config(true, 8, 8, 8),
                OPS
        );
        Task first = new Task();
        Task second = new Task();
        Object firstMesh = new Object();
        Object secondMesh = new Object();
        stageMesh(guard, first, firstMesh, 121L);
        stageMesh(guard, second, secondMesh, 121L);
        RuntimeException expected = new RuntimeException("publication failed");

        RuntimeException actual = assertThrows(RuntimeException.class, () ->
                guard.withPublicationDecision(121L, firstMesh, publicationDecision -> {
                    assertEquals(
                            TerrainPublicationGenerationGuard.PublicationDecision.ALLOW_CURRENT,
                            publicationDecision
                    );
                    throw expected;
                }));
        assertSame(expected, actual);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> invalidation = executor.submit(() -> guard.markDirty(121L));
            invalidation.get(1, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertTrue(second.cancelled);
        assertEquals(
                TerrainPublicationGenerationGuard.PublicationDecision.REJECT_STALE,
                guard.publicationDecision(121L, secondMesh)
        );
    }

    private static void stageMesh(
            TerrainPublicationGenerationGuard<Task> guard,
            Task task,
            Object mesh,
            long sectionId
    ) {
        guard.registerTask(task, sectionId);
        assertTrue(guard.enterTask(task));
        guard.bindMeshFromActiveTask(mesh);
        guard.exitTask(task);
    }

    private static void awaitLatch(CountDownLatch latch) {
        assertTrue(awaitLatch(latch, 1, TimeUnit.SECONDS));
    }

    private static boolean awaitLatch(CountDownLatch latch, long timeout, TimeUnit unit) {
        try {
            return latch.await(timeout, unit);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for mutation attempt", interrupted);
        }
    }

    private static void awaitBlocked(Thread contender) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (contender.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(Thread.State.BLOCKED, contender.getState());
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
