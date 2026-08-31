package com.metallum.client.validation.contract;

import com.google.gson.JsonObject;
import com.metallum.client.validation.storage.ValidationStorageBudget;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RenderContractCoreTest {
    @Test
    void resourceGenerationChangesWhenAllocationShapeChanges() throws Exception {
        Path output = Files.createTempDirectory("render-contract-resource-");
        RenderTraceRecorder recorder = new RenderTraceRecorder(output, "resource-test", "test", 4, 8, 16);
        ResourceIdentity first = recorder.identifyResource(
                "colortex0", 10L, "metal-texture-10", "RGBA8_UNORM", 4, 4, 1, 0, 1, 3
        );
        ResourceIdentity same = recorder.identifyResource(
                "colortex0", 10L, "metal-texture-10", "RGBA8_UNORM", 4, 4, 1, 0, 1, 3
        );
        ResourceIdentity resized = recorder.identifyResource(
                "colortex0", 11L, "metal-texture-11", "RGBA8_UNORM", 8, 4, 1, 0, 1, 3
        );
        recorder.close();

        assertEquals(first, same);
        assertNotEquals(first.generation(), resized.generation());
        assertEquals("colortex0@1", first.stableKey());
        assertEquals("allocation/10/generation/1/mip/0", first.allocationKey());
        assertEquals("colortex0@2", resized.stableKey());
        assertTrue(Files.exists(output.resolve("pass-manifest.json")));
    }

    @Test
    void resourceGenerationChangesWhenNativeHandleChangesAtTheSameShape() throws Exception {
        Path output = Files.createTempDirectory("render-contract-resource-handle-");
        RenderTraceRecorder recorder = new RenderTraceRecorder(output, "resource-handle-test", "test", 4, 8, 16);
        ResourceIdentity first = recorder.identifyResource(
                "colortex0", 10L, "metal-texture-old", "RGBA8_UNORM", 4, 4, 1, 0, 1, 3
        );
        ResourceIdentity reallocated = recorder.identifyResource(
                "colortex0", 10L, "metal-texture-new", "RGBA8_UNORM", 4, 4, 1, 0, 1, 3
        );
        recorder.close();

        assertNotEquals(first, reallocated);
        assertEquals("colortex0@1", first.stableKey());
        assertEquals("colortex0@2", reallocated.stableKey());
    }

    @Test
    void resourceGenerationChangesWhenAReleasedHandleIsReused() throws Exception {
        Path output = Files.createTempDirectory("render-contract-resource-reuse-");
        RenderTraceRecorder recorder = new RenderTraceRecorder(output, "resource-reuse-test", "test", 4, 8, 16);
        ResourceIdentity first = recorder.identifyResource(
                "colortex0", 10L, "metal-texture-reused", "RGBA8_UNORM", 4, 4, 1, 0, 1, 3
        );
        recorder.invalidateResourceAllocations(10L, "metal-texture-reused");
        ResourceIdentity reused = recorder.identifyResource(
                "colortex0", 10L, "metal-texture-reused", "RGBA8_UNORM", 4, 4, 1, 0, 1, 3
        );
        recorder.close();

        assertEquals("colortex0@1", first.stableKey());
        assertEquals("colortex0@2", reused.stableKey());
        JsonObject manifest = com.google.gson.JsonParser.parseString(
                Files.readString(output.resolve("pass-manifest.json"))
        ).getAsJsonObject();
        assertEquals(2, manifest.get("resourceCount").getAsInt());
        assertEquals(3, manifest.getAsJsonArray("resourceLifecycle").size());
        assertEquals("INVALIDATE", manifest.getAsJsonArray("resourceLifecycle")
                .get(1).getAsJsonObject().get("action").getAsString());
    }

    @Test
    void manifestKeepsLogicalPassStableAcrossProducerRecords() throws Exception {
        Path output = Files.createTempDirectory("render-contract-manifest-");
        RenderTraceRecorder recorder = new RenderTraceRecorder(output, "manifest-test", "test", 4, 8, 16);
        recorder.beginFrame(12L);
        ResourceIdentity resource = recorder.identifyResource(
                "color0", 1L, "metal-texture-1", "RGBA8_UNORM", 2, 2, 1, 0, 1, 3
        );
        long token = recorder.beginPass(
                "synthetic/mrt", PassType.RENDER,
                List.of(new AttachmentBindingRecord(0, resource, AttachmentSemantic.COLOR, "clear", "store", true)),
                null, null, new ViewportRecord(0, 0, 2, 2), ScissorRecord.disabled(),
                "unbound", List.of(), Map.of("commandBufferSubmissionId", "7")
        );
        recorder.recordProducer(token, ProducerType.CLEAR, "unbound", Map.of(), Map.of(), List.of("color0"));
        recorder.recordProducer(token, ProducerType.DRAW, "sha256:pipeline", Map.of("vertexCount", "3"), Map.of(), List.of("color0"));
        recorder.endPass(token);
        recorder.endFrame(12L);
        recorder.close();

        JsonObject manifest = com.google.gson.JsonParser.parseString(
                Files.readString(output.resolve("pass-manifest.json"))
        ).getAsJsonObject();
        assertEquals(1, manifest.get("passCount").getAsInt());
        assertTrue(manifest.get("manifestComplete").getAsBoolean());
        assertEquals("synthetic/mrt", manifest.getAsJsonArray("passes")
                .get(0).getAsJsonObject().get("semanticPassId").getAsString());
        assertEquals(2, manifest.getAsJsonArray("passes").get(0).getAsJsonObject()
                .getAsJsonArray("producers").size());
        JsonObject pass = manifest.getAsJsonArray("passes").get(0).getAsJsonObject();
        assertEquals("manifest-test", pass.getAsJsonObject("traceIdentity").get("runId").getAsString());
        assertEquals("synthetic/mrt", pass.getAsJsonObject("traceIdentity")
                .get("semanticPassId").getAsString());
        assertEquals(0, pass.getAsJsonObject("traceIdentity").get("passSequence").getAsInt());
        assertEquals(0, pass.getAsJsonArray("producers").get(0).getAsJsonObject()
                .getAsJsonObject("traceIdentity").get("producerIndex").getAsInt());
    }

    @Test
    void attachmentStoreResolutionUpdatesOnlyTheResolvedSlots() throws Exception {
        Path output = Files.createTempDirectory("render-contract-attachment-actions-");
        RenderTraceRecorder recorder = new RenderTraceRecorder(output, "attachment-actions", "test", 4, 8, 16);
        recorder.beginFrame(1L);
        ResourceIdentity color0 = recorder.identifyResource(
                "color0", 1L, "metal-texture-1", "RGBA8_UNORM", 2, 2, 1, 0, 1, 3
        );
        ResourceIdentity color1 = recorder.identifyResource(
                "color1", 2L, "metal-texture-2", "RGBA8_UNORM", 2, 2, 1, 0, 1, 3
        );
        ResourceIdentity depth = recorder.identifyResource(
                "depth", 3L, "metal-texture-3", "DEPTH32_FLOAT", 2, 2, 1, 0, 1, 3
        );
        long token = recorder.beginPass(
                "synthetic/attachment-actions", PassType.RENDER,
                List.of(
                        new AttachmentBindingRecord(0, color0, AttachmentSemantic.COLOR, "clear", "store", true),
                        new AttachmentBindingRecord(1, color1, AttachmentSemantic.COLOR, "load", "unknown", true)
                ),
                new AttachmentBindingRecord(0, depth, AttachmentSemantic.DEPTH, "load", "unknown", true),
                null,
                new ViewportRecord(0, 0, 2, 2),
                ScissorRecord.disabled(),
                "pipeline", List.of(), Map.of()
        );
        recorder.updateAttachmentStoreActions(token, Map.of(1, "dontCare"), "store");
        recorder.endPass(token);
        recorder.close();

        JsonObject pass = com.google.gson.JsonParser.parseString(
                Files.readString(output.resolve("pass-manifest.json"))
        ).getAsJsonObject().getAsJsonArray("passes").get(0).getAsJsonObject();
        assertEquals("clear", pass.getAsJsonArray("colorAttachments").get(0)
                .getAsJsonObject().get("loadAction").getAsString());
        assertEquals("store", pass.getAsJsonArray("colorAttachments").get(0)
                .getAsJsonObject().get("storeAction").getAsString());
        assertEquals("load", pass.getAsJsonArray("colorAttachments").get(1)
                .getAsJsonObject().get("loadAction").getAsString());
        assertEquals("dontCare", pass.getAsJsonArray("colorAttachments").get(1)
                .getAsJsonObject().get("storeAction").getAsString());
        assertEquals("store", pass.getAsJsonObject("depthAttachment")
                .get("storeAction").getAsString());
    }

    @Test
    void traceIdentityIsSharedByPassAndItsProducers() throws Exception {
        Path output = Files.createTempDirectory("render-contract-trace-identity-");
        RenderTraceRecorder recorder = new RenderTraceRecorder(output, "trace-test", "test", 2, 4, 8);
        recorder.beginFrame(9L);
        long token = recorder.beginPass(
                "synthetic/identity", PassType.COMPUTE, List.of(), null, null,
                new ViewportRecord(0, 0, 1, 1), ScissorRecord.disabled(), "pipeline", List.of(),
                Map.of("commandBufferSubmissionId", "42")
        );
        TraceIdentity passIdentity = recorder.traceIdentity(token);
        recorder.recordProducer(token, ProducerType.DISPATCH, "pipeline", Map.of(), Map.of(), List.of());
        recorder.endPass(token);
        recorder.close();

        RenderPassRecord pass = recorder.completedPasses().get(0);
        assertEquals(passIdentity, pass.traceIdentity());
        assertEquals(passIdentity.forProducer(0), pass.producers().get(0).traceIdentity());
        assertEquals("metallum-trace[run=trace-test,frame=9,pass=0,semantic=synthetic/identity,producer=-1,submit=42]",
                passIdentity.debugLabel());
    }

    @Test
    void producerCapturePolicyCanLimitDiagnosticDetailsToAStablePassRange() throws Exception {
        String previousEnabled = System.getProperty("metallum.renderContract.captureProducers");
        String previousPass = System.getProperty("metallum.renderContract.tracePass");
        String previousRange = System.getProperty("metallum.renderContract.producerRange");
        try {
            System.setProperty("metallum.renderContract.captureProducers", "true");
            System.setProperty("metallum.renderContract.tracePass", "synthetic/range");
            System.setProperty("metallum.renderContract.producerRange", "2:3");
            Path output = Files.createTempDirectory("render-contract-producer-range-");
            RenderTraceRecorder recorder = new RenderTraceRecorder(output, "producer-range", "test", 2, 4, 8);
            recorder.beginFrame(1L);
            long token = recorder.beginPass(
                    "synthetic/range", PassType.RENDER, List.of(), null, null,
                    new ViewportRecord(0, 0, 1, 1), ScissorRecord.disabled(), "pipeline", List.of(), Map.of()
            );
            for (int index = 0; index < 4; index++) {
                recorder.recordProducer(token, ProducerType.DRAW, "pipeline", Map.of(), Map.of(), List.of());
            }
            recorder.endPass(token);
            recorder.close();

            RenderPassRecord pass = recorder.completedPasses().get(0);
            assertEquals(List.of(2, 3), pass.producers().stream().map(ProducerRecord::producerIndex).toList());
            assertEquals("false", pass.metadata().get("producerDetailsComplete"));
            assertTrue(pass.metadata().get("producerCapturePolicy").contains("range=2:3"));
        } finally {
            restoreProperty("metallum.renderContract.captureProducers", previousEnabled);
            restoreProperty("metallum.renderContract.tracePass", previousPass);
            restoreProperty("metallum.renderContract.producerRange", previousRange);
        }
    }

    @Test
    void capturePointRejectsAnIdentityForAnotherProducer() {
        TraceIdentity identity = new TraceIdentity("capture-point", 1L, 3, "synthetic/pass", 2, 7L);
        assertThrows(IllegalArgumentException.class, () -> new CapturePoint(
                1L, "synthetic/pass", CapturePointKind.AFTER_PRODUCER, 1, identity
        ));
    }

    @Test
    void producerDetailsCanBeDisabledWithoutDroppingProducerCounts() throws Exception {
        String previous = System.getProperty("metallum.renderContract.captureProducers");
        Path output = Files.createTempDirectory("render-contract-producer-count-");
        try {
            System.setProperty("metallum.renderContract.captureProducers", "false");
            RenderTraceRecorder recorder = new RenderTraceRecorder(output, "producer-count-test", "test", 4, 8, 16);
            recorder.beginFrame(1L);
            long token = recorder.beginPass(
                    "synthetic/producer-count", PassType.RENDER, List.of(), null, null,
                    new ViewportRecord(0, 0, 1, 1), ScissorRecord.disabled(), "pipeline", List.of(), Map.of()
            );
            recorder.recordProducer(token, ProducerType.DRAW, "pipeline", Map.of(), Map.of("texture", "resource"), List.of());
            recorder.endPass(token);
            recorder.close();

            JsonObject manifest = com.google.gson.JsonParser.parseString(
                    Files.readString(output.resolve("pass-manifest.json"))
            ).getAsJsonObject();
            JsonObject pass = manifest.getAsJsonArray("passes").get(0).getAsJsonObject();
            assertFalse(recorder.producerDetailsCaptured());
            assertEquals(1L, manifest.get("producerCount").getAsLong());
            assertFalse(pass.getAsJsonArray("producers").size() > 0);
            assertEquals("1", pass.getAsJsonObject("metadata").get("producerCount").getAsString());
            assertEquals("false", pass.getAsJsonObject("metadata").get("producerDetailsCaptured").getAsString());
        } finally {
            if (previous == null) {
                System.clearProperty("metallum.renderContract.captureProducers");
            } else {
                System.setProperty("metallum.renderContract.captureProducers", previous);
            }
        }
    }

    @Test
    void manifestFinalizedTracksTheLatestTraceFlush() throws Exception {
        Path output = Files.createTempDirectory("render-contract-finalized-");
        RenderTraceRecorder recorder = new RenderTraceRecorder(output, "finalized-test", "test", 4, 8, 16);
        assertTrue(recorder.manifestFinalized());
        recorder.beginFrame(1L);
        assertFalse(recorder.manifestFinalized());
        recorder.endFrame(1L);
        assertTrue(recorder.manifestFinalized());
        recorder.close();
        assertTrue(recorder.manifestFinalized());
    }

    @Test
    void explicitManifestFlushPublishesTheLastFrameBeforeClose() throws Exception {
        Path output = Files.createTempDirectory("render-contract-explicit-flush-");
        RenderTraceRecorder recorder = new RenderTraceRecorder(
                output, "explicit-flush-test", "test", 4, 8, 16
        );
        recorder.beginFrame(99L);
        assertFalse(recorder.manifestFinalized());
        recorder.flushManifest();

        JsonObject manifest = com.google.gson.JsonParser.parseString(
                Files.readString(output.resolve("pass-manifest.json"))
        ).getAsJsonObject();
        assertTrue(recorder.manifestFinalized());
        assertEquals(1, manifest.get("frameCount").getAsInt());
        assertFalse(manifest.get("manifestComplete").getAsBoolean());
        recorder.close();
    }

    @Test
    void manifestBudgetFailureKeepsTerminalEvidenceWritableWithoutExhaustingArtifactBudget() throws Exception {
        String previousLimit = System.getProperty("metallum.renderContract.maxManifestBytes");
        Path output = Files.createTempDirectory("render-contract-manifest-budget-");
        try {
            System.setProperty("metallum.renderContract.maxManifestBytes", "256");
            RenderTraceRecorder recorder = new RenderTraceRecorder(
                    output, "manifest-budget-test", "test", 4, 8, 16
            );
            recorder.close();

            JsonObject manifest = com.google.gson.JsonParser.parseString(
                    Files.readString(output.resolve("pass-manifest.json"))
            ).getAsJsonObject();
            assertFalse(recorder.manifestFinalized());
            assertFalse(ValidationStorageBudget.shared(output).exceeded());
            assertFalse(manifest.get("manifestComplete").getAsBoolean());
            assertTrue(manifest.get("requiredManifestBytes").getAsLong() > 256L);
        } finally {
            if (previousLimit == null) {
                System.clearProperty("metallum.renderContract.maxManifestBytes");
            } else {
                System.setProperty("metallum.renderContract.maxManifestBytes", previousLimit);
            }
        }
    }

    @Test
    void closeDoesNotTurnAnOpenPassIntoACompleteManifest() throws Exception {
        Path output = Files.createTempDirectory("render-contract-open-pass-");
        RenderTraceRecorder recorder = new RenderTraceRecorder(
                output, "open-pass-test", "test", 4, 8, 16
        );
        recorder.beginFrame(3L);
        recorder.beginPass(
                "synthetic/open", PassType.RENDER, List.of(), null, null,
                new ViewportRecord(0, 0, 1, 1), ScissorRecord.disabled(),
                "pipeline", List.of(), Map.of()
        );
        recorder.close();

        JsonObject manifest = com.google.gson.JsonParser.parseString(
                Files.readString(output.resolve("pass-manifest.json"))
        ).getAsJsonObject();
        assertEquals("incomplete", recorder.status());
        assertEquals(1, recorder.forcedClosedPassCount());
        assertFalse(recorder.manifestComplete());
        assertFalse(manifest.get("manifestComplete").getAsBoolean());
        assertTrue(manifest.getAsJsonArray("passes").get(0).getAsJsonObject()
                .getAsJsonObject("metadata").get("forcedClose").getAsBoolean());
    }

    @Test
    void unknownPassReferenceIsACompletionFailure() throws Exception {
        Path output = Files.createTempDirectory("render-contract-invalid-pass-");
        RenderTraceRecorder recorder = new RenderTraceRecorder(
                output, "invalid-pass-test", "test", 4, 8, 16
        );
        recorder.beginFrame(1L);
        recorder.endPass(999L);
        recorder.close();

        JsonObject manifest = com.google.gson.JsonParser.parseString(
                Files.readString(output.resolve("pass-manifest.json"))
        ).getAsJsonObject();
        assertEquals("failed", recorder.status());
        assertEquals(1, recorder.invalidPassReferenceCount());
        assertFalse(recorder.manifestComplete());
        assertEquals(1, manifest.get("invalidPassReferenceCount").getAsInt());
        assertFalse(manifest.get("manifestComplete").getAsBoolean());
    }

    @Test
    void producerBudgetTruncationIsRecordedAsIncompleteEvidence() throws Exception {
        String previous = System.getProperty("metallum.renderContract.captureProducers");
        try {
            System.setProperty("metallum.renderContract.captureProducers", "true");
            Path output = Files.createTempDirectory("render-contract-producer-budget-");
            RenderTraceRecorder recorder = new RenderTraceRecorder(
                    output, "producer-budget-test", "test", 4, 8, 1
            );
            recorder.beginFrame(1L);
            long token = recorder.beginPass(
                    "synthetic/producer-budget", PassType.RENDER, List.of(), null, null,
                    new ViewportRecord(0, 0, 1, 1), ScissorRecord.disabled(),
                    "pipeline", List.of(), Map.of()
            );
            recorder.recordProducer(token, ProducerType.DRAW, "pipeline", Map.of(), Map.of(), List.of());
            recorder.recordProducer(token, ProducerType.DRAW, "pipeline", Map.of(), Map.of(), List.of());
            recorder.endPass(token);
            recorder.close();

            RenderPassRecord pass = recorder.completedPasses().get(0);
            assertTrue(recorder.producerBudgetExceeded());
            assertEquals("true", pass.metadata().get("producerDetailsTruncated"));
            assertEquals("false", pass.metadata().get("producerDetailsComplete"));
            assertFalse(recorder.manifestComplete());
        } finally {
            restoreProperty("metallum.renderContract.captureProducers", previous);
        }
    }

    @Test
    void captureFormatRecognizesCommonMetalFormats() {
        CaptureFormat rgba8 = CaptureFormat.fromFormat("RGBA8_UNORM", 4);
        CaptureFormat bgra8 = CaptureFormat.fromFormat("BGRA8_UNORM", 4);
        CaptureFormat motion = CaptureFormat.fromFormat("RG16_FLOAT", 4);
        CaptureFormat depth = CaptureFormat.fromFormat("DEPTH32_FLOAT", 4);
        assertEquals(CaptureFormat.ComponentType.UINT8, rgba8.componentType());
        assertEquals(4, rgba8.componentCount());
        assertTrue(rgba8.normalized());
        assertEquals(CaptureFormat.ComponentType.UINT8, bgra8.componentType());
        assertEquals(4, bgra8.componentCount());
        assertEquals(CaptureFormat.ComponentType.FLOAT16, motion.componentType());
        assertEquals(2, motion.componentCount());
        assertEquals(CaptureFormat.ComponentType.FLOAT32, depth.componentType());
        assertTrue(depth.depth());
        assertFalse(depth.stencil());
    }

    @Test
    void blankResourceNameIsRecordedAsStableUnclassifiedIdentity() throws Exception {
        Path output = Files.createTempDirectory("render-contract-unclassified-");
        RenderTraceRecorder recorder = new RenderTraceRecorder(output, "unclassified-test", "test", 4, 8, 16);
        ResourceIdentity first = recorder.identifyResource(
                "", 42L, "metal-texture-42", "BGRA8_UNORM", 8, 8, 1, 0, 1, 3
        );
        ResourceIdentity same = recorder.identifyResource(
                null, 42L, "metal-texture-42", "BGRA8_UNORM", 8, 8, 1, 0, 1, 3
        );
        recorder.close();

        assertTrue(first.semanticName().startsWith("unclassified/"));
        assertEquals(first, same);
    }

    @Test
    void semanticPassLabelsResolveWithoutShaderPackNames() {
        assertEquals("iris/final", SemanticPassIdResolver.resolve("Iris final: final0"));
        assertEquals("iris/composite/3", SemanticPassIdResolver.resolve("Iris composite 3"));
        assertEquals("iris/shadow/2", SemanticPassIdResolver.resolve("iris shadowcomp 2"));
        assertEquals("iris/shadow/0", SemanticPassIdResolver.resolve("Iris shadow_composite: shadowcomp"));
        assertEquals("iris/deferred/0", SemanticPassIdResolver.resolve("Iris deferred: deferred"));
        assertEquals("iris/deferred/1", SemanticPassIdResolver.resolve("Iris deferred: deferred1"));
        assertEquals("iris/prepare/0", SemanticPassIdResolver.resolve("Iris prepare: prepare"));
        assertEquals("iris/begin/0", SemanticPassIdResolver.resolve("Iris begin: begin"));
        assertEquals("iris/color-space", SemanticPassIdResolver.resolve("Iris color space: DCI_P3"));
        assertEquals("iris/gbuffers/terrain", SemanticPassIdResolver.resolve(
                "iris/gbuffers/terrain | source=Terrain"
        ));
        assertEquals("minecraft/transparency/0", SemanticPassIdResolver.resolve(
                "Post pass minecraft:transparency/0"
        ));
        assertEquals("minecraft/blur/5", SemanticPassIdResolver.resolve(
                "Post pass minecraft:blur/5"
        ));
        assertEquals("minecraft/pipeline/item_cutout", SemanticPassIdResolver.resolve(
                "Immediate draw with minecraft:pipeline/item_cutout"
        ));
        assertEquals("minecraft/texture-animation/textures/atlas/blocks.png", SemanticPassIdResolver.resolve(
                "Animate minecraft:textures/atlas/blocks.png"
        ));
        assertEquals("minecraft/particles/solid", SemanticPassIdResolver.resolve("Particles - Solid"));
        assertEquals("minecraft/gui/before-blur", SemanticPassIdResolver.resolve("GUI before blur"));
        assertEquals("minecraft/blit-render-target", SemanticPassIdResolver.resolve("Blit render target"));
        assertEquals("metallum/object-motion", SemanticPassIdResolver.resolve(
                "Metallum batched ordinary entity object motion"
        ));
        assertEquals("minecraft/world/opaque", SemanticPassIdResolver.resolve("minecraft/world/opaque"));
        assertTrue(SemanticPassIdResolver.resolve("pack-specific label").startsWith("unclassified/"));
    }

    private static void restoreProperty(final String name, final String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
