package com.metallum.client.metal.render;

import com.google.gson.JsonObject;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameEvidenceRecorderTest {
    private final AtomicLong clock = new AtomicLong(100);
    private final FrameEvidenceRecorder recorder = new FrameEvidenceRecorder(4, clock::get);

    @Test void preservesPrimitivePointerVoidAndExceptionalAbiShapes() throws Throwable {
        recorder.beginFrame(true);
        for (Class<?> type : new Class<?>[]{int.class, long.class, float.class, double.class, MemorySegment.class}) {
            Object value = type == int.class ? 42 : type == long.class ? 43L
                    : type == float.class ? 4.5f : type == double.class ? 5.5 : MemorySegment.NULL;
            // Build a typed handle without numeric conditional-expression promotion.
            MethodHandle target = MethodHandles.identity(type);
            MethodHandle observed = recorder.instrument(type.getName(), target);
            assertEquals(target.type(), observed.type());
            assertEquals(target.invokeWithArguments(value), observed.invokeWithArguments(value));
        }
        MethodHandle nothing = MethodHandles.empty(MethodType.methodType(void.class, int.class));
        recorder.instrument("void", nothing).invokeExact(7);
        IllegalStateException failure = new IllegalStateException("native failure");
        MethodHandle throwing = MethodHandles.insertArguments(
                MethodHandles.throwException(long.class, IllegalStateException.class), 0, failure);
        MethodHandle observed = recorder.instrument("throw", throwing);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> { long ignored = (long) observed.invokeExact(); }));
        recorder.endFrame();
        assertEquals(1, frame(0).getAsJsonObject("abi").getAsJsonObject("throw").get("failures").getAsInt());
    }

    @Test void nestedCallsHaveSeparateInclusiveAndExclusiveTime() throws Throwable {
        MethodHandle inner = recorder.instrument("inner", MethodHandles.lookup().findVirtual(
                FrameEvidenceRecorderTest.class, "work", MethodType.methodType(void.class)).bindTo(this));
        MethodHandle outer = recorder.instrument("outer", inner);
        recorder.beginFrame(true);
        outer.invokeExact();
        recorder.endFrame();
        JsonObject abi = frame(0).getAsJsonObject("abi");
        assertEquals(20, abi.getAsJsonObject("outer").get("inclusiveNs").getAsLong());
        assertEquals(0, abi.getAsJsonObject("outer").get("exclusiveNs").getAsLong());
        assertEquals(20, abi.getAsJsonObject("inner").get("exclusiveNs").getAsLong());
    }

    private void work() { clock.addAndGet(20); }

    @Test void preScopeBufferCanAcquireOwnerWithoutLosingItsSubmitIdentity() {
        assertNull(recorder.commandBuffer(42)); // tick upload, outside renderFrame
        recorder.beginFrame(true);
        var carried = recorder.commandBuffer(42); // same buffer first used in source scope
        recorder.presentationRequested(carried, 0);
        recorder.submitted(carried);
        recorder.endFrame();
        recorder.nativePresentationId(carried, 901);
        recorder.completed(carried, true, 1, 2);
        recorder.presented(new long[]{901}, new double[]{3});
        var row = frame(0).getAsJsonArray("commandBuffers").get(0).getAsJsonObject();
        assertEquals(42, row.get("nativeSubmitIndex").getAsLong());
        assertEquals(3, row.get("presentedTimeSeconds").getAsDouble());
        assertEquals("", frame(0).get("failure").getAsString());
        recorder.beginFrame(true);
        recorder.presentationRequested(carried, 902);
        assertEquals("invalid-presentation-request", frame(0).get("failure").getAsString());
        assertTrue(frame(1).getAsJsonArray("commandBuffers").isEmpty());
        recorder.endFrame();
    }

    @Test void encoderObservesReusedBuffersBeforeReturningThem() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java"));
        String accessor = source.substring(source.indexOf("    MTLCommandBuffer commandBuffer()"),
                source.indexOf("    MTLBlitCommandEncoder blitCommandEncoder()"));
        assertEquals(1, accessor.split("return commandBuffer;", -1).length - 1);
        assertTrue(accessor.indexOf("FrameEvidenceRuntime.commandBuffer(currentSubmitIndex)")
                < accessor.indexOf("return commandBuffer;"));
        assertTrue(accessor.contains("if (frameEvidenceSubmission == null)"));
    }

    @Test void delayedCompletionsKeepTheirOriginalFrameAndFailuresAreNotGpuSamples() {
        recorder.beginFrame(true);
        var first = recorder.commandBuffer(8);
        recorder.presentationRequested(first, 101);
        recorder.submitted(first);
        recorder.endFrame();
        recorder.beginFrame(true);
        var second = recorder.commandBuffer(9);
        recorder.presentationRequested(second, 102);
        recorder.submitted(second);
        recorder.completed(second, false, 1, 2);
        recorder.completed(first, true, 1, 1.002);
        recorder.endFrame();
        assertEquals(2_000_000, frame(0).getAsJsonArray("commandBuffers").get(0).getAsJsonObject().get("gpuServiceNs").getAsLong());
        assertEquals("command-buffer-failed", frame(1).getAsJsonArray("commandBuffers").get(0).getAsJsonObject().get("gpuUnavailableReason").getAsString());
        assertEquals(101, frame(0).getAsJsonArray("commandBuffers").get(0).getAsJsonObject().get("nativePresentationId").getAsLong());
        assertEquals(102, frame(1).getAsJsonArray("commandBuffers").get(0).getAsJsonObject().get("nativePresentationId").getAsLong());
    }

    @Test void presentationCallbacksJoinExactTicketsInsteadOfCompletionOrder() {
        recorder.beginFrame(true);
        var first = recorder.commandBuffer(8);
        recorder.presentationRequested(first, 0); // Metal 4: assigned at native commit.
        recorder.submitted(first);
        recorder.nativePresentationId(first, 101);
        recorder.completed(first, true, 1, 2);
        recorder.endFrame();
        recorder.beginFrame(true);
        var second = recorder.commandBuffer(9);
        recorder.presentationRequested(second, 102);
        recorder.submitted(second);
        recorder.completed(second, true, 2, 3);
        recorder.endFrame();
        recorder.presented(new long[]{102, 101}, new double[]{0, 10.5});
        var firstRow = frame(0).getAsJsonArray("commandBuffers").get(0).getAsJsonObject();
        var secondRow = frame(1).getAsJsonArray("commandBuffers").get(0).getAsJsonObject();
        assertEquals(10.5, firstRow.get("presentedTimeSeconds").getAsDouble());
        assertEquals("", firstRow.get("presentedUnavailableReason").getAsString());
        assertTrue(secondRow.get("presentedTimeSeconds").isJsonNull());
        assertEquals("presented-callback-pending", secondRow.get("presentedUnavailableReason").getAsString());
        recorder.nativePresentationId(first, 999);
        assertEquals("mismatched-native-presentation-id", frame(0).get("failure").getAsString());
    }

    @Test void openFramesCrossFrameSubmissionAndDuplicateCompletionRemainVisible() {
        recorder.beginFrame(true);
        var submission = recorder.commandBuffer(0);
        recorder.presentationRequested(submission, 0);
        JsonObject receipt = frame(0).getAsJsonArray("commandBuffers").get(0).getAsJsonObject();
        assertTrue(receipt.get("nativePresentationId").isJsonNull());
        assertEquals("native-present-id-not-returned", receipt.get("presentationIdUnavailableReason").getAsString());
        recorder.beginFrame(false);
        assertFalse(frame(0).get("ended").getAsBoolean());
        recorder.submitted(submission);
        assertEquals("cross-frame-command-buffer", frame(0).get("failure").getAsString());
        recorder.completed(submission, true, 0, 0);
        recorder.completed(submission, true, 0, 0);
        assertEquals("invalid-completion", frame(0).get("failure").getAsString());
    }

    @Test void recursiveLoadingScreenFramesRestoreTheOuterFrame() throws Throwable {
        MethodHandle call = recorder.instrument("outer", MethodHandles.empty(MethodType.methodType(void.class)));
        recorder.beginFrame(false);
        recorder.beginFrame(false);
        recorder.endFrame();
        call.invokeExact();
        recorder.endFrame();
        assertEquals(1, frame(1).get("parentFrameId").getAsLong());
        assertTrue(frame(0).get("ended").getAsBoolean());
        assertTrue(frame(1).getAsJsonObject("abi").isEmpty());
        assertEquals(1, frame(0).getAsJsonObject("abi").getAsJsonObject("outer").get("calls").getAsLong());
    }

    @Test void boundedCaptureReportsLossInsteadOfOverwritingOrReusingIds() {
        for (int i = 0; i < 7; i++) { recorder.beginFrame(true); recorder.endFrame(); }
        JsonObject snapshot = recorder.snapshot(new JsonObject());
        assertEquals(4, snapshot.getAsJsonArray("frames").size());
        assertEquals(3, snapshot.get("droppedFrames").getAsInt());
        assertEquals(4, frame(3).get("frameId").getAsInt());
    }

    @Test void workerCallsAreNotMisattributedToAnOpenRenderFrame() throws Throwable {
        MethodHandle observed = recorder.instrument("worker", MethodHandles.empty(MethodType.methodType(void.class)));
        recorder.beginFrame(true);
        Thread worker = new Thread(() -> {
            try { observed.invokeExact(); } catch (Throwable failure) { throw new AssertionError(failure); }
        });
        worker.start(); worker.join();
        recorder.endFrame();
        assertTrue(frame(0).getAsJsonObject("abi").isEmpty());
    }

    @Test void disabledRuntimeReturnsTheOriginalHandle() {
        assertFalse(FrameEvidenceRuntime.ENABLED);
        MethodHandle target = MethodHandles.identity(long.class);
        assertSame(target, FrameEvidenceRuntime.instrument("disabled", target));
    }

    @Test void terrainBatchJoinsRestoreOuterScopeAndDrawableWaitKeepsSubmissionOwner() {
        recorder.beginFrame(true);
        recorder.terrainBatchEncoded(17);
        var first = recorder.commandBuffer(8);
        recorder.submitted(first);
        recorder.beginFrame(false);
        recorder.terrainBatchEncoded(18);
        var second = recorder.commandBuffer(9);
        recorder.submitted(second);
        recorder.endFrame();
        recorder.terrainBatchEncoded(17);
        recorder.terrainBatchEncoded(19);
        recorder.endFrame();
        recorder.drawableWait(first, 0);
        recorder.drawableWait(second, 73);
        assertEquals("[17,19]", frame(0).getAsJsonArray("terrainBatchIndices").toString());
        assertEquals("[18]", frame(1).getAsJsonArray("terrainBatchIndices").toString());
        assertEquals(0, frame(0).getAsJsonArray("commandBuffers").get(0).getAsJsonObject().get("drawableWaitNs").getAsLong());
        assertEquals(73, frame(1).getAsJsonArray("commandBuffers").get(0).getAsJsonObject().get("drawableWaitNs").getAsLong());
    }

    @Test void pipelineCreationIdentityIsBoundToItsSourceFrame() {
        recorder.beginFrame(true);
        JsonObject signature = new JsonObject();
        signature.addProperty("depthFormat", "Depth32Float");
        recorder.pipelineCreation("sha256:pipeline", "attachment-variant", signature, 348_092_958L);
        recorder.endFrame();

        JsonObject event = frame(0).getAsJsonArray("pipelineCreations").get(0).getAsJsonObject();
        assertEquals("metallum_MTLDevice_makeRenderPipelineState", event.get("nativeCall").getAsString());
        assertEquals("sha256:pipeline", event.get("validationPipelineId").getAsString());
        assertEquals("attachment-variant", event.get("creationKind").getAsString());
        assertEquals(348_092_958L, event.get("durationNs").getAsLong());
        assertEquals("Depth32Float", event.getAsJsonObject("signature").get("depthFormat").getAsString());
    }

    private JsonObject frame(int index) {
        return recorder.snapshot(new JsonObject()).getAsJsonArray("frames").get(index).getAsJsonObject();
    }

    @Test void explicitWindowExcludesWarmupAndLongSessionTailWithoutLoss() {
        var bounded = new FrameEvidenceRecorder(2, clock::get, true);
        bounded.armWindow(new JsonObject(), 100, 200);
        bounded.beginFrame(true); bounded.endFrame(); // warmup
        clock.set(200);
        bounded.beginFrame(true);
        var first = bounded.commandBuffer(55);
        bounded.presentationRequested(first, 71);
        bounded.submitted(first);
        clock.set(210); bounded.endFrame();
        clock.set(399); bounded.beginFrame(true);
        clock.set(410); bounded.endFrame(); // admitted by start; callback may be later
        for (int i = 0; i < 100; i++) { bounded.beginFrame(true); bounded.endFrame(); }
        bounded.completed(first, true, 2, 3);
        bounded.presented(new long[]{71}, new double[]{10});
        JsonObject report = bounded.snapshot(new JsonObject());
        assertEquals(2, report.getAsJsonArray("frames").size());
        assertEquals(0, report.get("droppedFrames").getAsInt());
        assertTrue(report.getAsJsonObject("window").get("closed").getAsBoolean());
        assertEquals(101, report.getAsJsonObject("window").get("outsideWindowFrames").getAsInt());
        assertEquals(200, report.getAsJsonArray("frames").get(0).getAsJsonObject().get("sourceStartNs").getAsLong());
        assertThrows(IllegalStateException.class, () -> bounded.armWindow(new JsonObject(), 0, 1));
    }

    @Test void windowNestedScopesKeepParentMembershipAcrossBoundaryAndCapacityIsExplicit() {
        var bounded = new FrameEvidenceRecorder(2, clock::get, true);
        bounded.armWindow(new JsonObject(), 10, 20);
        bounded.beginFrame(false); // outside root
        clock.set(111); bounded.beginFrame(true); bounded.endFrame(); bounded.endFrame();
        bounded.beginFrame(true); // admitted root
        clock.set(131); bounded.beginFrame(false); bounded.endFrame(); bounded.endFrame();
        var rows = bounded.snapshot(new JsonObject()).getAsJsonArray("frames");
        assertEquals(2, rows.size());
        assertEquals(rows.get(0).getAsJsonObject().get("frameId"), rows.get(1).getAsJsonObject().get("parentFrameId"));
        var small = new FrameEvidenceRecorder(1, clock::get, true);
        small.armWindow(new JsonObject(), 0, 20);
        small.beginFrame(true); small.beginFrame(false); small.endFrame(); small.endFrame();
        assertEquals(1, small.snapshot(new JsonObject()).get("droppedFrames").getAsInt());
    }

    @Test void copiedPresentationSurvivesEvictionButConflictingReceiptInvalidates() {
        recorder.beginFrame(true);
        var submission = recorder.commandBuffer(1);
        recorder.presentationRequested(submission, 4);
        recorder.submitted(submission); recorder.endFrame();
        recorder.presented(new long[]{4}, new double[]{12});
        recorder.presented(new long[]{4}, new double[]{-3});
        assertEquals(12, frame(0).getAsJsonArray("commandBuffers").get(0).getAsJsonObject().get("presentedTimeSeconds").getAsDouble());
        assertEquals(0, recorder.presentationIds().length);
        assertThrows(IllegalArgumentException.class, () -> recorder.presented(new long[]{4, 4}, new double[]{12, 13}));
        recorder.presented(new long[]{4}, new double[]{13});
        assertEquals("conflicting-presented-timestamp", frame(0).get("failure").getAsString());
    }

    @Test void zeroReceiptIsNotPresentationAndIsDistinctFromInvalidClock() {
        recorder.beginFrame(true);
        var notPresented = recorder.commandBuffer(1);
        var invalidClock = recorder.commandBuffer(2);
        recorder.presentationRequested(notPresented, 40);
        recorder.presentationRequested(invalidClock, 41);
        recorder.submitted(notPresented); recorder.submitted(invalidClock);
        recorder.endFrame();
        recorder.completed(notPresented, true, 1, 2);
        recorder.completed(invalidClock, true, 1, 2);
        recorder.presented(new long[]{41, 40}, new double[]{-2, -4});
        var rows = frame(0).getAsJsonArray("commandBuffers");
        assertTrue(rows.get(0).getAsJsonObject().get("presentedTimeSeconds").isJsonNull());
        assertEquals("drawable-not-presented", rows.get(0).getAsJsonObject().get("presentedUnavailableReason").getAsString());
        assertEquals("invalid-presented-timestamp", rows.get(1).getAsJsonObject().get("presentedUnavailableReason").getAsString());
        assertEquals(0, recorder.presentationIds().length);
    }

    @Test void epochChangesRemainExplicitAndSubmissionHistoryIsBounded() {
        var bounded = new FrameEvidenceRecorder(2, clock::get, true);
        bounded.armWindow(new JsonObject(), 0, 100);
        bounded.beginFrame(true); bounded.endFrame();
        bounded.advanceEpoch();
        bounded.beginFrame(true);
        for (int i = 0; i < 256; i++) assertNotNull(bounded.commandBuffer(i));
        assertNull(bounded.commandBuffer(256));
        bounded.endFrame();
        var report = bounded.snapshot(new JsonObject());
        assertEquals(1, report.getAsJsonObject("window").get("epoch").getAsInt());
        var row = report.getAsJsonArray("frames").get(1).getAsJsonObject();
        assertEquals(2, row.get("epoch").getAsInt());
        assertEquals("submission-evidence-overflow", row.get("failure").getAsString());
    }

    @Test void exportIsAtomicAndOutputFailureCannotBecomeAReport(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var output = directory.resolve("report.json");
        JsonObject report = new JsonObject(); report.addProperty("fixture", true);
        FrameEvidenceRuntime.writeReport(output, report);
        assertTrue(java.nio.file.Files.readString(output).contains("fixture"));
        assertFalse(java.nio.file.Files.exists(directory.resolve("report.json.partial")));
        var impossible = output.resolve("child.json");
        assertThrows(java.io.IOException.class, () -> FrameEvidenceRuntime.writeReport(impossible, report));
        assertFalse(java.nio.file.Files.exists(impossible));
    }
}
