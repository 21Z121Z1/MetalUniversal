package com.metallum.client.validation.contract;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

final class RenderContractRuntimeTest {
    @Test
    void failedFinalTraceWriteDetachesClosedRecorderAndKeepsFailureVisible() throws Exception {
        String enabled=System.getProperty("metallum.renderContract.enabled");
        Path output=Files.createTempDirectory("render-contract-close-trace-io-");
        try {
            System.setProperty("metallum.renderContract.enabled","true");RenderContractRuntime.start(output,"close-trace-io");
            completedCapture();assertTrue(RenderContractRuntime.completionGatePassed());
            Path manifest=output.resolve("render-contract/pass-manifest.json");
            Files.delete(manifest);Files.createDirectory(manifest);
            assertThrows(IllegalStateException.class,RenderContractRuntime::close,"A real filesystem write failure must still fail conformance");
            assertFalse(RenderContractRuntime.enabled());assertFalse(RenderContractRuntime.observing());
            assertDoesNotThrow(()->RenderContractRuntime.invalidateResourceAllocations(1,"after-close"));
            assertFalse(RenderContractRuntime.completionGatePassed());
            assertDoesNotThrow(RenderContractRuntime::close);
        } finally {RenderContractRuntime.close();restoreProperty("metallum.renderContract.enabled",enabled);}
    }

    @Test
    void failedCaptureCloseStillClosesTraceAndCannotAuthorizeCompletion() throws Exception {
        String enabled=System.getProperty("metallum.renderContract.enabled");
        Path output=Files.createTempDirectory("render-contract-close-capture-io-");
        try {
            System.setProperty("metallum.renderContract.enabled","true");RenderContractRuntime.start(output,"close-capture-io");
            completedCapture();assertTrue(RenderContractRuntime.completionGatePassed());
            var field=RenderContractRuntime.class.getDeclaredField("recorder");field.setAccessible(true);
            var trace=(RenderTraceRecorder)field.get(null);
            Path results=output.resolve("render-contract/results.json");
            Files.delete(results);Files.createDirectory(results);
            assertThrows(IllegalStateException.class,RenderContractRuntime::close);
            assertFalse(RenderContractRuntime.enabled());assertFalse(RenderContractRuntime.completionGatePassed());
            assertTrue(trace.isClosed(),
                    "Failure of capture finalization cannot prevent the independently owned trace from closing");
            assertTrue(RenderContractRuntime.snapshot().status().equals("failed"),
                    "The combined oracle must stay failed even if the independent trace closed successfully");
            assertDoesNotThrow(()->RenderContractRuntime.invalidateResourceAllocations(1,"after-close"));
        } finally {RenderContractRuntime.close();restoreProperty("metallum.renderContract.enabled",enabled);}
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

    private static void completedCapture() {
        RenderContractRuntime.beginFrame(0);
        var resource=RenderContractRuntime.identifyResource("color0",1,"texture-1","RGBA8_UNORM",1,1,1,0,1,3);
        long pass=RenderContractRuntime.beginRenderPass("synthetic/runtime-close",PassType.RENDER,
                List.of(new AttachmentBindingRecord(0,resource,AttachmentSemantic.COLOR,"clear","store",true)),
                null,null,new ViewportRecord(0,0,1,1),ScissorRecord.disabled(),"pipeline",List.of("fragment"),Map.of());
        RenderContractRuntime.recordProducer(pass,ProducerType.DRAW,"pipeline",Map.of("vertexCount","3"),Map.of(),List.of(resource.stableKey()));
        RenderContractRuntime.endPass(pass);RenderContractRuntime.endFrame(0);
        var point=new CapturePoint(0,"synthetic/runtime-close",CapturePointKind.AFTER_PASS,-1);
        RenderContractRuntime.requestReadbacks(point,List.of(new RenderContractRuntime.ReadbackRequest(
                "color0",1,"texture-1","RGBA8_UNORM",4,1,1,1,0,1,3,AttachmentSemantic.COLOR)),List.of());
        RenderContractRuntime.recordReadback(point,"color0",1,"texture-1","RGBA8_UNORM",4,1,1,1,0,1,3,
                new byte[]{1,2,3,(byte)255},List.of());
    }

    private static void restoreProperty(final String name, final String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
