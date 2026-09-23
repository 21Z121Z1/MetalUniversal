package com.metallum.client.metal.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class FramePipelineCreationEvidenceTest {
    private final AtomicLong clock = new AtomicLong(100);
    private final FrameEvidenceRecorder recorder = new FrameEvidenceRecorder(4, clock::get);

    private static JsonObject signature() {
        JsonObject value = new JsonObject();
        JsonArray colors = new JsonArray();
        colors.add("RGBA8Unorm");
        value.add("colorFormats", colors);
        value.addProperty("depthFormat", "Depth32Float");
        value.addProperty("stencilFormat", "Invalid");
        value.addProperty("sampleCount", 1);
        return value;
    }

    private void finish(FrameEvidenceRecorder.PipelineCreation attempt, JsonObject signature, boolean success) {
        recorder.pipelineCreationFinished(attempt, clock.get(), "sha256:" + "a".repeat(64), "minecraft:test",
                "attachment-variant", signature, success);
    }

    private JsonObject frame(int index) {
        return recorder.snapshot(new JsonObject()).getAsJsonArray("frames").get(index).getAsJsonObject();
    }

    @Test void capturesActualAttemptIdentityDurationOutcomeAndAnIndependentSignature() {
        recorder.beginFrame(true);
        var attempt = recorder.pipelineCreationStarted();
        JsonObject signature = signature();
        clock.addAndGet(25);
        finish(attempt, signature, false);
        signature.getAsJsonArray("colorFormats").add("mutated");
        clock.incrementAndGet();
        recorder.endFrame();
        var event = frame(0).getAsJsonArray("pipelineCreations").get(0).getAsJsonObject();
        assertEquals("metallum_MTLDevice_makeRenderPipelineState", event.get("nativeCall").getAsString());
        assertEquals("sha256:" + "a".repeat(64), event.get("validationPipelineId").getAsString());
        assertEquals("minecraft:test", event.get("pipelineLocation").getAsString());
        assertEquals("attachment-variant", event.get("creationKind").getAsString());
        assertEquals(25, event.get("durationNs").getAsLong());
        assertFalse(event.get("succeeded").getAsBoolean());
        assertEquals(1, event.getAsJsonObject("signature").getAsJsonArray("colorFormats").size());
        event.getAsJsonObject("signature").addProperty("sampleCount", 99);
        assertEquals(1, frame(0).getAsJsonArray("pipelineCreations").get(0).getAsJsonObject()
                .getAsJsonObject("signature").get("sampleCount").getAsInt());
        assertEquals("", frame(0).get("failure").getAsString());
    }

    @Test void nestedScopesCannotReassignAnOuterAttemptToTheCurrentFrame() {
        recorder.beginFrame(true);
        var outer = recorder.pipelineCreationStarted();
        clock.addAndGet(10);
        recorder.beginFrame(false);
        var inner = recorder.pipelineCreationStarted();
        clock.addAndGet(5);
        finish(outer, signature(), true);
        assertEquals(1, frame(0).getAsJsonArray("pipelineCreations").size());
        assertEquals(0, frame(1).getAsJsonArray("pipelineCreations").size());
        finish(inner, signature(), true);
        recorder.endFrame();
        recorder.endFrame();
        assertEquals(15, frame(0).getAsJsonArray("pipelineCreations").get(0).getAsJsonObject().get("durationNs").getAsLong());
        assertEquals(5, frame(1).getAsJsonArray("pipelineCreations").get(0).getAsJsonObject().get("durationNs").getAsLong());
    }

    @Test void startupWorkersAndDisabledRuntimeCreateNoFrameObservation() throws InterruptedException {
        assertNull(recorder.pipelineCreationStarted());
        assertFalse(FrameEvidenceRuntime.ENABLED);
        assertNull(FrameEvidenceRuntime.pipelineCreationStarted());
        recorder.beginFrame(true);
        AtomicReference<FrameEvidenceRecorder.PipelineCreation> worker = new AtomicReference<>();
        Thread thread = new Thread(() -> worker.set(recorder.pipelineCreationStarted()));
        thread.start(); thread.join();
        assertNull(worker.get());
        finish(null, signature(), true);
        recorder.endFrame();
        assertTrue(frame(0).getAsJsonArray("pipelineCreations").isEmpty());
    }

    @Test void boundsAttemptsWithoutSilentlyDroppingDiagnostics() {
        recorder.beginFrame(true);
        for (int i = 0; i < 64; i++) finish(recorder.pipelineCreationStarted(), signature(), true);
        assertNull(recorder.pipelineCreationStarted());
        recorder.endFrame();
        assertEquals(64, frame(0).getAsJsonArray("pipelineCreations").size());
        assertEquals("pipeline-creation-evidence-overflow", frame(0).get("failure").getAsString());
    }

    @Test void duplicateInvalidAndUnfinishedAttemptsStayVisible() {
        recorder.beginFrame(true);
        var attempt = recorder.pipelineCreationStarted();
        finish(attempt, signature(), true);
        finish(attempt, signature(), true);
        assertEquals("duplicate-pipeline-creation-completion", frame(0).get("failure").getAsString());
        recorder.endFrame();
        recorder.beginFrame(true);
        var invalid = recorder.pipelineCreationStarted();
        finish(invalid, null, true);
        assertEquals("invalid-pipeline-creation-evidence", frame(1).get("failure").getAsString());
        recorder.endFrame();
        recorder.beginFrame(true);
        recorder.pipelineCreationStarted();
        recorder.endFrame();
        assertEquals("open-pipeline-creation-at-frame-end", frame(2).get("failure").getAsString());
    }

    @Test void anExportedSegmentIsImmutableEvenWhenAnAttemptCompletesLate() {
        recorder.enableSegments(1, 1, 1000);
        recorder.beginFrame(true);
        var attempt = recorder.pipelineCreationStarted();
        clock.incrementAndGet();
        recorder.endFrame();
        var detached = recorder.pollSegment(new JsonObject(), true);
        var before = detached.toJson();
        finish(attempt, signature(), true);
        assertEquals(before, detached.toJson());
        assertEquals(1, recorder.archiveState(new JsonObject()).getAsJsonObject("retention")
                .get("lateCompletionUpdates").getAsLong());
    }
}
