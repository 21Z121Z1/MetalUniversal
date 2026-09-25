package com.metallum.client.metal.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FrameEvidenceSegmentsTest {
    private final AtomicLong clock = new AtomicLong(100);

    private FrameEvidenceRecorder recorder(int capacity, int segmentSize, int pending) {
        var result = new FrameEvidenceRecorder(capacity, clock::get);
        result.enableSegments(segmentSize, pending, 1000);
        return result;
    }

    private FrameEvidenceRecorder.Submission frame(FrameEvidenceRecorder recorder, long ticket, boolean complete) {
        recorder.beginFrame(true);
        var submission = recorder.commandBuffer(ticket);
        recorder.presentationRequested(submission, ticket);
        recorder.submitted(submission);
        clock.incrementAndGet();
        recorder.endFrame();
        if (complete) recorder.completed(submission, true, 1, 2);
        return submission;
    }

    private JsonObject retention(FrameEvidenceRecorder recorder) {
        return recorder.archiveState(new JsonObject()).getAsJsonObject("retention");
    }

    @Test void repeatedWrapKeepsGlobalIdentityAndBoundedRetention() {
        var recorder = recorder(2, 2, 2);
        for (int segment = 1; segment <= 50; segment++) {
            long first = segment * 2L - 1;
            frame(recorder, first, true);
            frame(recorder, first + 1, true);
            recorder.presented(new long[]{first + 1, first}, new double[]{first + 1.25, first + 0.25});
            JsonObject value = recorder.pollSegment(new JsonObject(), false).toJson();
            assertEquals(segment, value.getAsJsonObject("segment").get("id").getAsInt());
            assertEquals(first, value.getAsJsonArray("frames").get(0).getAsJsonObject().get("frameId").getAsLong());
            assertEquals(0, retention(recorder).get("retainedFrames").getAsInt());
            assertEquals(0, recorder.presentationIds().length);
        }
        assertEquals(100, retention(recorder).get("exportedFrames").getAsInt());
        assertEquals(0, recorder.archiveState(new JsonObject()).get("droppedFrames").getAsInt());
        assertThrows(IllegalStateException.class, () -> recorder.snapshot(new JsonObject()));
    }

    @Test void outOfOrderLateCompletionAndReceiptStayWithTheirOriginalSegment() {
        var recorder = recorder(2, 1, 2);
        var first = frame(recorder, 10, false);
        frame(recorder, 20, true);
        recorder.presented(new long[]{20}, new double[]{3});
        assertNull(recorder.pollSegment(new JsonObject(), false));
        recorder.presented(new long[]{10}, new double[]{2});
        assertNull(recorder.pollSegment(new JsonObject(), false)); // Receipt is not GPU completion.
        recorder.completed(first, true, 1, 1.5);
        assertEquals(1, recorder.pollSegment(new JsonObject(), false).toJson().getAsJsonObject("segment").get("id").getAsInt());
        assertEquals(2, recorder.pollSegment(new JsonObject(), false).toJson().getAsJsonObject("segment").get("id").getAsInt());
    }

    @Test void unresolvedReceiptExpiresWithoutBeingAttributedToANewFrame() {
        var recorder = recorder(2, 1, 1);
        var first = frame(recorder, 99, true);
        clock.addAndGet(1001);
        JsonObject expired = recorder.pollSegment(new JsonObject(), false).toJson();
        assertEquals("callback-retention-expired", expired.getAsJsonObject("segment").get("censoringReason").getAsString());
        recorder.presented(new long[]{99}, new double[]{9});
        recorder.completed(first, true, 1, 2);
        assertTrue(expired.getAsJsonArray("frames").get(0).getAsJsonObject().getAsJsonArray("commandBuffers")
                .get(0).getAsJsonObject().get("presentedTimeSeconds").isJsonNull());
        assertEquals(1, retention(recorder).get("lateReceiptUpdates").getAsInt());
        assertEquals(1, retention(recorder).get("lateCompletionUpdates").getAsInt());
        assertEquals(1, retention(recorder).get("censoredSegments").getAsInt());
    }

    @Test void slowWriterBackpressureAccountsOverflowRatherThanOverwritingUnpublishedFrames() {
        var recorder = recorder(2, 1, 1);
        for (int i = 1; i <= 8; i++) frame(recorder, i, false);
        JsonObject state = recorder.archiveState(new JsonObject());
        assertEquals(3, state.getAsJsonObject("retention").get("retainedFrames").getAsInt());
        assertEquals(5, state.get("droppedFrames").getAsInt());
        assertNotNull(recorder.pollSegment(new JsonObject(), true));
        assertNotNull(recorder.pollSegment(new JsonObject(), true));
        assertNull(recorder.pollSegment(new JsonObject(), true));
    }

    @Test void recursiveFramesAreNeverSplitAcrossSegments() {
        var recorder = recorder(4, 1, 2);
        recorder.beginFrame(true);
        recorder.beginFrame(false);
        clock.incrementAndGet();
        recorder.endFrame();
        assertNull(recorder.pollSegment(new JsonObject(), false));
        clock.incrementAndGet();
        recorder.endFrame();
        JsonObject report = recorder.pollSegment(new JsonObject(), false).toJson();
        assertEquals(2, report.getAsJsonArray("frames").size());
        assertEquals(1, report.getAsJsonArray("frames").get(1).getAsJsonObject().get("parentFrameId").getAsInt());
    }

    @Test void terminalNotPresentedCannotLaterBecomeAValidReceipt() {
        var recorder = recorder(4, 4, 1);
        frame(recorder, 1, true);
        recorder.presented(new long[]{1}, new double[]{-4});
        recorder.presented(new long[]{1}, new double[]{2});
        JsonObject report = recorder.pollSegment(new JsonObject(), true).toJson();
        JsonObject row = report.getAsJsonArray("frames").get(0).getAsJsonObject();
        assertEquals("conflicting-presented-timestamp", row.get("failure").getAsString());
        assertTrue(row.getAsJsonArray("commandBuffers").get(0).getAsJsonObject().get("presentedTimeSeconds").isJsonNull());
    }

    @Test void shutdownCensorsOpenScopeAndLateEndDoesNotMutateDetachedRows() {
        var recorder = recorder(4, 2, 1);
        recorder.beginFrame(true);
        var snapshot = recorder.pollSegment(new JsonObject(), true);
        String before = snapshot.toJson().toString();
        clock.incrementAndGet();
        recorder.endFrame();
        assertEquals(before, snapshot.toJson().toString());
        assertEquals("shutdown-right-censored", snapshot.toJson().getAsJsonObject("segment").get("censoringReason").getAsString());
    }

    @Test void invalidConfigurationsFailBeforeCapture() {
        var recorder = new FrameEvidenceRecorder(4, clock::get);
        assertThrows(IllegalArgumentException.class, () -> recorder.enableSegments(5, 1, 100));
        assertThrows(IllegalArgumentException.class, () -> recorder.enableSegments(2, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> recorder.enableSegments(2, 1, 0));
        recorder.beginFrame(true);
        assertThrows(IllegalArgumentException.class, () -> recorder.enableSegments(2, 1, 100));
    }

    @Test void archivePublishesHashChainedSegmentsAndOnlyThenCompletesCheckpoint(@TempDir Path directory) throws Exception {
        var recorder = recorder(4, 2, 2);
        frame(recorder, 1, true);
        frame(recorder, 2, true);
        recorder.presented(new long[]{2, 1}, new double[]{4, 3});
        Path output = directory.resolve("frames.json");
        var archive = new FrameEvidenceArchive(recorder, new JsonObject(), output);
        archive.finish("passed", report -> report.addProperty("workloadComplete", true));
        JsonObject index = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        assertTrue(index.getAsJsonObject("archive").get("complete").getAsBoolean());
        assertEquals(1, index.getAsJsonObject("archive").get("committedSegments").getAsInt());
        byte[] bytes = Files.readAllBytes(directory.resolve("frames.json.segments/00000001.json"));
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(sha, index.getAsJsonObject("archive").get("lastSegmentSha256").getAsString());
        assertTrue(index.get("workloadComplete").getAsBoolean());
    }

    @Test void existingOutputIsNotSilentlyReplaced(@TempDir Path directory) throws Exception {
        Path output = directory.resolve("frames.json");
        Files.writeString(output, "previous evidence");
        var archive = new FrameEvidenceArchive(recorder(2, 1, 1), new JsonObject(), output);
        assertThrows(java.io.IOException.class, () -> archive.finish("passed", value -> { }));
        assertEquals("previous evidence", Files.readString(output));
    }

    @Test void fatalWriterExitCannotPublishACompletedArchive(@TempDir Path directory) throws Exception {
        var fatal = new AssertionError("injected final-flush failure");
        var uncaught = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var recorder = new FrameEvidenceRecorder(2, () -> {
            // No frame is recorded, so the first clock read occurs in the real
            // writer's final drain. No production injection or worker access is needed.
            Thread.currentThread().setUncaughtExceptionHandler((thread, failure) -> uncaught.set(failure));
            throw fatal;
        });
        recorder.enableSegments(1, 1, 1000);
        Path output = directory.resolve("frames.json");
        var archive = new FrameEvidenceArchive(recorder, new JsonObject(), output);
        var failure = assertThrows(java.io.IOException.class,
                () -> archive.finish("passed", value -> fail("completion metadata must not run")));
        assertSame(fatal, failure.getCause());
        assertSame(fatal, uncaught.get());
        var checkpoint = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        assertFalse(checkpoint.getAsJsonObject("archive").get("complete").getAsBoolean());
        assertFalse(checkpoint.get("shutdownDrained").getAsBoolean());
        assertEquals("unvalidated", checkpoint.get("validationStatus").getAsString());
    }

    @Test void failedFinalMetadataLeavesTheExistingCheckpointIncomplete(@TempDir Path directory) throws Exception {
        Path output = directory.resolve("frames.json");
        var archive = new FrameEvidenceArchive(recorder(2, 1, 1), new JsonObject(), output);
        var fatal = new AssertionError("injected metadata failure");
        assertSame(fatal, assertThrows(AssertionError.class,
                () -> archive.finish("passed", value -> { throw fatal; })));
        var checkpoint = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        assertFalse(checkpoint.getAsJsonObject("archive").get("complete").getAsBoolean());
        assertFalse(checkpoint.get("shutdownDrained").getAsBoolean());
    }

}
