package com.metallum.client.validation.report;

import com.metallum.client.validation.capture.CapturedResource;
import com.metallum.client.validation.contract.AttachmentBindingRecord;
import com.metallum.client.validation.contract.AttachmentSemantic;
import com.metallum.client.validation.contract.CaptureFormat;
import com.metallum.client.validation.contract.CapturePointKind;
import com.metallum.client.validation.contract.ProducerRecord;
import com.metallum.client.validation.contract.ProducerType;
import com.metallum.client.validation.contract.RenderPassRecord;
import com.metallum.client.validation.contract.PassType;
import com.metallum.client.validation.contract.ResourceIdentity;
import com.metallum.client.validation.contract.ScissorRecord;
import com.metallum.client.validation.contract.ViewportRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RenderContractReportTest {
    private static final ResourceIdentity RESOURCE = new ResourceIdentity(
            "color0", 1L, 1L, "texture-1", "RGBA8_UNORM", 1, 1, 1, 0, 1, 3
    );

    @Test
    void captureComparatorReportsFirstDivergentPassAndMetrics() {
        CapturedResource expected = resource(new byte[]{1, 2, 3, 4});
        CapturedResource actual = resource(new byte[]{1, 12, 3, 4});
        DivergenceReport result = PassManifestComparator.compareCaptures(
                List.of(new CaptureSnapshot(4, 2, "iris/composite/0", 1, "color0", expected)),
                List.of(new CaptureSnapshot(4, 2, "iris/composite/0", 1, "color0", actual))
        );

        assertFalse(result.matched());
        assertEquals("iris/composite/0", result.firstDivergentPass());
        assertEquals(1, result.producerIndex());
        assertEquals(1, result.metrics().get("mismatchBytes"));
        assertEquals(10, result.metrics().get("maxError"));
    }

    @Test
    void captureComparatorReportsResourceGenerationDivergenceBeforeBytes() {
        ResourceIdentity reallocated = new ResourceIdentity(
                "color0", RESOURCE.runtimeId(), RESOURCE.generation() + 1,
                RESOURCE.nativeHandleHashOrDebugId(), RESOURCE.format(), RESOURCE.width(), RESOURCE.height(),
                RESOURCE.depthOrLayers(), RESOURCE.mipLevel(), RESOURCE.sampleCount(), RESOURCE.usage()
        );
        DivergenceReport result = PassManifestComparator.compareCaptures(
                List.of(new CaptureSnapshot(4, 2, "iris/composite/0", -1, "color0",
                        resource(RESOURCE, new byte[]{1, 2, 3, 4}))),
                List.of(new CaptureSnapshot(4, 2, "iris/composite/0", -1, "color0",
                        resource(reallocated, new byte[]{1, 2, 3, 4})))
        );

        assertFalse(result.matched());
        assertEquals("captured attachment resource generation differs", result.reason());
    }

    @Test
    void producerComparatorLocalizesProducerTypeDifference() {
        ProducerRecord expectedProducer = producer(ProducerType.DRAW);
        ProducerRecord actualProducer = producer(ProducerType.DISPATCH);
        RenderPassRecord expected = pass(List.of(expectedProducer));
        RenderPassRecord actual = pass(List.of(actualProducer));

        DivergenceReport result = PassManifestComparator.compareProducers(expected, actual);

        assertFalse(result.matched());
        assertEquals(0, result.producerIndex());
        assertEquals("producer type differs", result.reason());
    }

    @Test
    void producerComparatorReportsPipelineAndBindingEvidence() {
        ProducerRecord expectedProducer = new ProducerRecord(
                0, ProducerType.DRAW, "pipeline/reference", List.of("vertex/reference"),
                Map.of("vertexCount", "3"), Map.of("texture", "color0@1"),
                new ViewportRecord(0, 0, 1, 1), ScissorRecord.disabled(), List.of("color0")
        );
        ProducerRecord actualProducer = new ProducerRecord(
                0, ProducerType.DRAW, "pipeline/actual", List.of("vertex/actual"),
                Map.of("vertexCount", "4"), Map.of("texture", "color0@2"),
                new ViewportRecord(1, 0, 1, 1), new ScissorRecord(true, 0, 0, 1, 1), List.of("color1")
        );

        DivergenceReport result = PassManifestComparator.compareProducers(
                pass(List.of(expectedProducer)), pass(List.of(actualProducer))
        );

        assertFalse(result.matched());
        assertEquals("producer pipeline differs", result.reason());
        assertEquals("pipeline/reference", result.metrics().get("expectedPipelineId"));
        assertEquals("pipeline/actual", result.metrics().get("actualPipelineId"));
        assertEquals(List.of("vertex/reference"), result.metrics().get("expectedShaderIds"));
        assertEquals(Map.of("texture", "color0@2"), result.metrics().get("actualBoundResources"));
    }

    @Test
    void producerComparatorReportsUnavailableEvidenceInsteadOfTreatingEmptyAsZero() {
        RenderPassRecord expected = passWithoutProducerDetails(3);
        RenderPassRecord actual = passWithoutProducerDetails(3);

        DivergenceReport result = PassManifestComparator.compareProducers(expected, actual);

        assertFalse(result.matched());
        assertEquals(
                "producer comparison unavailable: producer details were not captured",
                result.reason()
        );
        assertEquals(false, result.metrics().get("producerComparisonSupported"));
    }

    @Test
    void passComparatorUsesProducerCountsWhenDetailsAreDisabled() {
        RenderPassRecord expected = passWithoutProducerDetails(2);
        RenderPassRecord actual = passWithoutProducerDetails(3);

        DivergenceReport result = PassManifestComparator.compare(List.of(expected), List.of(actual));

        assertFalse(result.matched());
        assertEquals("producerCount differs", result.reason());
    }

    @Test
    void manifestComparatorReportsMissingActualStream() {
        DivergenceReport result = PassManifestComparator.compare(
                List.of(pass(List.of())),
                null
        );

        assertFalse(result.matched());
        assertEquals("actual manifest ended early", result.reason());
    }

    @Test
    void captureComparatorReportsMissingActualStream() {
        DivergenceReport result = PassManifestComparator.compareCaptures(
                List.of(new CaptureSnapshot(4, 2, "iris/composite/0", -1, "color0", resource(new byte[]{1, 2, 3, 4}))),
                null
        );

        assertFalse(result.matched());
        assertEquals("actual capture stream ended early", result.reason());
    }

    @Test
    void semanticAlignmentIgnoresBackendPrivatePipelineAndNativeSequence() {
        RenderPassRecord expected = passWithContract(
                0, 4, "iris/composite/0", "pipeline/opengl", Map.of(), List.of()
        );
        RenderPassRecord actual = passWithContract(
                0, 19, "iris/composite/0", "pipeline/metal", Map.of(), List.of()
        );

        DivergenceReport result = PassManifestComparator.compare(List.of(expected), List.of(actual));

        assertTrue(result.matched(), result.toString());
    }

    @Test
    void attachmentComparisonIncludesSemanticNameGenerationAndUsage() {
        ResourceIdentity reallocated = new ResourceIdentity(
                "color0", RESOURCE.runtimeId(), RESOURCE.generation() + 1,
                RESOURCE.nativeHandleHashOrDebugId(), RESOURCE.format(), RESOURCE.width(), RESOURCE.height(),
                RESOURCE.depthOrLayers(), RESOURCE.mipLevel(), RESOURCE.sampleCount(), RESOURCE.usage()
        );
        RenderPassRecord expected = passWithAttachment(RESOURCE);
        RenderPassRecord actual = passWithAttachment(reallocated);

        DivergenceReport result = PassManifestComparator.compare(List.of(expected), List.of(actual));

        assertFalse(result.matched());
        assertEquals("color attachment contract differs", result.reason());
    }

    @Test
    void crossBackendPolicyIgnoresAbsoluteGenerationButChecksLineage() {
        ResourceIdentity referenceFirst = resourceIdentity("color0", 10L, 41L);
        ResourceIdentity actualFirst = resourceIdentity("color0", 20L, 7L);
        RenderPassRecord referencePass = passWithAttachmentAt(0, 0, referenceFirst);
        RenderPassRecord actualPass = passWithAttachmentAt(0, 19, actualFirst);

        DivergenceReport equivalent = PassManifestComparator.compare(
                List.of(referencePass), List.of(actualPass), ManifestAlignmentPolicy.crossBackend()
        );

        assertTrue(equivalent.matched(), equivalent.toString());

        ResourceIdentity referenceSecond = resourceIdentity("color0", 11L, 42L);
        ResourceIdentity actualSecond = resourceIdentity("color0", 20L, 8L);
        DivergenceReport sameTransition = PassManifestComparator.compare(
                List.of(referencePass, passWithAttachmentAt(1, 0, referenceSecond)),
                List.of(actualPass, passWithAttachmentAt(1, 3, actualSecond)),
                ManifestAlignmentPolicy.crossBackend()
        );
        assertTrue(sameTransition.matched(), sameTransition.toString());

        ResourceIdentity actualUnchanged = resourceIdentity("color0", 20L, 7L);
        DivergenceReport wrongTransition = PassManifestComparator.compare(
                List.of(referencePass, passWithAttachmentAt(1, 0, referenceSecond)),
                List.of(actualPass, passWithAttachmentAt(1, 3, actualUnchanged)),
                ManifestAlignmentPolicy.crossBackend()
        );
        assertFalse(wrongTransition.matched());
        assertEquals("resource generation lineage differs", wrongTransition.reason());
    }

    @Test
    void prefixEndpointUsesFrameLocalSequenceForTemporalPasses() {
        RenderContractDivergenceRunner.PassKey target =
                new RenderContractDivergenceRunner.PassKey(3L, 17, "synthetic/temporal", 4);
        RenderContractDivergenceRunner.CapturePlan plan =
                RenderContractDivergenceRunner.CapturePlan.full(0L, 5L)
                        .withPrefixEndpoint(target);

        assertEquals(0L, plan.frameStartInclusive());
        assertEquals(3L, plan.frameEndInclusive());
        assertEquals(0, plan.passStartInclusive());
        assertEquals(4, plan.passEndInclusive());
        assertEquals(CapturePointKind.AFTER_PASS, plan.capturePointKind());
    }

    @Test
    void undeclaredAdditionalSemanticPassFailsClosed() {
        RenderPassRecord expected = passWithContract(
                0, 0, "iris/final", "pipeline/reference", Map.of(), List.of()
        );
        RenderPassRecord extra = passWithContract(
                0, 1, "metallum/private-debug", "pipeline/metal", Map.of(), List.of()
        );

        DivergenceReport result = PassManifestComparator.compare(List.of(expected), List.of(expected, extra));

        assertFalse(result.matched());
        assertEquals("actual manifest has an extra semantic pass", result.reason());
    }

    @Test
    void backendPrivatePassRequiresExplicitPolicyToIgnore() {
        RenderPassRecord expected = passWithContract(
                0, 0, "iris/final", "pipeline/reference", Map.of(), List.of()
        );
        RenderPassRecord privatePass = new RenderPassRecord(
                0, 1, "metallum/debug", PassType.RENDER, List.of(), null, null,
                new ViewportRecord(0, 0, 1, 1), ScissorRecord.disabled(), "pipeline/metal", List.of(),
                List.of(), Map.of("backendPrivate", "true")
        );
        ManifestAlignmentPolicy policy = new ManifestAlignmentPolicy(
                Map.of(), java.util.Set.of("metallum/debug"), java.util.Set.of(), Map.of(), false
        );

        DivergenceReport result = PassManifestComparator.compare(
                List.of(expected), List.of(expected, privatePass), policy
        );

        assertTrue(result.matched(), result.toString());
    }

    @Test
    void privateMetadataDoesNotBypassStrictPolicy() {
        RenderPassRecord expected = passWithContract(
                0, 0, "iris/final", "pipeline/reference", Map.of(), List.of()
        );
        RenderPassRecord privatePass = new RenderPassRecord(
                0, 1, "metallum/debug", PassType.RENDER, List.of(), null, null,
                new ViewportRecord(0, 0, 1, 1), ScissorRecord.disabled(), "pipeline/metal", List.of(),
                List.of(), Map.of("backendPrivate", "true")
        );

        DivergenceReport result = PassManifestComparator.compare(List.of(expected), List.of(expected, privatePass));

        assertFalse(result.matched());
        assertEquals("actual manifest has an extra semantic pass", result.reason());
    }

    @Test
    void declaredSplitAllowsDifferentNativePassMultiplicityButNotUnrelatedContracts() {
        RenderPassRecord expected = passWithContract(
                0, 0, "iris/composite/0", "pipeline/reference", Map.of(), List.of()
        );
        RenderPassRecord splitA = passWithContract(
                0, 3, "iris/composite/0", "pipeline/metal-a", Map.of(), List.of()
        );
        RenderPassRecord splitB = passWithContract(
                0, 4, "iris/composite/0", "pipeline/metal-b", Map.of(), List.of()
        );
        ManifestAlignmentPolicy policy = new ManifestAlignmentPolicy(
                Map.of(), java.util.Set.of(), java.util.Set.of(),
                Map.of("iris/composite/0", ManifestAlignmentPolicy.Multiplicity.ALLOW_SPLIT), false
        );

        DivergenceReport result = PassManifestComparator.compare(
                List.of(expected), List.of(splitA, splitB), policy
        );

        assertTrue(result.matched(), result.toString());
    }

    @Test
    void undeclaredSplitFailsEvenWhenSemanticIdMatches() {
        RenderPassRecord expected = passWithContract(
                0, 0, "iris/composite/0", "pipeline/reference", Map.of(), List.of()
        );
        RenderPassRecord split = passWithContract(
                0, 1, "iris/composite/0", "pipeline/metal", Map.of(), List.of()
        );

        DivergenceReport result = PassManifestComparator.compare(List.of(expected), List.of(expected, split));

        assertFalse(result.matched());
        assertEquals("semantic pass occurrence count differs", result.reason());
    }

    @Test
    void replayRunnerBinarySearchesTheFirstBadPassAndProducer() throws Exception {
        List<RenderPassRecord> referencePasses = new java.util.ArrayList<>();
        for (int passIndex = 0; passIndex < 8; passIndex++) {
            referencePasses.add(passWithProducers(
                    0, passIndex, "synthetic/pass-" + passIndex, 4, false
            ));
        }
        RenderContractDivergenceRunner.RunEvidence reference = new RenderContractDivergenceRunner.RunEvidence(
                referencePasses, List.of(), "passed", completeEvidence()
        );
        RenderContractDivergenceRunner.ReplayRunner fake = plan -> {
            List<RenderPassRecord> selected = new java.util.ArrayList<>();
            int passEnd = Math.min(plan.passEndInclusive(), referencePasses.size() - 1);
            for (int passIndex = Math.max(0, plan.passStartInclusive()); passIndex <= passEnd; passIndex++) {
                RenderPassRecord source = referencePasses.get(passIndex);
                if (passIndex == 5 && plan.capturePointKind() == CapturePointKind.AFTER_PRODUCER) {
                    int producerEnd = plan.producerEndInclusive() < 0
                            ? source.producers().size() - 1
                            : Math.min(plan.producerEndInclusive(), source.producers().size() - 1);
                    selected.add(passWithProducers(0, passIndex, source.semanticPassId(), producerEnd + 1, true));
                } else if (passIndex == 5) {
                    selected.add(passWithProducers(0, passIndex, source.semanticPassId(), 4, true));
                } else if (passIndex > 5) {
                    break;
                } else {
                    selected.add(source);
                }
            }
            return new RenderContractDivergenceRunner.RunEvidence(selected, List.of(), "passed", completeEvidence());
        };

        RenderContractDivergenceRunner.LocalizationResult result =
                RenderContractDivergenceRunner.locate(
                        fake,
                        reference,
                        new RenderContractDivergenceRunner.RunEvidence(
                                referencePasses.stream().map(pass -> pass.sequence() == 5
                                        ? passWithProducers(0, 5, pass.semanticPassId(), 4, true)
                                        : pass).toList(),
                                List.of(), "failed", completeEvidence()
                        ),
                        RenderContractDivergenceRunner.CapturePlan.full(0, 0)
                );

        assertFalse(result.matched());
        assertEquals("synthetic/pass-5", result.firstDivergentPass().semanticPassId());
        assertEquals(3, result.firstDivergentProducer());
        assertTrue(result.replayPlans().size() >= 4, result.toString());
    }

    @Test
    void replayRunnerFindsAFrameOneDivergenceUsingFrameLocalSequence() throws Exception {
        List<RenderPassRecord> referencePasses = List.of(
                passWithContract(0, 0, "synthetic/frame-zero-a", "pipeline", Map.of(), List.of()),
                passWithContract(0, 1, "synthetic/frame-zero-b", "pipeline", Map.of(), List.of()),
                passWithContract(1, 0, "synthetic/frame-one-a", "pipeline", Map.of(), List.of()),
                passWithContract(1, 1, "synthetic/frame-one-b", "pipeline", Map.of(), List.of()),
                passWithContract(1, 2, "synthetic/frame-one-c", "pipeline", Map.of(), List.of())
        );
        RenderPassRecord divergent = passWithType(
                1, 1, "synthetic/frame-one-b", PassType.COMPUTE, "pipeline"
        );
        List<RenderPassRecord> initialActualPasses = List.of(
                referencePasses.get(0), referencePasses.get(1), referencePasses.get(2),
                divergent, referencePasses.get(4)
        );
        RenderContractDivergenceRunner.RunEvidence reference = new RenderContractDivergenceRunner.RunEvidence(
                referencePasses, List.of(), "passed", completeEvidence()
        );
        RenderContractDivergenceRunner.RunEvidence initialActual = new RenderContractDivergenceRunner.RunEvidence(
                initialActualPasses, List.of(), "failed", completeEvidence()
        );

        RenderContractDivergenceRunner.ReplayRunner fake = plan -> {
            List<RenderPassRecord> selected = new java.util.ArrayList<>();
            for (RenderPassRecord expected : referencePasses) {
                if (expected.frameId() > plan.frameEndInclusive()) continue;
                if (expected.frameId() == plan.frameEndInclusive()
                        && expected.sequence() > plan.passEndInclusive()) continue;
                if (expected.frameId() == 1L && expected.sequence() == 1
                        && plan.passEndInclusive() >= 1) {
                    selected.add(divergent);
                } else {
                    selected.add(expected);
                }
            }
            if (plan.capturePointKind() == CapturePointKind.AFTER_PRODUCER
                    && plan.semanticPassId().equals("synthetic/frame-one-b")) {
                selected = List.of(divergent);
            }
            return new RenderContractDivergenceRunner.RunEvidence(
                    selected, List.of(), "passed", completeEvidence()
            );
        };

        RenderContractDivergenceRunner.LocalizationResult result =
                RenderContractDivergenceRunner.locate(
                        fake,
                        reference,
                        initialActual,
                        RenderContractDivergenceRunner.CapturePlan.full(0, 1)
                );

        assertFalse(result.matched());
        assertEquals(1L, result.firstDivergentPass().frameId());
        assertEquals(1, result.firstDivergentPass().sequence());
        assertEquals("synthetic/frame-one-b", result.firstDivergentPass().semanticPassId());
        assertEquals("localized-pass-only", result.status());
    }

    @Test
    void replayRunnerDoesNotTurnAFailedPrefixReplayIntoAFalseDivergence() throws Exception {
        List<RenderPassRecord> referencePasses = List.of(
                passWithContract(0, 0, "synthetic/first", "pipeline", Map.of(), List.of()),
                passWithContract(0, 1, "synthetic/second", "pipeline", Map.of(), List.of()),
                passWithContract(0, 2, "synthetic/third", "pipeline", Map.of(), List.of())
        );
        RenderPassRecord divergent = passWithType(
                0, 2, "synthetic/third", PassType.COMPUTE, "pipeline"
        );
        RenderContractDivergenceRunner.LocalizationResult result =
                RenderContractDivergenceRunner.locate(
                        plan -> {
                            throw new IllegalStateException("simulated replay timeout");
                        },
                        new RenderContractDivergenceRunner.RunEvidence(
                                referencePasses, List.of(), "passed", completeEvidence()
                        ),
                        new RenderContractDivergenceRunner.RunEvidence(
                                List.of(referencePasses.get(0), referencePasses.get(1), divergent),
                                List.of(), "failed", completeEvidence()
                        ),
                        RenderContractDivergenceRunner.CapturePlan.full(0, 0)
                );

        assertFalse(result.matched());
        assertEquals("pass-localization-incomplete", result.status());
        assertTrue(result.firstDivergentPass() == null);
        assertTrue(result.evidence().get("reason").toString().contains("simulated replay timeout"));
    }

    @Test
    void replayRunnerUsesAttachmentEvidenceWhenManifestIsStructurallyIdentical() throws Exception {
        List<RenderPassRecord> referencePasses = new java.util.ArrayList<>();
        List<CaptureSnapshot> referenceCaptures = new java.util.ArrayList<>();
        for (int passIndex = 0; passIndex < 8; passIndex++) {
            referencePasses.add(passWithContract(
                    0, passIndex, "synthetic/capture-pass-" + passIndex, "pipeline", Map.of(), List.of()
            ));
            referenceCaptures.add(new CaptureSnapshot(
                    0, passIndex, "synthetic/capture-pass-" + passIndex, -1, "color0",
                    resource(new byte[]{(byte) passIndex, 0, 0, (byte) 255})
            ));
        }
        RenderContractDivergenceRunner.RunEvidence reference = new RenderContractDivergenceRunner.RunEvidence(
                referencePasses, referenceCaptures, "passed", completeEvidence()
        );
        RenderContractDivergenceRunner.ReplayRunner fake = plan -> {
            List<RenderPassRecord> selected = new java.util.ArrayList<>();
            List<CaptureSnapshot> captures = new java.util.ArrayList<>();
            int passEnd = Math.min(plan.passEndInclusive(), referencePasses.size() - 1);
            for (int passIndex = Math.max(0, plan.passStartInclusive()); passIndex <= passEnd; passIndex++) {
                RenderPassRecord pass = referencePasses.get(passIndex);
                selected.add(pass);
                if (plan.capturePointKind() == CapturePointKind.AFTER_PRODUCER
                        && plan.semanticPassId().equals(pass.semanticPassId())) {
                    int producer = plan.producerStartInclusive();
                    if (producer < 0) producer = -1;
                    captures.add(new CaptureSnapshot(
                            0, passIndex, pass.semanticPassId(), producer, "color0",
                            resource(new byte[]{(byte) (passIndex == 3 ? 99 : passIndex), 0, 0, (byte) 255})
                    ));
                } else if (plan.capturePointKind() == CapturePointKind.AFTER_PASS) {
                    captures.add(new CaptureSnapshot(
                            0, passIndex, pass.semanticPassId(), -1, "color0",
                            resource(new byte[]{(byte) (passIndex == 3 ? 99 : passIndex), 0, 0, (byte) 255})
                    ));
                }
            }
            return new RenderContractDivergenceRunner.RunEvidence(selected, captures, "failed", completeEvidence());
        };

        List<CaptureSnapshot> actualCaptures = referenceCaptures.stream().map(snapshot ->
                snapshot.semanticPassId().equals("synthetic/capture-pass-3")
                        ? new CaptureSnapshot(
                                snapshot.frameId(), snapshot.sequence(), snapshot.semanticPassId(),
                                snapshot.producerIndex(), snapshot.resource(),
                                resource(new byte[]{99, 0, 0, (byte) 255})
                        )
                        : snapshot
        ).toList();
        RenderContractDivergenceRunner.LocalizationResult result =
                RenderContractDivergenceRunner.locate(
                        fake,
                        reference,
                        new RenderContractDivergenceRunner.RunEvidence(
                                referencePasses, actualCaptures, "failed", completeEvidence()
                        ),
                        RenderContractDivergenceRunner.CapturePlan.full(0, 0)
                );

        assertFalse(result.matched());
        assertEquals("synthetic/capture-pass-3", result.firstDivergentPass().semanticPassId());
        assertEquals(-1, result.firstDivergentProducer());
        assertEquals("localized-pass-only", result.status());
    }

    @Test
    void divergenceLocalizationFailsClosedWhenEvidenceCompletionIsNotDeclared() throws Exception {
        List<RenderPassRecord> passes = List.of(
                passWithContract(0, 0, "synthetic/incomplete", "pipeline", Map.of(), List.of())
        );
        RenderContractDivergenceRunner.RunEvidence incomplete =
                new RenderContractDivergenceRunner.RunEvidence(passes, List.of(), "passed", Map.of());

        RenderContractDivergenceRunner.LocalizationResult result =
                RenderContractDivergenceRunner.locate(
                        plan -> {
                            throw new AssertionError("incomplete evidence must not trigger replay");
                        },
                        incomplete,
                        incomplete,
                        RenderContractDivergenceRunner.CapturePlan.full(0, 0)
                );

        assertFalse(result.matched());
        assertEquals("incomplete-evidence", result.status());
        assertTrue(result.replayPlans().isEmpty());
    }

    private static Map<String, Object> completeEvidence() {
        return Map.of(
                "evidenceComplete", true,
                "manifestComplete", true,
                "capturesComplete", true
        );
    }

    private static CapturedResource resource(final byte[] bytes) {
        return resource(RESOURCE, bytes);
    }

    private static CapturedResource resource(final ResourceIdentity identity, final byte[] bytes) {
        return new CapturedResource(
                "color0", identity, CaptureFormat.fromFormat("RGBA8_UNORM", 4), 1, 1, bytes
        );
    }

    private static ProducerRecord producer(final ProducerType type) {
        return new ProducerRecord(
                0, type, "pipeline", List.of(), Map.of(), Map.of(),
                new ViewportRecord(0, 0, 1, 1), ScissorRecord.disabled(), List.of("color0")
        );
    }

    private static RenderPassRecord pass(final List<ProducerRecord> producers) {
        return new RenderPassRecord(
                4, 2, "iris/composite/0", PassType.RENDER, List.of(), null, null,
                new ViewportRecord(0, 0, 1, 1), ScissorRecord.disabled(), "pipeline", List.of(),
                producers, Map.of("producerDetailsCaptured", "true")
        );
    }

    private static RenderPassRecord passWithoutProducerDetails(final int producerCount) {
        return new RenderPassRecord(
                4, 2, "iris/composite/0", PassType.RENDER, List.of(), null, null,
                new ViewportRecord(0, 0, 1, 1), ScissorRecord.disabled(), "pipeline", List.of(),
                List.of(), Map.of(
                        "producerDetailsCaptured", "false",
                        "producerCount", Integer.toString(producerCount)
                )
        );
    }

    private static RenderPassRecord passWithContract(
            final long frame,
            final int sequence,
            final String semanticPassId,
            final String pipelineId,
            final Map<String, String> metadata,
            final List<ProducerRecord> producers
    ) {
        Map<String, String> merged = new java.util.LinkedHashMap<>(metadata);
        merged.putIfAbsent("producerDetailsCaptured", "true");
        merged.putIfAbsent("producerDetailsComplete", "true");
        merged.putIfAbsent("producerCapturePolicy", "enabled=true,pass=*,range=0:*,maxDetailed=1000000");
        return new RenderPassRecord(
                frame, sequence, semanticPassId, PassType.RENDER, List.of(), null, null,
                new ViewportRecord(0, 0, 1, 1), ScissorRecord.disabled(), pipelineId, List.of(),
                producers, merged
        );
    }

    private static RenderPassRecord passWithType(
            final long frame,
            final int sequence,
            final String semanticPassId,
            final PassType type,
            final String pipelineId
    ) {
        return new RenderPassRecord(
                frame, sequence, semanticPassId, type, List.of(), null, null,
                new ViewportRecord(0, 0, 1, 1), ScissorRecord.disabled(), pipelineId, List.of(),
                List.of(), Map.of(
                        "producerDetailsCaptured", "true",
                        "producerDetailsComplete", "true",
                        "producerDetailsTruncated", "false",
                        "producerCapturePolicy", "enabled=true,pass=*,range=0:*,maxDetailed=1000000"
                )
        );
    }

    private static RenderPassRecord passWithAttachment(final ResourceIdentity resource) {
        return passWithAttachmentAt(0, 0, resource);
    }

    private static RenderPassRecord passWithAttachmentAt(
            final long frame,
            final int sequence,
            final ResourceIdentity resource
    ) {
        return new RenderPassRecord(
                frame, sequence, "synthetic/attachment", PassType.RENDER,
                List.of(new AttachmentBindingRecord(
                        0, resource, AttachmentSemantic.COLOR, "CLEAR", "STORE", true
                )),
                null, null, new ViewportRecord(0, 0, 1, 1), ScissorRecord.disabled(),
                "pipeline", List.of(), List.of(), Map.of(
                        "producerDetailsCaptured", "true",
                        "producerDetailsComplete", "true",
                        "producerCapturePolicy", "enabled=true,pass=*,range=0:*,maxDetailed=1000000"
                )
        );
    }

    private static ResourceIdentity resourceIdentity(
            final String semanticName,
            final long runtimeId,
            final long generation
    ) {
        return new ResourceIdentity(
                semanticName, runtimeId, generation, "native-" + runtimeId,
                "RGBA8_UNORM", 1, 1, 1, 0, 1, 3
        );
    }

    private static RenderPassRecord passWithProducers(
            final long frame,
            final int sequence,
            final String semanticPassId,
            final int producerCount,
            final boolean divergentProducer
    ) {
        List<ProducerRecord> producers = new java.util.ArrayList<>();
        for (int index = 0; index < producerCount; index++) {
            producers.add(new ProducerRecord(
                    index,
                    divergentProducer && index == 3 ? ProducerType.DISPATCH : ProducerType.DRAW,
                    "pipeline",
                    List.of(),
                    Map.of("index", Integer.toString(index)),
                    Map.of(),
                    new ViewportRecord(0, 0, 1, 1),
                    ScissorRecord.disabled(),
                    List.of("color0")
            ));
        }
        return new RenderPassRecord(
                frame, sequence, semanticPassId, PassType.RENDER, List.of(), null, null,
                new ViewportRecord(0, 0, 1, 1), ScissorRecord.disabled(), "pipeline", List.of(),
                producers, Map.of(
                        "producerDetailsCaptured", "true",
                        "producerDetailsComplete", "true",
                        "producerDetailsTruncated", "false",
                        "producerCapturePolicy", "enabled=true,pass=*,range=0:*,maxDetailed=1000000",
                        "producerCount", Integer.toString(producerCount)
                )
        );
    }
}
