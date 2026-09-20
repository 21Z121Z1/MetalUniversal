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
                tracker.publish(section, sharedEmptySentinel, t + 3, "empty-mesh");
        assertNotNull(token);

        // Once the compiler context is consumed, the shared sentinel itself is not treated as an
        // identity-bearing mesh and cannot be reused to fabricate a second publication.
        assertNull(tracker.publish(section, sharedEmptySentinel, t + 4, "bad-reuse"));
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
