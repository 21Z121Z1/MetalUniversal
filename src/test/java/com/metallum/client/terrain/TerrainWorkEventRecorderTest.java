package com.metallum.client.terrain;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerrainWorkEventRecorderTest {
    private static TerrainWorkEventRecorder.WorkKey key(final long revision) {
        return new TerrainWorkEventRecorder.WorkKey(3L, 42L, revision, revision + 10L, 7L);
    }

    @Test
    void preservesGenerationAndActualDrawFields() {
        TerrainWorkEventRecorder recorder = new TerrainWorkEventRecorder(8);
        long now = System.nanoTime();

        recorder.record(
                key(11L),
                TerrainWorkEventRecorder.Stage.PUBLISHED,
                now,
                1024L,
                "all-layers-ready",
                "main",
                TerrainWorkEventRecorder.NO_FRAME,
                19L
        );
        recorder.record(
                key(11L),
                TerrainWorkEventRecorder.Stage.FIRST_VALID_DRAW,
                now + 1L,
                0L,
                "draw-authority",
                "main",
                23L,
                19L
        );

        TerrainWorkEventRecorder.Snapshot snapshot = recorder.snapshot();
        assertEquals(2, snapshot.events().size());
        assertFalse(snapshot.overflowed());
        assertEquals(0L, snapshot.droppedEvents());

        TerrainWorkEventRecorder.Event draw = snapshot.events().get(1);
        assertEquals(TerrainWorkEventRecorder.Stage.FIRST_VALID_DRAW, draw.stage());
        assertEquals(11L, draw.key().geometryRevision());
        assertEquals(21L, draw.key().lightingRevision());
        assertEquals(23L, draw.frameIndex());
        assertEquals(19L, draw.meshGeneration());
        assertTrue(draw.hasFrameIndex());
        assertTrue(draw.hasMeshGeneration());
    }

    @Test
    void boundsMemoryAndReportsOverwrittenEvents() {
        TerrainWorkEventRecorder recorder = new TerrainWorkEventRecorder(4);
        long now = System.nanoTime();

        for (int i = 0; i < 6; i++) {
            recorder.record(
                    key(i),
                    TerrainWorkEventRecorder.Stage.QUEUED,
                    now + i,
                    i,
                    "test",
                    "main",
                    TerrainWorkEventRecorder.NO_FRAME,
                    TerrainWorkEventRecorder.NO_MESH_GENERATION
            );
        }

        TerrainWorkEventRecorder.Snapshot snapshot = recorder.snapshot();
        assertEquals(6L, snapshot.claimedEvents());
        assertEquals(4, snapshot.events().size());
        assertEquals(2L, snapshot.droppedEvents());
        assertTrue(snapshot.overflowed());
        assertEquals(2L, snapshot.events().getFirst().sequence());
        assertEquals(5L, snapshot.events().getLast().sequence());
    }

    @Test
    void concurrentWritersPublishWholeEvents() throws Exception {
        int writers = 4;
        int eventsPerWriter = 128;
        TerrainWorkEventRecorder recorder = new TerrainWorkEventRecorder(1024);
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);

        try {
            for (int writer = 0; writer < writers; writer++) {
                final int writerId = writer;
                executor.execute(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < eventsPerWriter; i++) {
                            long revision = writerId * 1000L + i;
                            recorder.record(
                                    key(revision),
                                    TerrainWorkEventRecorder.Stage.BUILD_END,
                                    System.nanoTime(),
                                    i,
                                    "concurrent-test",
                                    "main",
                                    TerrainWorkEventRecorder.NO_FRAME,
                                    TerrainWorkEventRecorder.NO_MESH_GENERATION
                            );
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        TerrainWorkEventRecorder.Snapshot snapshot = recorder.snapshot();
        assertEquals(writers * eventsPerWriter, snapshot.events().size());
        assertEquals(0L, snapshot.droppedEvents());
        Set<Long> sequences = new HashSet<>();
        snapshot.events().forEach(event -> sequences.add(event.sequence()));
        assertEquals(writers * eventsPerWriter, sequences.size());
    }

    @Test
    void rejectsIncompleteDrawEventsInsteadOfInventingIdentity() {
        TerrainWorkEventRecorder recorder = new TerrainWorkEventRecorder(4);
        long now = System.nanoTime();

        assertThrows(IllegalArgumentException.class, () -> recorder.record(
                key(1L),
                TerrainWorkEventRecorder.Stage.FIRST_VALID_DRAW,
                now,
                0L,
                "bad",
                "main",
                TerrainWorkEventRecorder.NO_FRAME,
                1L
        ));
        assertThrows(IllegalArgumentException.class, () -> recorder.record(
                key(1L),
                TerrainWorkEventRecorder.Stage.PUBLISHED,
                now,
                0L,
                "bad",
                "main",
                TerrainWorkEventRecorder.NO_FRAME,
                TerrainWorkEventRecorder.NO_MESH_GENERATION
        ));
    }
}
