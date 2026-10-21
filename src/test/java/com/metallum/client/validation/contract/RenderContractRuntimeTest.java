package com.metallum.client.validation.contract;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.metallum.client.validation.capture.FileValidationCaptureService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RenderContractRuntimeTest {
    @Test
    void successfulCaptureCannotHideABudgetFailureFirstEncounteredAtFinalClose() throws Exception {
        String enabled=System.getProperty("metallum.renderContract.enabled");
        String limit=System.getProperty("metallum.renderContract.maxManifestBytes");
        Path output=Files.createTempDirectory("render-contract-close-budget-");
        try {
            System.setProperty("metallum.renderContract.enabled","true");
            System.setProperty("metallum.renderContract.maxManifestBytes","32768");
            RenderContractRuntime.start(output,"close-budget");
            completedCapture();assertTrue(RenderContractRuntime.completionGatePassed());
            // Captures already passed. Append real logical passes without an
            // intermediate manifest flush, so the first byte-limit decision
            // is the same final-close path as the client terminal oracle.
            for(int i=0;i<40;i++) {
                long pass=RenderContractRuntime.beginRenderPass("synthetic/late-payload",PassType.RENDER,
                        List.of(),null,null,new ViewportRecord(0,0,1,1),ScissorRecord.disabled(),"pipeline",
                        List.of("fragment"),Map.of("retainedPayload","x".repeat(1024),"index",Integer.toString(i)));
                RenderContractRuntime.endPass(pass);
            }
            RenderContractRuntime.close();
            var terminal=RenderContractRuntime.snapshot();
            assertTrue(terminal.requestedCaptures()==1 && terminal.completedCaptures()==1);
            assertTrue(terminal.failedCaptures()==0 && terminal.pendingCaptures()==0 && terminal.droppedCaptures()==0);
            assertTrue(terminal.status().equals("failed"));assertFalse(terminal.ready());
            assertFalse(terminal.manifestFinalized());assertFalse(RenderContractRuntime.completionGatePassed());
            Path path=output.resolve("render-contract/pass-manifest.json");
            assertTrue(Files.size(path)<4096,"Budget failure must remain bounded and inspectable");
            var trace=com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            assertTrue(trace.get("status").getAsString().equals("failed"));
            assertFalse(trace.get("manifestComplete").getAsBoolean());
            assertTrue(trace.get("manifestFailureReason").getAsString().equals("render contract manifest byte budget exceeded"));
            assertTrue(trace.get("requiredManifestBytes").getAsLong()>32768);
            assertTrue(trace.get("tracePayloadOmitted").getAsBoolean());
        } finally {
            RenderContractRuntime.close();restoreProperty("metallum.renderContract.enabled",enabled);
            restoreProperty("metallum.renderContract.maxManifestBytes",limit);
        }
    }

    @Test
    void successfulClosePublishesACompleteOnDiskTraceAndTerminalSnapshot() throws Exception {
        String enabled=System.getProperty("metallum.renderContract.enabled");
        Path output=Files.createTempDirectory("render-contract-close-success-");
        try {
            System.setProperty("metallum.renderContract.enabled","true");RenderContractRuntime.start(output,"close-success");
            completedCapture();assertTrue(RenderContractRuntime.completionGatePassed());RenderContractRuntime.close();
            assertFalse(RenderContractRuntime.enabled());assertTrue(RenderContractRuntime.snapshot().ready());
            assertTrue(RenderContractRuntime.snapshot().status().equals("passed"));
            var manifest=com.google.gson.JsonParser.parseString(Files.readString(output.resolve("render-contract/pass-manifest.json"))).getAsJsonObject();
            assertTrue(manifest.get("manifestComplete").getAsBoolean());assertTrue(manifest.get("status").getAsString().equals("passed"));
        } finally {RenderContractRuntime.close();restoreProperty("metallum.renderContract.enabled",enabled);}
    }

    @Test
    void failedTerminalWritesDetachOwnersBeforeLaterBufferSwaps() throws Exception {
        String previousEnabled = System.getProperty("metallum.renderContract.enabled");
        try {
            System.setProperty("metallum.renderContract.enabled", "true");
            for (int fault : new int[]{0, 1, 2}) {
                boolean failManifest = fault != 1, failResults = fault != 0;
                Path output = Files.createTempDirectory("render-contract-close-write-failure-");
                RenderContractRuntime.start(output, "close-write-failure");
                completedCapture();
                assertTrue(RenderContractRuntime.completionGatePassed(), "The fault must invalidate a previously passing gate");
                var recorderField = RenderContractRuntime.class.getDeclaredField("recorder");
                recorderField.setAccessible(true);
                var recorder = (RenderTraceRecorder) recorderField.get(null);
                Path manifest = output.resolve("render-contract/pass-manifest.json");
                Path results = output.resolve("render-contract/results.json");
                // A directory at a file destination reliably fails on both
                // hosted macOS and Linux, including a privileged test process.
                if (failManifest) { Files.delete(manifest); Files.createDirectory(manifest); }
                if (failResults) {
                    Files.delete(results);
                    Files.createDirectory(results);
                }
                try {
                    IllegalStateException failure = null;
                    try {
                        RenderContractRuntime.close();
                    } catch (IllegalStateException exception) {
                        failure = exception;
                    }
                    assertTrue(failure != null, "A failed terminal write must be reported");
                    assertTrue(failure.getMessage().equals(failResults
                            ? "Could not write capture results" : "Could not write render contract manifest"));
                    assertTrue(failure.getSuppressed().length == (fault == 2 ? 1 : 0),
                            "Attempt both disposals and retain the secondary failure");
                    assertTrue(recorder.isClosed(), "A results failure must not skip recorder disposal");
                    // Exact entry point reached by MetalGpuBuffer.swapBacking
                    // during the real client's disconnect frame.
                    RenderContractRuntime.invalidateResourceAllocations(1, "metal-buffer-1");
                    assertFalse(RenderContractRuntime.enabled(), "A throwing close must detach its owners");
                    RenderContractRuntime.beginFrame(1);
                    assertTrue(RenderContractRuntime.identifyResource("late", 2, "texture-2",
                            "RGBA8_UNORM", 1, 1, 1, 0, 1, 3) == null);
                    assertFalse(RenderContractRuntime.completionGatePassed(),
                            "Detachment must not turn a failed requested run into passing opt-out");
                    assertTrue("failed".equals(RenderContractRuntime.snapshot().status()));
                    RenderContractRuntime.close();
                    assertFalse(RenderContractRuntime.completionGatePassed());
                    // Keep the recorder's own closed-owner negative strict.
                    boolean rejected = false;
                    try { recorder.invalidateResourceAllocations(1, "metal-buffer-1"); }
                    catch (IllegalStateException expected) { rejected = true; }
                    assertTrue(rejected, "Direct use of a disposed recorder must still fail");
                } finally {
                    Files.deleteIfExists(manifest);
                    if (failResults) Files.deleteIfExists(results);
                    RenderContractRuntime.close();
                }
            }
        } finally {
            RenderContractRuntime.close();
            restoreProperty("metallum.renderContract.enabled", previousEnabled);
        }
    }

    @Test
    void concurrentTeardownCannotLeaveAClosedRecorderVisibleToBufferRetirement() throws Exception {
        String previousEnabled=System.getProperty("metallum.renderContract.enabled");
        var workers=Executors.newFixedThreadPool(3);
        CountDownLatch serviceHeld=new CountDownLatch(1),releaseService=new CountDownLatch(1);
        try {
            System.setProperty("metallum.renderContract.enabled","true");
            RenderContractRuntime.start(Files.createTempDirectory("render-contract-close-race-"),"close-race");
            var recorderField=RenderContractRuntime.class.getDeclaredField("recorder");recorderField.setAccessible(true);
            var serviceField=RenderContractRuntime.class.getDeclaredField("captureService");serviceField.setAccessible(true);
            var recorder=(RenderTraceRecorder)recorderField.get(null);
            var service=(FileValidationCaptureService)serviceField.get(null);
            CompletableFuture<Void> closing;
            CompletableFuture<Void> snapshotBarrier;
            // Freeze close between service disposal and recorder disposal,
            // then block its final snapshot after the recorder has closed.
            synchronized(recorder) {
                closing=CompletableFuture.runAsync(RenderContractRuntime::close,workers);
                awaitCondition(()->!"active".equals(service.status()));
                snapshotBarrier=CompletableFuture.runAsync(()->{
                    synchronized(service) {
                        serviceHeld.countDown();
                        try {
                            if(!releaseService.await(5,TimeUnit.SECONDS))throw new AssertionError("Snapshot barrier timed out");
                        } catch(InterruptedException e) {Thread.currentThread().interrupt();throw new AssertionError(e);}
                    }
                },workers);
                assertTrue(serviceHeld.await(5,TimeUnit.SECONDS));
            }
            awaitCondition(()->!"active".equals(recorder.status()));
            AtomicReference<Thread> retirementThread=new AtomicReference<>();
            var retirement=CompletableFuture.runAsync(()->{
                retirementThread.set(Thread.currentThread());
                // The actual failed E2E path: MetalGpuBuffer.swapBacking.
                RenderContractRuntime.invalidateResourceAllocations(1,"metal-buffer-1");
                RenderContractRuntime.beginFrame(1);
                RenderContractRuntime.identifyResource("late",2,"texture-2","RGBA8_UNORM",1,1,1,0,1,3);
            },workers);
            awaitCondition(()->retirement.isDone()||(retirementThread.get()!=null
                    &&retirementThread.get().getState()==Thread.State.BLOCKED));
            assertFalse(retirement.isDone(),"A renderer operation must wait for owner teardown, not borrow its closed recorder");
            releaseService.countDown();
            closing.get(5,TimeUnit.SECONDS);snapshotBarrier.get(5,TimeUnit.SECONDS);retirement.get(5,TimeUnit.SECONDS);
            assertFalse(RenderContractRuntime.enabled());
            assertFalse(RenderContractRuntime.snapshot().ready(),"No capture was completed; synchronization cannot manufacture a passing gate");
        } finally {
            releaseService.countDown();workers.shutdownNow();workers.awaitTermination(5,TimeUnit.SECONDS);
            RenderContractRuntime.close();restoreProperty("metallum.renderContract.enabled",previousEnabled);
        }
    }

    private static void completedCapture() {
        RenderContractRuntime.beginFrame(0);
        var resource = RenderContractRuntime.identifyResource("color0", 1, "texture-1", "RGBA8_UNORM", 1, 1, 1, 0, 1, 3);
        long pass = RenderContractRuntime.beginRenderPass("synthetic/runtime-close", PassType.RENDER,
                List.of(new AttachmentBindingRecord(0, resource, AttachmentSemantic.COLOR, "clear", "store", true)),
                null, null, new ViewportRecord(0, 0, 1, 1), ScissorRecord.disabled(), "pipeline", List.of("fragment"), Map.of());
        RenderContractRuntime.recordProducer(pass, ProducerType.DRAW, "pipeline", Map.of("vertexCount", "3"),
                Map.of(), List.of(resource.stableKey()));
        RenderContractRuntime.endPass(pass);
        RenderContractRuntime.endFrame(0);
        var point = new CapturePoint(0, "synthetic/runtime-close", CapturePointKind.AFTER_PASS, -1);
        RenderContractRuntime.requestReadbacks(point, List.of(new RenderContractRuntime.ReadbackRequest(
                "color0", 1, "texture-1", "RGBA8_UNORM", 4, 1, 1, 1, 0, 1, 3, AttachmentSemantic.COLOR)), List.of());
        RenderContractRuntime.recordReadback(point, "color0", 1, "texture-1", "RGBA8_UNORM", 4,
                1, 1, 1, 0, 1, 3, new byte[]{1, 2, 3, (byte)255}, List.of());
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()) {
            if(System.nanoTime()>=deadline)throw new AssertionError("Timed out arranging the recorder lifecycle interleaving");
            Thread.sleep(1);
        }
    }

    @Test
    void explicitFailureClosesTheCompletionGate() throws Exception {
        String previousEnabled = System.getProperty("metallum.renderContract.enabled");
        String previousRunId = System.getProperty("metallum.renderContract.runId");
        Path output = Files.createTempDirectory("render-contract-runtime-gate-");
        try {
            System.setProperty("metallum.renderContract.enabled", "true");
            System.setProperty("metallum.renderContract.runId", "runtime-gate");
            RenderContractRuntime.start(output, "runtime-gate");
            RenderContractRuntime.beginFrame(0L);
            ResourceIdentity resource = RenderContractRuntime.identifyResource(
                    "color0", 1L, "texture-1", "RGBA8_UNORM", 1, 1, 1, 0, 1, 3
            );
            long pass = RenderContractRuntime.beginRenderPass(
                    "synthetic/runtime-gate", PassType.RENDER,
                    List.of(new AttachmentBindingRecord(
                            0, resource, AttachmentSemantic.COLOR, "clear", "store", true
                    )),
                    null,
                    null,
                    new ViewportRecord(0, 0, 1, 1),
                    ScissorRecord.disabled(),
                    "pipeline",
                    List.of("fragment"),
                    Map.of("commandBufferSubmissionId", "1")
            );
            RenderContractRuntime.recordProducer(
                    pass,
                    ProducerType.DRAW,
                    "pipeline",
                    Map.of("vertexCount", "3"),
                    Map.of("color", resource.stableKey()),
                    List.of(resource.stableKey())
            );
            RenderContractRuntime.endPass(pass);
            RenderContractRuntime.endFrame(0L);

            CapturePoint point = new CapturePoint(
                    0L, "synthetic/runtime-gate", CapturePointKind.AFTER_PASS, -1
            );
            RenderContractRuntime.requestReadbacks(
                    point,
                    List.of(new RenderContractRuntime.ReadbackRequest(
                            "color0", 1L, "texture-1", "RGBA8_UNORM", 4,
                            1, 1, 1, 0, 1, 3, AttachmentSemantic.COLOR
                    )),
                    List.of()
            );
            RenderContractRuntime.recordReadback(
                    point, "color0", 1L, "texture-1", "RGBA8_UNORM", 4,
                    1, 1, 1, 0, 1, 3, new byte[]{1, 2, 3, (byte) 255}, List.of()
            );

            assertTrue(RenderContractRuntime.completionGatePassed(), RenderContractRuntime.snapshot().toString());
            RenderContractRuntime.markFailed();
            assertFalse(RenderContractRuntime.completionGatePassed(), RenderContractRuntime.snapshot().toString());
        } finally {
            RenderContractRuntime.close();
            restoreProperty("metallum.renderContract.enabled", previousEnabled);
            restoreProperty("metallum.renderContract.runId", previousRunId);
        }
    }

    private static void restoreProperty(final String name, final String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
