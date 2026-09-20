package com.metallum.client.validation.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WorldStageRecorderTest {
    @Test
    void serializesOrderedRelativeOffsetsAndStageMetadata() {
        AtomicLong clock = new AtomicLong(1_000L);
        WorldStageRecorder recorder = new WorldStageRecorder(4, clock::get);
        long context = recorder.contextId(new Object());
        recorder.record(WorldStageRecorder.Stage.CHUNK_INSTALL, context, 1_005L, 1_010L,
                true, 2L, 1L, 4L, 1);
        recorder.record(WorldStageRecorder.Stage.LIGHT_TASK, context, 1_020L, 1_040L,
                false, -1L, -1L, 1L, 0);

        WorldStageRecorder.Snapshot snapshot = recorder.snapshot();
        assertEquals(2L, snapshot.eventCount());
        assertEquals(1L, snapshot.events().get(0).sequence());
        assertEquals(5L, snapshot.events().get(0).startOffsetNanos());
        assertEquals(10L, snapshot.events().get(0).endOffsetNanos());
        assertEquals("client-main", snapshot.events().get(1).role());
        assertEquals("client-level", snapshot.events().get(1).contextKind());
        assertEquals(1, snapshot.events().get(0).resultCode());
        assertEquals(0, snapshot.events().get(1).resultCode());
    }

    @Test
    void rejectsInvalidValuesWithoutWritingRows() {
        WorldStageRecorder recorder = new WorldStageRecorder(4);
        long context = recorder.contextId(new Object());
        long now = System.nanoTime();
        recorder.record(null, context, now, now, true, -1L, -1L, -1L, -1);
        recorder.record(WorldStageRecorder.Stage.SERVER_TICK, -1L, now, now, true,
                -1L, -1L, -1L, -1);
        recorder.record(WorldStageRecorder.Stage.SERVER_TICK, context, now + 10L, now,
                true, -1L, -1L, -1L, -1);
        recorder.record(WorldStageRecorder.Stage.SERVER_TICK, context, now, now,
                true, -2L, -1L, -1L, -1);
        recorder.record(WorldStageRecorder.Stage.SERVER_TICK, context, now, now,
                true, -1L, -1L, -1L, 2);
        WorldStageRecorder.Snapshot snapshot = recorder.snapshot();
        assertEquals(0L, snapshot.eventCount());
        assertEquals(5L, snapshot.invalidEvents());
    }

    @Test
    void boundedCapacityDropsLaterEventsAndKeepsEarlierRows() {
        WorldStageRecorder recorder = new WorldStageRecorder(2);
        long context = recorder.contextId(new Object());
        long now = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            recorder.record(WorldStageRecorder.Stage.LIGHT_POLL, context, now, now,
                    true, -1L, -1L, -1L, -1);
        }
        WorldStageRecorder.Snapshot snapshot = recorder.snapshot();
        assertEquals(2L, snapshot.eventCount());
        assertEquals(1L, snapshot.droppedEvents());
        assertEquals(0L, snapshot.invalidEvents());
        assertEquals(1L, snapshot.events().get(0).sequence());
        assertEquals(2L, snapshot.events().get(1).sequence());
        assertThrows(IllegalArgumentException.class, () -> new WorldStageRecorder(0));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldStageRecorder(WorldStageRecorder.MAX_CAPACITY + 1));
    }

    @Test
    void contextRegistryUsesIdentityAndHasBoundedOverflow() {
        WorldStageRecorder recorder = new WorldStageRecorder(2);
        Object first = new String("same");
        Object equalButDistinct = new String("same");
        long firstId = recorder.contextId(first);
        assertEquals(firstId, recorder.contextId(first));
        assertNotEquals(firstId, recorder.contextId(equalButDistinct));
        assertEquals(0L, recorder.contextId(null));

        List<Object> retained = new ArrayList<>();
        retained.add(first);
        retained.add(equalButDistinct);
        for (int i = 2; i < WorldStageRecorder.CONTEXT_CAPACITY; i++) {
            Object context = new Object();
            retained.add(context);
            assertTrue(recorder.contextId(context) > 0L);
        }
        assertEquals(0L, recorder.contextId(new Object()));
        WorldStageRecorder.Snapshot snapshot = recorder.snapshot();
        assertEquals(WorldStageRecorder.CONTEXT_CAPACITY, snapshot.liveContextCount());
        assertEquals(1L, snapshot.contextOverflowEvents());
        assertEquals(1L, snapshot.invalidEvents());
    }

    @Test
    void concurrentRecordingsHaveUniqueMonotonicSequences() throws Exception {
        int workers = 4;
        int perWorker = 100;
        WorldStageRecorder recorder = new WorldStageRecorder(workers * perWorker);
        long context = recorder.contextId(new Object());
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int worker = 0; worker < workers; worker++) {
                executor.submit(() -> {
                    start.await();
                    long now = System.nanoTime();
                    for (int i = 0; i < perWorker; i++) {
                        recorder.record(WorldStageRecorder.Stage.LIGHT_TASK, context, now, now,
                                true, -1L, -1L, 1L, 1);
                    }
                    return null;
                });
            }
            start.countDown();
        } finally {
            executor.shutdown();
        }
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        WorldStageRecorder.Snapshot snapshot = recorder.snapshot();
        assertEquals((long) workers * perWorker, snapshot.eventCount());
        for (int i = 0; i < snapshot.events().size(); i++) {
            assertEquals(i + 1L, snapshot.events().get(i).sequence());
            assertTrue(snapshot.events().get(i).threadId() > 0L);
        }
    }

    @Test
    void reportCarriesDiagnosticScopeAndImmutableSnapshot() {
        WorldStageRecorder recorder = new WorldStageRecorder(2);
        long context = recorder.contextId(new Object());
        long now = System.nanoTime();
        recorder.record(WorldStageRecorder.Stage.SERVER_PACKETS, context, now, now,
                true, -1L, -1L, -1L, -1);
        WorldStageRecorder.Report report = recorder.report("0123456789abcdef0123456789abcdef01234567", "trial-7", "passed");
        assertEquals(1, report.schemaVersion());
        assertEquals("diagnostic", report.evidenceClass());
        assertEquals(false, report.performanceEligible());
        assertEquals("process-observation-including-warmup", report.scope());
        assertEquals("System.nanoTime", report.clock());
        assertEquals("26.3", report.minecraftVersion());
        assertEquals("0123456789abcdef0123456789abcdef01234567", report.sourceSha());
        assertEquals("trial-7", report.trialId());
        assertEquals(1L, report.snapshot().eventCount());
        assertThrows(UnsupportedOperationException.class,
                () -> report.snapshot().events().add(report.snapshot().events().get(0)));
    }
    @Test
    void emitsActualGsonFixtureForIndependentOracle() throws Exception {
        var recorder = new WorldStageRecorder(16, () -> 100L);
        Object level = new Object();
        long context = recorder.contextId(level);
        recorder.record(WorldStageRecorder.Stage.LIGHT_TASK, context, 105, 115, false, 2, 1, 1, -1);
        recorder.record(WorldStageRecorder.Stage.LIGHT_POLL, context, 101, 120, false, 3, 1, -1, -1);
        var report = recorder.report("0123456789abcdef0123456789abcdef01234567", "world-stage-java-fixture", "passed");
        var output = java.nio.file.Path.of("build/agent-state/world-stage-java-fixture.json");
        java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output, new com.google.gson.Gson().toJson(report));
    }

    @Test
    void rejectsExplicitInvalidConfiguredCapacity() {
        String key = "metallum.validation.worldStageCapacity";
        String previous = System.getProperty(key);
        try {
            for (String invalid : List.of("", "0", "-1", "65537", "not-an-integer", "99999999999999999")) {
                System.setProperty(key, invalid);
                assertThrows(IllegalArgumentException.class, WorldStageRecorder::new);
            }
            System.setProperty(key, "17");
            assertEquals(17, new WorldStageRecorder().capacity());
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }

}
