package com.metallum.client.terrain;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VanillaTerrainWorkTrackerTest {
    @Test
    void publicationRejectsADifferentSectionWithoutConsumingTheRealOwner() {
        Fixture f = new Fixture();
        f.build();
        assertNull(f.tracker.publish(8L, f.mesh, f.now(), "wrong-section"));
        assertNotNull(f.tracker.publish(7L, f.mesh, f.now(), "correct-section"));
        assertEquals(1L, f.count(TerrainWorkEventRecorder.Stage.PUBLISHED));
    }

    @Test
    void dirtyDuringUploadRejectsPublicationForEveryRevisionKind() {
        for (VanillaTerrainWorkTracker.DirtyKind kind : VanillaTerrainWorkTracker.DirtyKind.values()) {
            Fixture f = new Fixture();
            f.build();
            f.tracker.uploadQueued(f.mesh, 256L, "staging", f.now());
            f.tracker.markDirty(7L, kind);
            assertNull(f.tracker.publish(7L, f.mesh, f.now(), "late-upload"), kind.toString());
            assertEquals(0L, f.count(TerrainWorkEventRecorder.Stage.PUBLISHED));
            assertEquals(1L, f.count(TerrainWorkEventRecorder.Stage.CANCELLED));
        }
    }

    @Test
    void materialInvalidationRejectsBothQueuedAndAlreadyBuiltWork() {
        Fixture queued = new Fixture();
        queued.tracker.advanceMaterialGeneration();
        assertFalse(queued.tracker.beginBuild(queued.region, queued.now()));
        assertEquals(1L, queued.count(TerrainWorkEventRecorder.Stage.CANCELLED));

        Fixture built = new Fixture();
        built.build();
        built.tracker.advanceMaterialGeneration();
        assertNull(built.tracker.publish(7L, built.mesh, built.now(), "late-reload"));
        assertEquals(1L, built.count(TerrainWorkEventRecorder.Stage.CANCELLED));
    }

    @Test
    void publicationRequiresCompletedBuild() {
        Fixture f = new Fixture();
        assertTrue(f.tracker.beginBuild(f.region, f.now()));
        f.tracker.bindConstructedMesh(f.mesh);
        assertNull(f.tracker.publish(7L, f.mesh, f.now(), "incomplete-build"));
        f.tracker.endBuild(f.now());
        assertNotNull(f.tracker.publish(7L, f.mesh, f.now(), "complete-build"));
    }

    @Test
    void releasingUnpublishedMeshRecordsCancellationExactlyOnce() {
        Fixture f = new Fixture();
        f.build();
        f.tracker.uploadQueued(f.mesh, 256L, "staging", f.now());
        f.tracker.retireMesh(f.mesh, f.now(), "cancelled-upload");
        f.tracker.retireMesh(f.mesh, f.now(), "duplicate-release");
        assertEquals(1L, f.count(TerrainWorkEventRecorder.Stage.CANCELLED));
        assertEquals(0L, f.count(TerrainWorkEventRecorder.Stage.RETIRED));
        assertNull(f.tracker.publish(7L, f.mesh, f.now(), "late-callback"));
    }

    @Test
    void sectionResetRetiresOldPublishedMeshAsWellAsCancellingReplacement() {
        Fixture f = new Fixture();
        f.build();
        VanillaTerrainWorkTracker.DrawToken old = f.tracker.publish(7L, f.mesh, f.now(), "first");
        assertNotNull(old);
        f.tracker.markDirty(7L, VanillaTerrainWorkTracker.DirtyKind.GEOMETRY);
        f.tracker.beginWork(7L, new Object(), true, f.now());
        f.tracker.invalidateSection(7L, f.now(), "section-reset");
        f.tracker.firstValidDraw(old, 0L, f.now());
        assertNull(f.tracker.drawTokenForMesh(f.mesh));
        assertEquals(1L, f.count(TerrainWorkEventRecorder.Stage.RETIRED));
        assertEquals(1L, f.count(TerrainWorkEventRecorder.Stage.CANCELLED));
        assertEquals(0L, f.count(TerrainWorkEventRecorder.Stage.FIRST_VALID_DRAW));
    }

    @Test
    void lateFirstDrawAfterDirtyOrReloadCannotBeReportedAsValid() {
        for (boolean reload : List.of(false, true)) {
            Fixture f = new Fixture();
            f.build();
            VanillaTerrainWorkTracker.DrawToken token = f.tracker.publish(7L, f.mesh, f.now(), "ready");
            assertNotNull(token);
            if (reload) {
                f.tracker.advanceMaterialGeneration();
            } else {
                f.tracker.markDirty(7L, VanillaTerrainWorkTracker.DirtyKind.LIGHTING);
            }
            f.tracker.firstValidDraw(token, 0L, f.now());
            assertEquals(0L, f.count(TerrainWorkEventRecorder.Stage.FIRST_VALID_DRAW));
        }
    }

    private static final class Fixture {
        final VanillaTerrainWorkTracker tracker = new VanillaTerrainWorkTracker(new TerrainWorkEventRecorder(64));
        final Object region = new Object();
        final Object mesh = new Object();

        Fixture() {
            tracker.beginWork(7L, region, true, now());
        }

        void build() {
            assertTrue(tracker.beginBuild(region, now()));
            tracker.endBuild(now());
            tracker.bindConstructedMesh(mesh);
        }

        long now() {
            return System.nanoTime();
        }

        long count(TerrainWorkEventRecorder.Stage stage) {
            return tracker.snapshot().events().stream().filter(event -> event.stage() == stage).count();
        }
    }

    @Test
    void unknownMeshCannotBorrowTheActiveBuildAndEmptyIsExplicit() {
        Fixture f = new Fixture();
        f.build();
        assertNull(f.tracker.publish(7L, new Object(), f.now(), "unknown-mesh"));
        assertNull(f.tracker.publish(7L, null, f.now(), "null-mesh"));
        assertNull(f.tracker.publishEmpty(8L, f.now(), "wrong-empty-section"));
        assertNotNull(f.tracker.publishEmpty(7L, f.now(), "known-empty"));
        assertNull(f.tracker.publishEmpty(7L, f.now(), "duplicate-empty"));
    }

    @Test
    void rebindingKnownMeshCannotStealAnotherSectionsGeneration() {
        Fixture f = new Fixture();
        f.build();
        f.tracker.uploadQueued(f.mesh, 256L, "staging", f.now());
        Object secondRegion = new Object();
        f.tracker.beginWork(8L, secondRegion, false, f.now());
        assertTrue(f.tracker.beginBuild(secondRegion, f.now()));
        f.tracker.endBuild(f.now());
        f.tracker.bindConstructedMesh(f.mesh);
        VanillaTerrainWorkTracker.DrawToken token = f.tracker.publish(7L, f.mesh, f.now(), "original-owner");
        assertNotNull(token);
        assertEquals(7L, token.key().sectionId());
        assertNotNull(f.tracker.publishEmpty(8L, f.now(), "second-empty"));
    }

    @Test
    void workerReturningAfterWorldSwitchCannotPublishEvenAnEmptyResult() throws Exception {
        Fixture f = new Fixture();
        java.util.concurrent.CountDownLatch built = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch switched = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> {
                f.build();
                built.countDown();
                assertTrue(switched.await(5, java.util.concurrent.TimeUnit.SECONDS));
                f.tracker.bindConstructedMesh(new Object());
                assertNull(f.tracker.publishEmpty(7L, f.now(), "late-empty"));
                return f.tracker.publish(7L, f.mesh, f.now(), "late-mesh");
            });
            try {
                assertTrue(built.await(5, java.util.concurrent.TimeUnit.SECONDS));
                f.tracker.advanceWorldEpoch(f.now());
            } finally {
                switched.countDown();
            }
            assertNull(result.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(0L, f.count(TerrainWorkEventRecorder.Stage.PUBLISHED));
        assertEquals(1L, f.count(TerrainWorkEventRecorder.Stage.CANCELLED));
    }
    @Test
    void carriesRealRevisionsFromDirtyMutationThroughFirstDraw() {
        TerrainWorkEventRecorder recorder = new TerrainWorkEventRecorder(64);
        VanillaTerrainWorkTracker tracker = new VanillaTerrainWorkTracker(recorder);
        long section = 1234L;
        Object region = new Object();
        Object mesh = new Object();
        long t = System.nanoTime();

        tracker.markDirty(section, VanillaTerrainWorkTracker.DirtyKind.GEOMETRY_AND_LIGHTING);
        tracker.beginWork(section, region, true, t);
        assertTrue(tracker.beginBuild(region, t + 1));
        tracker.endBuild(t + 2);
        tracker.bindConstructedMesh(mesh);
        tracker.uploadQueued(mesh, 4096L, "solid-staging", t + 3);
        tracker.gpuEncoded(mesh, 4096L, "solid-copy-encoded", t + 4);
        VanillaTerrainWorkTracker.DrawToken token =
                tracker.publish(section, mesh, t + 5, "all-layers-ready");
        assertNotNull(token);
        long frame = tracker.nextFrameIndex();
        tracker.firstValidDraw(token, frame, t + 6);
        tracker.firstValidDraw(token, frame + 1, t + 7);

        TerrainWorkEventRecorder.Snapshot snapshot = tracker.snapshot();
        List<TerrainWorkEventRecorder.Event> events = snapshot.events();
        assertEquals(List.of(
                        TerrainWorkEventRecorder.Stage.DATA_READY,
                        TerrainWorkEventRecorder.Stage.QUEUED,
                        TerrainWorkEventRecorder.Stage.BUILD_START,
                        TerrainWorkEventRecorder.Stage.BUILD_END,
                        TerrainWorkEventRecorder.Stage.UPLOAD_QUEUED,
                        TerrainWorkEventRecorder.Stage.GPU_ENCODED,
                        TerrainWorkEventRecorder.Stage.PUBLISHED,
                        TerrainWorkEventRecorder.Stage.FIRST_VALID_DRAW
                ),
                events.stream().map(TerrainWorkEventRecorder.Event::stage).toList());
        TerrainWorkEventRecorder.WorkKey key = events.getFirst().key();
        assertEquals(2L, key.geometryRevision());
        assertEquals(2L, key.lightingRevision());
        assertEquals(key, token.key());
        assertEquals(token.meshGeneration(), events.getLast().meshGeneration());
        assertEquals(frame, events.getLast().frameIndex());
    }

    @Test
    void separateDirtyKindsAdvanceOnlyTheProvenRevision() {
        TerrainWorkEventRecorder recorder = new TerrainWorkEventRecorder(32);
        VanillaTerrainWorkTracker tracker = new VanillaTerrainWorkTracker(recorder);
        long section = 77L;

        tracker.markDirty(section, VanillaTerrainWorkTracker.DirtyKind.GEOMETRY);
        Object first = new Object();
        tracker.beginWork(section, first, false, System.nanoTime());
        assertTrue(tracker.beginBuild(first, System.nanoTime()));
        tracker.endBuild(System.nanoTime());
        Object firstMesh = new Object();
        tracker.bindConstructedMesh(firstMesh);
        VanillaTerrainWorkTracker.DrawToken firstToken =
                tracker.publish(section, firstMesh, System.nanoTime(), "direct");
        assertNotNull(firstToken);
        assertEquals(2L, firstToken.key().geometryRevision());
        assertEquals(1L, firstToken.key().lightingRevision());

        tracker.markDirty(section, VanillaTerrainWorkTracker.DirtyKind.LIGHTING);
        Object second = new Object();
        tracker.beginWork(section, second, false, System.nanoTime());
        assertTrue(tracker.beginBuild(second, System.nanoTime()));
        tracker.endBuild(System.nanoTime());
        Object secondMesh = new Object();
        tracker.bindConstructedMesh(secondMesh);
        VanillaTerrainWorkTracker.DrawToken secondToken =
                tracker.publish(section, secondMesh, System.nanoTime(), "direct");
        assertNotNull(secondToken);
        assertEquals(2L, secondToken.key().geometryRevision());
        assertEquals(2L, secondToken.key().lightingRevision());
    }

    @Test
    void supersededWorkCancelsInsteadOfBeingReattributed() {
        TerrainWorkEventRecorder recorder = new TerrainWorkEventRecorder(32);
        VanillaTerrainWorkTracker tracker = new VanillaTerrainWorkTracker(recorder);
        long section = 99L;
        Object oldRegion = new Object();
        Object newRegion = new Object();
        long t = System.nanoTime();

        tracker.beginWork(section, oldRegion, true, t);
        tracker.markDirty(section, VanillaTerrainWorkTracker.DirtyKind.GEOMETRY_AND_LIGHTING);
        tracker.beginWork(section, newRegion, true, t + 1);

        assertFalse(tracker.beginBuild(oldRegion, t + 2), "cancelled old snapshot must fail closed");
        assertTrue(tracker.beginBuild(newRegion, t + 3));

        List<TerrainWorkEventRecorder.Event> events = tracker.snapshot().events();
        assertTrue(events.stream().anyMatch(event ->
                event.stage() == TerrainWorkEventRecorder.Stage.CANCELLED
                        && event.key().geometryRevision() == 1L));
        assertTrue(events.stream().anyMatch(event ->
                event.stage() == TerrainWorkEventRecorder.Stage.BUILD_START
                        && event.key().geometryRevision() == 2L));
    }

    @Test
    void emptyPublicationUsesOnlyTheActiveBuildContext() {
        TerrainWorkEventRecorder recorder = new TerrainWorkEventRecorder(32);
        VanillaTerrainWorkTracker tracker = new VanillaTerrainWorkTracker(recorder);
        Object sharedEmptySentinel = new Object();
        Object region = new Object();
        long section = 42L;
        long t = System.nanoTime();

        tracker.beginWork(section, region, false, t);
        assertTrue(tracker.beginBuild(region, t + 1));
        tracker.endBuild(t + 2);
        VanillaTerrainWorkTracker.DrawToken token =
                tracker.publishEmpty(section, t + 3, "empty-mesh");
        assertNotNull(token);

        // Once the compiler context is consumed, the shared sentinel itself is not treated as an
        // identity-bearing mesh and cannot be reused to fabricate a second publication.
        assertNull(tracker.publishEmpty(section, t + 4, "bad-reuse"));
        assertNull(tracker.drawTokenForMesh(sharedEmptySentinel));
    }

    @Test
    void worldEpochInvalidatesOutstandingWorkAndResetsSectionRevisions() {
        TerrainWorkEventRecorder recorder = new TerrainWorkEventRecorder(64);
        VanillaTerrainWorkTracker tracker = new VanillaTerrainWorkTracker(recorder);
        long section = 5L;
        Object region = new Object();
        long t = System.nanoTime();

        tracker.markDirty(section, VanillaTerrainWorkTracker.DirtyKind.GEOMETRY_AND_LIGHTING);
        tracker.beginWork(section, region, true, t);
        long newEpoch = tracker.advanceWorldEpoch(t + 1);
        assertEquals(2L, newEpoch);
        assertFalse(tracker.beginBuild(region, t + 2));

        Object nextRegion = new Object();
        tracker.beginWork(section, nextRegion, false, t + 3);
        TerrainWorkEventRecorder.Event last = tracker.snapshot().events().getLast();
        assertEquals(TerrainWorkEventRecorder.Stage.DATA_READY, last.stage());
        assertEquals(2L, last.key().worldEpoch());
        assertEquals(1L, last.key().geometryRevision());
        assertEquals(1L, last.key().lightingRevision());
    }
}
