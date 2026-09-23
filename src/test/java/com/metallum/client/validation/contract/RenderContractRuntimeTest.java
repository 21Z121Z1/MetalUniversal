package com.metallum.client.validation.contract;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RenderContractRuntimeTest {
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
