package com.metallum.client.validation.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WorldStageRecorderTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void beginEndRecordsProcessObservationAndSchemaTwoIdentity() {
        AtomicLong clock = new AtomicLong(1_000L);
        WorldStageRecorder recorder = new WorldStageRecorder(4, clock::get);
        Object contextObject = new Object();
        long context = recorder.contextId(contextObject);
        long token = recorder.begin(WorldStageRecorder.Stage.CHUNK_INSTALL, context, 1_005L, 2L);
        assertEquals(1L, token);
        recorder.end(token, 1_010L, true, 1L, 4L, 1);
        clock.set(1_010L);

        WorldStageRecorder.Snapshot snapshot = recorder.snapshot();
        assertEquals(2, snapshot.schemaVersion());
        assertEquals(1L, snapshot.eventCount());
        assertEquals(1L, snapshot.startedInvocations());
        assertEquals(1L, snapshot.finishedInvocations());
        assertEquals(1L, snapshot.events().get(0).sequence());
        assertEquals(1L, snapshot.events().get(0).invocationId());
        assertEquals(5L, snapshot.events().get(0).startOffsetNanos());
        assertEquals(10L, snapshot.events().get(0).endOffsetNanos());
        assertEquals("CHUNK_INSTALL", snapshot.events().get(0).stage().name());
        assertTrue(snapshot.activeInvocations().isEmpty());
        assertEquals(10L, snapshot.timestampOffsetNanos());
        assertNull(snapshot.window());

        WorldStageRecorder.Report report = recorder.report(SHA, "trial-7", "passed");
        assertEquals(2, report.schemaVersion());
        assertEquals("diagnostic", report.evidenceClass());
        assertFalse(report.performanceEligible());
        assertEquals("process-observation-including-warmup", report.scope());
        assertEquals("System.nanoTime", report.clock());
        assertEquals("26.3", report.minecraftVersion());
        assertEquals(WorldStageRecorder.MEASUREMENT_LIMITS, report.measurementLimits());
    }

    @Test
    void wrongThreadAndInvalidExitDoNotCreateRows() throws Exception {
        AtomicLong clock = new AtomicLong(1_000L);
        WorldStageRecorder recorder = new WorldStageRecorder(4, clock::get);
        Object contextObject = new Object();
        long context = recorder.contextId(contextObject);
        long token = recorder.begin(WorldStageRecorder.Stage.LIGHT_TASK, context, 1_001L, -1L);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> recorder.end(token, 1_002L, true, -1L, 1L, 1)).get();
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(1L, recorder.snapshot().invalidEvents());
        recorder.end(token, 1_002L, true, -1L, 1L, 1);
        recorder.end(0L, 1_003L, true, -1L, 1L, 1);
        assertEquals(1L, recorder.snapshot().eventCount());
        assertEquals(2L, recorder.snapshot().invalidEvents());
    }

    @Test
    void invalidTimesGaugesAndUnissuedContextsCannotBecomeValidEvidence() {
        for (int scenario = 0; scenario < 8; scenario++) {
            var clock = new AtomicLong(100);
            var recorder = new WorldStageRecorder(4, clock::get);
            long context = recorder.contextId(new Object());
            switch (scenario) {
                case 0 -> recorder.begin(null, context, 101, 0);
                case 1 -> recorder.begin(WorldStageRecorder.Stage.LIGHT_TASK, context + 1, 101, 0);
                case 2 -> recorder.begin(WorldStageRecorder.Stage.LIGHT_TASK, context, 99, 0);
                case 3 -> recorder.begin(WorldStageRecorder.Stage.LIGHT_TASK, context, 101, -2);
                default -> {
                    long token = recorder.begin(WorldStageRecorder.Stage.LIGHT_TASK, context, 101, 0);
                    recorder.end(token, scenario == 4 ? 100 : 102, true,
                            scenario == 5 ? -2 : 0, scenario == 6 ? -2 : 1, scenario == 7 ? 2 : -1);
                }
            }
            clock.set(110);
            assertEquals(1, recorder.snapshot().invalidEvents(), "scenario " + scenario);
            assertEquals(0, recorder.snapshot().eventCount(), "scenario " + scenario);
        }
    }

    @Test
    void boundedCapacityDropsFinishedRowsAndPreservesConservation() {
        AtomicLong clock = new AtomicLong(1_000L);
        WorldStageRecorder recorder = new WorldStageRecorder(1, clock::get);
        Object contextObject = new Object();
        long context = recorder.contextId(contextObject);
        long first = recorder.begin(WorldStageRecorder.Stage.LIGHT_POLL, context, 1_001L, -1L);
        recorder.end(first, 1_002L, true, -1L, -1L, -1);
        long second = recorder.begin(WorldStageRecorder.Stage.LIGHT_POLL, context, 1_003L, -1L);
        recorder.end(second, 1_004L, true, -1L, -1L, -1);
        WorldStageRecorder.Snapshot snapshot = recorder.snapshot();
        assertEquals(1L, snapshot.eventCount());
        assertEquals(1L, snapshot.droppedEvents());
        assertEquals(2L, snapshot.startedInvocations());
        assertEquals(2L, snapshot.finishedInvocations());
        assertEquals(snapshot.eventCount() + snapshot.droppedEvents(), snapshot.finishedInvocations());
    }

    @Test
    void activeCapacityReturnsDisabledTokenAndRecordsOverflow() {
        AtomicLong clock = new AtomicLong(1_000L);
        WorldStageRecorder recorder = new WorldStageRecorder(256, clock::get);
        Object contextObject = new Object();
        long context = recorder.contextId(contextObject);
        List<Long> tokens = new ArrayList<>();
        for (int i = 0; i < WorldStageRecorder.MAX_ACTIVE_INVOCATIONS; i++) {
            tokens.add(recorder.begin(WorldStageRecorder.Stage.LIGHT_TASK, context, 1_001L + i, -1L));
        }
        assertEquals(0L, recorder.begin(WorldStageRecorder.Stage.LIGHT_TASK, context, 2_000L, -1L));
        assertEquals(1L, recorder.snapshot().activeOverflowEvents());
        for (long token : tokens) recorder.end(token, 2_100L, true, -1L, 1L, 1);
        assertEquals(256L, recorder.snapshot().eventCount());
        assertEquals(256L, recorder.snapshot().finishedInvocations());
    }

    @Test
    void concurrentBeginEndPreservesSequencesAndDoesNotHideWorkerFailures() throws Exception {
        int workers = 4;
        int perWorker = 100;
        AtomicLong clock = new AtomicLong(1_000L);
        WorldStageRecorder recorder = new WorldStageRecorder(workers * perWorker, clock::get);
        Object contextObject = new Object();
        long context = recorder.contextId(contextObject);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int worker = 0; worker < workers; worker++) {
                futures.add(executor.submit(() -> {
                    for (int i = 0; i < perWorker; i++) {
                        long start = 1_001L + i;
                        long token = recorder.begin(WorldStageRecorder.Stage.LIGHT_TASK, context, start, -1L);
                        recorder.end(token, start + 1L, true, -1L, 1L, 1);
                    }
                }));
            }
            for (Future<?> future : futures) future.get();
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        WorldStageRecorder.Snapshot snapshot = recorder.snapshot();
        assertEquals((long) workers * perWorker, snapshot.eventCount());
        assertEquals(snapshot.eventCount(), snapshot.finishedInvocations());
        for (int i = 0; i < snapshot.events().size(); i++) {
            assertEquals(i + 1L, snapshot.events().get(i).sequence());
            assertTrue(snapshot.events().get(i).threadId() > 0L);
        }
    }

    @Test
    void windowRetainsLeftCensoredAndRightCensoredCallsThenFreezes() {
        AtomicLong clock = new AtomicLong(100L);
        WorldStageRecorder recorder = new WorldStageRecorder(8, clock::get);
        Object contextObject = new Object();
        long context = recorder.contextId(contextObject);
        long left = recorder.begin(WorldStageRecorder.Stage.LIGHT_TASK, context, 105L, 2L);
        clock.set(110L);
        recorder.beginWindow("window-1", 10L, 110L);
        long middle = recorder.begin(WorldStageRecorder.Stage.LIGHT_TASK, context, 115L, 3L);
        long right = recorder.begin(WorldStageRecorder.Stage.SERVER_TICK, context, 125L, -1L);
        recorder.end(left, 120L, false, 1L, 1L, 0);
        recorder.end(middle, 130L, true, 3L, 1L, 1);
        clock.set(140L);
        recorder.endWindow(12L, 140L);

        WorldStageRecorder.Snapshot snapshot = recorder.snapshot();
        assertNotNull(snapshot.window());
        assertTrue(snapshot.window().closed());
        assertEquals("window-1", snapshot.window().id());
        assertEquals(10L, snapshot.window().startOffsetNanos());
        assertEquals(40L, snapshot.window().endOffsetNanos());
        assertEquals(1L, snapshot.window().activeAtStart());
        assertEquals(2L, snapshot.startedInvocations());
        assertEquals(2L, snapshot.finishedInvocations());
        assertEquals(2L, snapshot.eventCount());
        assertEquals(1, snapshot.activeInvocations().size());
        assertEquals(right, snapshot.activeInvocations().get(0).invocationId());
        assertEquals(1L + snapshot.startedInvocations(),
                snapshot.finishedInvocations() + snapshot.activeInvocations().size());
        assertEquals(40L, snapshot.timestampOffsetNanos());
        assertEquals(5L, snapshot.events().get(0).startOffsetNanos());
        assertEquals(20L, snapshot.events().get(0).endOffsetNanos());

        WorldStageRecorder.Snapshot frozen = snapshot;
        recorder.end(right, 150L, true, -1L, -1L, 1);
        assertEquals(0L, recorder.begin(WorldStageRecorder.Stage.SERVER_TICK, context, 145L, -1L));
        assertSame(frozen, recorder.snapshot());
        assertEquals(1, recorder.snapshot().activeInvocations().size());
    }

    @Test
    void windowGuardsRejectDuplicateAndZeroLengthWindows() {
        AtomicLong clock = new AtomicLong(100L);
        WorldStageRecorder recorder = new WorldStageRecorder(4, clock::get);
        assertThrows(IllegalStateException.class, () -> recorder.endWindow(1L, 101L));
        recorder.beginWindow("", 1L, 101L);
        recorder.beginWindow("window", 1L, 101L);
        assertThrows(IllegalStateException.class, () -> recorder.beginWindow("second", 2L, 102L));
        recorder.endWindow(1L, 102L);
        recorder.endWindow(2L, 101L); // A zero-duration window is also invalid.
        assertEquals(3L, recorder.snapshot().invalidEvents());
        clock.set(103L);
        recorder.endWindow(2L, 103L);
        assertTrue(recorder.snapshot().window().closed());
        assertThrows(IllegalStateException.class, () -> recorder.endWindow(3L, 104L));
    }

    @Test
    void contextRegistryAndConfiguredCapacityRemainBounded() {
        WorldStageRecorder recorder = new WorldStageRecorder(2);
        Object first = new String("same");
        Object equalButDistinct = new String("same");
        long firstId = recorder.contextId(first);
        assertEquals(firstId, recorder.contextId(first));
        assertTrue(recorder.contextId(equalButDistinct) > firstId);
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
        assertEquals(1L, recorder.snapshot().contextOverflowEvents());
        assertEquals(1L, recorder.snapshot().invalidEvents());

        String key = "metallum.validation.worldStageCapacity";
        String previous = System.getProperty(key);
        try {
            for (String invalid : List.of("", "0", "-1", "65537", "not-an-integer")) {
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

    @Test
    void reportValidatesIdentityAndWritesActualGsonCrossWindowFixture() throws Exception {
        AtomicLong clock = new AtomicLong(100L);
        WorldStageRecorder recorder = new WorldStageRecorder(16, clock::get);
        Object contextObject = new Object();
        long context = recorder.contextId(contextObject);
        long left = recorder.begin(WorldStageRecorder.Stage.LIGHT_TASK, context, 105L, 2L);
        clock.set(110L);
        recorder.beginWindow("world-stage-java-fixture", 10L, 110L);
        long middle = recorder.begin(WorldStageRecorder.Stage.SERVER_TICK, context, 115L, -1L);
        long right = recorder.begin(WorldStageRecorder.Stage.LIGHT_POLL, context, 125L, 0L);
        recorder.end(left, 120L, false, 1L, 1L, -1);
        recorder.end(middle, 130L, true, -1L, 1L, 1);
        clock.set(140L);
        recorder.endWindow(12L, 140L);
        var report = recorder.report(SHA, "world-stage-java-fixture", "passed");
        assertEquals(right, report.snapshot().activeInvocations().get(0).invocationId());
        assertThrows(IllegalArgumentException.class, () -> recorder.report("BAD", "trial", "passed"));
        assertThrows(IllegalArgumentException.class, () -> recorder.report(SHA, "", "passed"));
        assertThrows(IllegalArgumentException.class, () -> recorder.report(SHA, "trial", ""));
        assertEquals(2, report.schemaVersion());
        var output = Path.of("build/agent-state/world-stage-java-fixture.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new com.google.gson.Gson().toJson(report) + "\n");
        String json = Files.readString(output);
        assertTrue(json.contains("\"schemaVersion\":2"));
        assertTrue(json.contains("\"window\""));
        assertTrue(json.contains("\"activeInvocations\""));
    }
}
