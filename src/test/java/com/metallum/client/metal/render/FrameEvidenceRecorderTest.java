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

    private JsonObject frame(int index) {
        return recorder.snapshot(new JsonObject()).getAsJsonArray("frames").get(index).getAsJsonObject();
    }
}
