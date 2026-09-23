package com.metallum.client.validation.report;

import com.metallum.client.validation.contract.AttachmentBindingRecord;
import com.metallum.client.validation.contract.ProducerRecord;
import com.metallum.client.validation.contract.RenderPassRecord;
import com.metallum.client.validation.contract.ResourceIdentity;
import com.metallum.client.validation.capture.CapturedResource;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compares logical passes, independent of native encoder splitting. */
public final class PassManifestComparator {
    private PassManifestComparator() {
    }

    public static DivergenceReport compare(
            final List<RenderPassRecord> expected,
            final List<RenderPassRecord> actual
    ) {
        return compare(expected, actual, ManifestAlignmentPolicy.strict());
    }

    /**
     * Aligns logical passes by frame, canonical semantic ID, and occurrence.
     * Native sequence numbers are deliberately excluded from identity because
     * encoder splitting and merging is an implementation detail.
     */
    public static DivergenceReport compare(
            final List<RenderPassRecord> expected,
            final List<RenderPassRecord> actual,
            final ManifestAlignmentPolicy policy
    ) {
        Objects.requireNonNull(policy, "policy");
        Map<AlignmentKey, List<RenderPassRecord>> expectedGroups = groups(expected, policy);
        Map<AlignmentKey, List<RenderPassRecord>> actualGroups = groups(actual, policy);
        List<AlignmentKey> keys = new ArrayList<>(expectedGroups.keySet());
        for (AlignmentKey key : actualGroups.keySet()) {
            if (!keys.contains(key)) keys.add(key);
        }
        keys.sort(Comparator.comparingLong(AlignmentKey::frameId)
                .thenComparingInt(key -> firstSequence(expectedGroups, actualGroups, key))
                .thenComparing(AlignmentKey::semanticPassId));
        String lastMatching = "none";
        for (AlignmentKey key : keys) {
            List<RenderPassRecord> referenceGroup = expectedGroups.getOrDefault(key, List.of());
            List<RenderPassRecord> candidateGroup = actualGroups.getOrDefault(key, List.of());
            if (referenceGroup.isEmpty() || candidateGroup.isEmpty()) {
                if (policy.isOptional(key.semanticPassId())) continue;
                RenderPassRecord evidence = candidateGroup.isEmpty()
                        ? referenceGroup.get(0) : candidateGroup.get(0);
                return divergence(lastMatching, evidence,
                        actual == null ? "actual manifest ended early"
                                : candidateGroup.isEmpty()
                                ? "actual manifest is missing a semantic pass"
                                : "actual manifest has an extra semantic pass",
                        Map.of("alignmentKey", key.toString(),
                                "expectedOccurrenceCount", referenceGroup.size(),
                                "actualOccurrenceCount", candidateGroup.size()));
            }
            ManifestAlignmentPolicy.Multiplicity multiplicity = policy.multiplicityFor(key.semanticPassId());
            if (!multiplicity.permits(referenceGroup.size(), candidateGroup.size())) {
                return divergence(lastMatching, candidateGroup.get(0), "semantic pass occurrence count differs",
                        Map.of("alignmentKey", key.toString(),
                                "expectedOccurrenceCount", referenceGroup.size(),
                                "actualOccurrenceCount", candidateGroup.size(),
                                "multiplicityRule", multiplicity.name()));
            }
            int common = Math.min(referenceGroup.size(), candidateGroup.size());
            for (int index = 0; index < common; index++) {
                String reason = difference(referenceGroup.get(index), candidateGroup.get(index), policy);
                if (reason != null) {
                    return divergence(lastMatching, candidateGroup.get(index), reason,
                            Map.of("alignmentKey", key.toString(), "occurrence", index));
                }
                lastMatching = candidateGroup.get(index).semanticPassId();
            }
            if (referenceGroup.size() != candidateGroup.size()) {
                String consistencyFailure = multiplicityConsistencyFailure(
                        referenceGroup, candidateGroup, policy
                );
                if (consistencyFailure != null) {
                    return divergence(lastMatching, candidateGroup.get(0), consistencyFailure,
                            Map.of("alignmentKey", key.toString(),
                                    "expectedOccurrenceCount", referenceGroup.size(),
                                    "actualOccurrenceCount", candidateGroup.size()));
                }
            }
        }
        if (!policy.compareResourceGenerationAbsolutely()) {
            LineageMismatch lineageMismatch = compareGenerationLineage(expected, actual, policy);
            if (lineageMismatch != null) {
                return divergence(
                        lineageMismatch.lastMatchingPass(),
                        lineageMismatch.pass(),
                        "resource generation lineage differs",
                        Map.of(
                                "resourceSemanticName", lineageMismatch.resourceSemanticName(),
                                "expectedLineage", lineageMismatch.expectedLineage(),
                                "actualLineage", lineageMismatch.actualLineage(),
                                "lineageIndex", lineageMismatch.index()
                        )
                );
            }
        }
        return DivergenceReport.success();
    }

    public static DivergenceReport compareProducers(
            final RenderPassRecord expected,
            final RenderPassRecord actual
    ) {
        if (expected == null || actual == null) {
            return new DivergenceReport(false, "none", actual == null ? "none" : actual.semanticPassId(),
                    actual == null ? -1 : actual.frameId(), actual == null ? -1 : actual.sequence(),
                    actual == null ? "none" : actual.semanticPassId(), -1, "none", "pass missing", Map.of());
        }
        boolean expectedDetailsCaptured = producerDetailsCaptured(expected);
        boolean actualDetailsCaptured = producerDetailsCaptured(actual);
        boolean expectedDetailsComplete = producerDetailsComplete(expected);
        boolean actualDetailsComplete = producerDetailsComplete(actual);
        if (!expectedDetailsCaptured || !actualDetailsCaptured) {
            return new DivergenceReport(
                    false,
                    "none",
                    actual.semanticPassId(),
                    actual.frameId(),
                    actual.sequence(),
                    actual.semanticPassId(),
                    -1,
                    "none",
                    "producer comparison unavailable: producer details were not captured",
                    Map.of(
                            "producerComparisonSupported", false,
                            "expectedProducerDetailsCaptured", expectedDetailsCaptured,
                            "actualProducerDetailsCaptured", actualDetailsCaptured,
                            "expectedProducerDetailsComplete", expectedDetailsComplete,
                            "actualProducerDetailsComplete", actualDetailsComplete
                    )
            );
        }
        String expectedPolicy = expected.metadata().get("producerCapturePolicy");
        String actualPolicy = actual.metadata().get("producerCapturePolicy");
        if (!Objects.equals(expectedPolicy, actualPolicy)) {
            return new DivergenceReport(
                    false,
                    "none",
                    actual.semanticPassId(),
                    actual.frameId(),
                    actual.sequence(),
                    actual.semanticPassId(),
                    -1,
                    "none",
                    "producer comparison unavailable: capture ranges differ",
                    Map.of(
                            "producerComparisonSupported", false,
                            "expectedProducerCapturePolicy", String.valueOf(expectedPolicy),
                            "actualProducerCapturePolicy", String.valueOf(actualPolicy)
                    )
            );
        }
        int common = Math.min(expected.producers().size(), actual.producers().size());
        for (int index = 0; index < common; index++) {
            ProducerRecord reference = expected.producers().get(index);
            ProducerRecord candidate = actual.producers().get(index);
            String producerDifference = producerDifference(reference, candidate);
            if (producerDifference != null) {
                Map<String, Object> metrics = new LinkedHashMap<>();
                metrics.put("expectedProducerType", reference.producerType().name());
                metrics.put("actualProducerType", candidate.producerType().name());
                metrics.put("expectedPipelineId", reference.pipelineId());
                metrics.put("actualPipelineId", candidate.pipelineId());
                metrics.put("expectedShaderIds", reference.shaderIds());
                metrics.put("actualShaderIds", candidate.shaderIds());
                metrics.put("expectedParameters", reference.parameters());
                metrics.put("actualParameters", candidate.parameters());
                metrics.put("expectedBoundResources", reference.boundResources());
                metrics.put("actualBoundResources", candidate.boundResources());
                metrics.put("expectedViewport", reference.viewport());
                metrics.put("actualViewport", candidate.viewport());
                metrics.put("expectedScissor", reference.scissor());
                metrics.put("actualScissor", candidate.scissor());
                metrics.put("expectedWrittenAttachments", reference.writtenAttachments());
                metrics.put("actualWrittenAttachments", candidate.writtenAttachments());
                metrics.put("producerComparisonComplete", expectedDetailsComplete && actualDetailsComplete);
                return new DivergenceReport(
                        false,
                        index == 0 ? "none" : Integer.toString(index - 1),
                        actual.semanticPassId(), actual.frameId(), actual.sequence(), actual.semanticPassId(),
                        index,
                        candidate.writtenAttachments().isEmpty() ? "none" : candidate.writtenAttachments().get(0),
                        producerDifference,
                        metrics
                );
            }
        }
        if (expectedDetailsComplete && actualDetailsComplete
                && expected.producers().size() != actual.producers().size()) {
            int index = common;
            return new DivergenceReport(false, Integer.toString(Math.max(0, index - 1)), actual.semanticPassId(),
                    actual.frameId(), actual.sequence(), actual.semanticPassId(), index, "none",
                    "producer count differs", Map.of("expectedProducerCount", expected.producers().size(),
                            "actualProducerCount", actual.producers().size()));
        }
        return DivergenceReport.success();
    }

    private static String producerDifference(
            final ProducerRecord expected,
            final ProducerRecord actual
    ) {
        return producerDifference(expected, actual, true);
    }

    private static String producerDifference(
            final ProducerRecord expected,
            final ProducerRecord actual,
            final boolean comparePipelineAndShaders
    ) {
        if (expected.producerType() != actual.producerType()) return "producer type differs";
        if (comparePipelineAndShaders && !expected.pipelineId().equals(actual.pipelineId())) {
            return "producer pipeline differs";
        }
        if (comparePipelineAndShaders && !expected.shaderIds().equals(actual.shaderIds())) {
            return "producer shader IDs differ";
        }
        if (!expected.parameters().equals(actual.parameters())) return "producer parameters differ";
        if (!expected.boundResources().equals(actual.boundResources())) return "producer bindings differ";
        if (!expected.viewport().equals(actual.viewport())) return "producer viewport differs";
        if (!expected.scissor().equals(actual.scissor())) return "producer scissor differs";
        if (!expected.writtenAttachments().equals(actual.writtenAttachments())) {
            return "producer written attachments differ";
        }
        return null;
    }

    public static DivergenceReport compareCaptures(
            final List<CaptureSnapshot> expected,
            final List<CaptureSnapshot> actual
    ) {
        return compareCaptureEntries(
                expected, List.of(), actual, List.of(), ManifestAlignmentPolicy.strict()
        );
    }

    /**
     * Compares attachment evidence using logical pass identity rather than the
     * native sequence number. When a backend splits or merges encoders, the
     * pass sequence can change while the semantic pass and its occurrence stay
     * stable. The pass lists supply that occurrence information.
     */
    public static DivergenceReport compareCaptures(
            final List<CaptureSnapshot> expected,
            final List<RenderPassRecord> expectedPasses,
            final List<CaptureSnapshot> actual,
            final List<RenderPassRecord> actualPasses
    ) {
        return compareCaptureEntries(
                expected, expectedPasses, actual, actualPasses, ManifestAlignmentPolicy.strict()
        );
    }

    /** Compares attachment evidence using an explicit alignment policy. */
    public static DivergenceReport compareCaptures(
            final List<CaptureSnapshot> expected,
            final List<RenderPassRecord> expectedPasses,
            final List<CaptureSnapshot> actual,
            final List<RenderPassRecord> actualPasses,
            final ManifestAlignmentPolicy policy
    ) {
        return compareCaptureEntries(expected, expectedPasses, actual, actualPasses, policy);
    }

    private static DivergenceReport compareCaptureEntries(
            final List<CaptureSnapshot> expected,
            final List<RenderPassRecord> expectedPasses,
            final List<CaptureSnapshot> actual,
            final List<RenderPassRecord> actualPasses,
            final ManifestAlignmentPolicy policy
    ) {
        List<CaptureEntry> expectedEntries = captureEntries(expected, expectedPasses, policy);
        List<CaptureEntry> actualEntries = captureEntries(actual, actualPasses, policy);
        Map<CaptureKey, List<CaptureEntry>> expectedGroups = captureGroups(expectedEntries);
        Map<CaptureKey, List<CaptureEntry>> actualGroups = captureGroups(actualEntries);
        List<CaptureKey> keys = new ArrayList<>(expectedGroups.keySet());
        for (CaptureKey key : actualGroups.keySet()) {
            if (!keys.contains(key)) keys.add(key);
        }
        keys.sort(Comparator.comparingLong(CaptureKey::frameId)
                .thenComparingInt(CaptureKey::passOccurrence)
                .thenComparing(CaptureKey::semanticPassId)
                .thenComparingInt(CaptureKey::producerIndex)
                .thenComparing(CaptureKey::resource));
        String lastMatching = "none";
        for (CaptureKey key : keys) {
            List<CaptureEntry> referenceGroup = expectedGroups.getOrDefault(key, List.of());
            List<CaptureEntry> candidateGroup = actualGroups.getOrDefault(key, List.of());
            int common = Math.min(referenceGroup.size(), candidateGroup.size());
            for (int index = 0; index < common; index++) {
                CaptureSnapshot reference = referenceGroup.get(index).snapshot();
                CaptureSnapshot candidate = candidateGroup.get(index).snapshot();
                String shapeDifference = shapeDifference(reference.value(), candidate.value(), policy);
                if (shapeDifference != null) {
                    return captureDivergence(lastMatching, candidate, shapeDifference, Map.of(
                            "captureKey", key.toString(),
                            "expectedSequence", reference.sequence(),
                            "actualSequence", candidate.sequence()
                    ));
                }
                Map<String, Object> metrics = byteDifference(reference.value(), candidate.value());
                if (((Number) metrics.get("mismatchBytes")).intValue() != 0) {
                    Map<String, Object> evidence = new LinkedHashMap<>(metrics);
                    evidence.put("captureKey", key.toString());
                    evidence.put("expectedSequence", reference.sequence());
                    evidence.put("actualSequence", candidate.sequence());
                    return captureDivergence(lastMatching, candidate, "captured attachment differs", evidence);
                }
                lastMatching = candidate.semanticPassId();
            }
            if (referenceGroup.size() != candidateGroup.size()) {
                CaptureSnapshot missing = candidateGroup.size() > common
                        ? candidateGroup.get(common).snapshot()
                        : referenceGroup.size() > common ? referenceGroup.get(common).snapshot() : null;
                if (missing != null) {
                    return captureDivergence(lastMatching, missing,
                            referenceGroup.size() > candidateGroup.size()
                                    ? "actual capture stream ended early"
                                    : "actual capture stream has an extra sample",
                            Map.of("captureKey", key.toString(), "captureIndex", common));
                }
            }
        }
        return DivergenceReport.success();
    }

    private static String difference(
            final RenderPassRecord expected,
            final RenderPassRecord actual,
            final ManifestAlignmentPolicy policy
    ) {
        if (expected.frameId() != actual.frameId()) return "frame differs";
        if (!policy.canonicalSemanticPassId(expected.semanticPassId())
                .equals(policy.canonicalSemanticPassId(actual.semanticPassId()))) {
            return "semantic pass differs";
        }
        if (expected.type() != actual.type()) return "pass type differs";
        if (!attachmentsMatch(expected.colorAttachments(), actual.colorAttachments(), policy)) return "color attachment contract differs";
        if (!attachmentMatch(expected.depthAttachment(), actual.depthAttachment(), policy)) return "depth attachment contract differs";
        if (!attachmentMatch(expected.stencilAttachment(), actual.stencilAttachment(), policy)) return "stencil attachment contract differs";
        if (!expected.viewport().equals(actual.viewport())) return "viewport differs";
        if (!expected.scissor().equals(actual.scissor())) return "scissor differs";
        if (policy.comparePipelineAndShaders() && !expected.pipelineId().equals(actual.pipelineId())) {
            return "pipeline ID differs";
        }
        if (policy.comparePipelineAndShaders() && !expected.shaderIds().equals(actual.shaderIds())) {
            return "shader IDs differ";
        }
        boolean expectedDetailsCaptured = producerDetailsCaptured(expected);
        boolean actualDetailsCaptured = producerDetailsCaptured(actual);
        if (expectedDetailsCaptured != actualDetailsCaptured) {
            return "producer detail capture availability differs";
        }
        if (producerDetailsComplete(expected) != producerDetailsComplete(actual)) {
            return "producer detail completeness differs";
        }
        if (expectedDetailsCaptured && expected.producers().size() != actual.producers().size()) {
            return "producer count differs";
        }
        if (expectedDetailsCaptured && actualDetailsCaptured
                && producerDetailsComplete(expected) && producerDetailsComplete(actual)) {
            int producerCount = Math.min(expected.producers().size(), actual.producers().size());
            for (int index = 0; index < producerCount; index++) {
                String producerDifference = producerDifference(
                        expected.producers().get(index), actual.producers().get(index),
                        policy.comparePipelineAndShaders()
                );
                if (producerDifference != null) return producerDifference;
            }
        }
        String producerCountDifference = metadataDifference(expected, actual, "producerCount");
        if (producerCountDifference != null) return producerCountDifference;
        String producerTypeDifference = metadataDifference(expected, actual, "producerTypeCounts");
        if (producerTypeDifference != null) return producerTypeDifference;
        return null;
    }

    private static boolean producerDetailsCaptured(final RenderPassRecord pass) {
        String declared = pass.metadata().get("producerDetailsCaptured");
        if (declared != null) {
            return Boolean.parseBoolean(declared);
        }
        // Older programmatic records may omit the schema field. A non-empty
        // list is detailed evidence; an empty list remains non-comparable.
        return !pass.producers().isEmpty();
    }

    private static boolean producerDetailsComplete(final RenderPassRecord pass) {
        String declared = pass.metadata().get("producerDetailsComplete");
        return declared == null ? producerDetailsCaptured(pass) : Boolean.parseBoolean(declared);
    }

    private static String metadataDifference(
            final RenderPassRecord expected,
            final RenderPassRecord actual,
            final String key
    ) {
        String expectedValue = expected.metadata().get(key);
        String actualValue = actual.metadata().get(key);
        if (expectedValue == null && actualValue == null) return null;
        if (expectedValue == null || actualValue == null) return key + " availability differs";
        return expectedValue.equals(actualValue) ? null : key + " differs";
    }

    private static boolean attachmentsMatch(
            final List<AttachmentBindingRecord> expected,
            final List<AttachmentBindingRecord> actual,
            final ManifestAlignmentPolicy policy
    ) {
        if (expected.size() != actual.size()) return false;
        for (int index = 0; index < expected.size(); index++) {
            if (!attachmentMatch(expected.get(index), actual.get(index), policy)) return false;
        }
        return true;
    }

    private static boolean attachmentMatch(
            final AttachmentBindingRecord expected,
            final AttachmentBindingRecord actual,
            final ManifestAlignmentPolicy policy
    ) {
        if (expected == actual) return true;
        if (expected == null || actual == null) return false;
        var a = expected.resource();
        var b = actual.resource();
        return expected.slot() == actual.slot()
                && expected.semantic() == actual.semantic()
                && expected.writable() == actual.writable()
                && a.semanticName().equals(b.semanticName())
                && (policy.compareResourceGenerationAbsolutely()
                ? a.generation() == b.generation() : true)
                && a.format().equals(b.format())
                && a.width() == b.width()
                && a.height() == b.height()
                && a.depthOrLayers() == b.depthOrLayers()
                && a.mipLevel() == b.mipLevel()
                && a.sampleCount() == b.sampleCount()
                && a.usage() == b.usage()
                && expected.loadAction().equals(actual.loadAction())
                && expected.storeAction().equals(actual.storeAction());
    }

    /**
     * Compares allocation lineage for a cross-backend run. Absolute generation
     * values are intentionally ignored, but the sequence of resource
     * reallocations for each logical attachment must remain the same. Adjacent
     * duplicate generations are collapsed so an explicitly permitted encoder
     * split does not manufacture a false transition.
     */
    private static LineageMismatch compareGenerationLineage(
            final List<RenderPassRecord> expected,
            final List<RenderPassRecord> actual,
            final ManifestAlignmentPolicy policy
    ) {
        Map<LineageKey, List<GenerationSample>> expectedStreams = generationStreams(expected, policy);
        Map<LineageKey, List<GenerationSample>> actualStreams = generationStreams(actual, policy);
        List<LineageKey> keys = new ArrayList<>(expectedStreams.keySet());
        for (LineageKey key : actualStreams.keySet()) {
            if (!keys.contains(key)) keys.add(key);
        }
        keys.sort(Comparator.comparing(LineageKey::semanticPassId)
                .thenComparing(LineageKey::role)
                .thenComparingInt(LineageKey::slot)
                .thenComparing(LineageKey::resourceSemanticName));
        for (LineageKey key : keys) {
            List<GenerationSample> expectedSamples = expectedStreams.getOrDefault(key, List.of());
            List<GenerationSample> actualSamples = actualStreams.getOrDefault(key, List.of());
            List<Integer> expectedLineage = normalizedLineage(expectedSamples);
            List<Integer> actualLineage = normalizedLineage(actualSamples);
            int common = Math.min(expectedLineage.size(), actualLineage.size());
            for (int index = 0; index < common; index++) {
                if (!expectedLineage.get(index).equals(actualLineage.get(index))) {
                    GenerationSample candidate = actualSamples.get(Math.min(index, actualSamples.size() - 1));
                    GenerationSample reference = expectedSamples.get(Math.min(index, expectedSamples.size() - 1));
                    return new LineageMismatch(
                            index == 0 ? "none" : previousPass(actualSamples, index),
                            candidate == null ? reference.pass() : candidate.pass(),
                            key.resourceSemanticName(), expectedLineage.toString(), actualLineage.toString(), index
                    );
                }
            }
            if (expectedLineage.size() != actualLineage.size()) {
                int index = common;
                GenerationSample candidate = index < actualSamples.size() ? actualSamples.get(index) : null;
                GenerationSample reference = index < expectedSamples.size() ? expectedSamples.get(index) : null;
                RenderPassRecord pass = candidate == null ? reference.pass() : candidate.pass();
                return new LineageMismatch(
                        index == 0 ? "none" : previousPass(candidate == null ? expectedSamples : actualSamples, index),
                        pass,
                        key.resourceSemanticName(), expectedLineage.toString(), actualLineage.toString(), index
                );
            }
        }
        return null;
    }

    private static String previousPass(final List<GenerationSample> samples, final int index) {
        if (samples == null || samples.isEmpty()) return "none";
        return samples.get(Math.min(index - 1, samples.size() - 1)).pass().semanticPassId();
    }

    private static List<Integer> normalizedLineage(final List<GenerationSample> samples) {
        Map<Long, Integer> ordinals = new LinkedHashMap<>();
        List<Integer> result = new ArrayList<>();
        Integer previous = null;
        for (GenerationSample sample : samples) {
            int ordinal = ordinals.computeIfAbsent(sample.generation(), ignored -> ordinals.size());
            if (!Integer.valueOf(ordinal).equals(previous)) {
                result.add(ordinal);
                previous = ordinal;
            }
        }
        return result;
    }

    private static Map<LineageKey, List<GenerationSample>> generationStreams(
            final List<RenderPassRecord> passes,
            final ManifestAlignmentPolicy policy
    ) {
        Map<LineageKey, List<GenerationSample>> result = new LinkedHashMap<>();
        if (passes == null) return result;
        List<RenderPassRecord> ordered = new ArrayList<>(passes);
        ordered.sort(Comparator.comparingLong(RenderPassRecord::frameId)
                .thenComparingInt(RenderPassRecord::sequence));
        for (RenderPassRecord pass : ordered) {
            if (policy.isBackendPrivate(pass)) continue;
            for (AttachmentBindingRecord attachment : pass.colorAttachments()) {
                addGenerationSample(
                        result, pass, policy.canonicalSemanticPassId(pass.semanticPassId()),
                        "color", attachment.slot(), attachment.resource()
                );
            }
            if (pass.depthAttachment() != null) {
                addGenerationSample(
                        result, pass, policy.canonicalSemanticPassId(pass.semanticPassId()),
                        "depth", -1, pass.depthAttachment().resource()
                );
            }
            if (pass.stencilAttachment() != null) {
                addGenerationSample(
                        result, pass, policy.canonicalSemanticPassId(pass.semanticPassId()),
                        "stencil", -1, pass.stencilAttachment().resource()
                );
            }
        }
        return result;
    }

    private static void addGenerationSample(
            final Map<LineageKey, List<GenerationSample>> streams,
            final RenderPassRecord pass,
            final String canonicalSemanticPassId,
            final String role,
            final int slot,
            final ResourceIdentity resource
    ) {
        LineageKey key = new LineageKey(
                canonicalSemanticPassId, role, slot, resource.semanticName()
        );
        streams.computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(new GenerationSample(resource.generation(), pass));
    }

    private static DivergenceReport divergence(
            final String lastMatching,
            final RenderPassRecord pass,
            final String reason,
            final Map<String, Object> metrics
    ) {
        String resource = pass.colorAttachments().isEmpty()
                ? pass.depthAttachment() == null ? "none" : pass.depthAttachment().resource().stableKey()
                : pass.colorAttachments().get(0).resource().stableKey();
        return new DivergenceReport(false, lastMatching, pass.semanticPassId(), pass.frameId(), pass.sequence(),
                pass.semanticPassId(), -1, resource, reason, metrics);
    }

    private static Map<AlignmentKey, List<RenderPassRecord>> groups(
            final List<RenderPassRecord> passes,
            final ManifestAlignmentPolicy policy
    ) {
        Map<AlignmentKey, List<RenderPassRecord>> result = new LinkedHashMap<>();
        if (passes == null) return result;
        for (RenderPassRecord pass : passes) {
            if (policy.isBackendPrivate(pass)) continue;
            AlignmentKey key = new AlignmentKey(
                    pass.frameId(), policy.canonicalSemanticPassId(pass.semanticPassId())
            );
            result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(pass);
        }
        return result;
    }

    private static int firstSequence(
            final Map<AlignmentKey, List<RenderPassRecord>> expected,
            final Map<AlignmentKey, List<RenderPassRecord>> actual,
            final AlignmentKey key
    ) {
        List<RenderPassRecord> candidate = expected.getOrDefault(key, actual.getOrDefault(key, List.of()));
        return candidate.isEmpty() ? Integer.MAX_VALUE : candidate.get(0).sequence();
    }

    private static String multiplicityConsistencyFailure(
            final List<RenderPassRecord> expected,
            final List<RenderPassRecord> actual,
            final ManifestAlignmentPolicy policy
    ) {
        RenderPassRecord expectedRepresentative = expected.get(0);
        RenderPassRecord actualRepresentative = actual.get(0);
        for (RenderPassRecord candidate : actual) {
            if (difference(actualRepresentative, candidate, policy) != null) {
                return "declared split/fold contains inconsistent actual pass contracts";
            }
        }
        for (RenderPassRecord reference : expected) {
            if (difference(expectedRepresentative, reference, policy) != null) {
                return "declared split/fold contains inconsistent reference pass contracts";
            }
        }
        return difference(expectedRepresentative, actualRepresentative, policy);
    }

    private record AlignmentKey(long frameId, String semanticPassId) {
        @Override
        public String toString() {
            return frameId + ":" + semanticPassId;
        }
    }

    private static boolean sameLocation(final CaptureSnapshot expected, final CaptureSnapshot actual) {
        return expected.frameId() == actual.frameId()
                && expected.sequence() == actual.sequence()
                && expected.semanticPassId().equals(actual.semanticPassId())
                && expected.producerIndex() == actual.producerIndex()
                && expected.resource().equals(actual.resource());
    }

    private static String shapeDifference(
            final CapturedResource expected,
            final CapturedResource actual,
            final ManifestAlignmentPolicy policy
    ) {
        var expectedIdentity = expected.resource();
        var actualIdentity = actual.resource();
        if (!expectedIdentity.semanticName().equals(actualIdentity.semanticName())
                || (policy.compareResourceGenerationAbsolutely()
                && expectedIdentity.generation() != actualIdentity.generation())) {
            return "captured attachment resource generation differs";
        }
        if (!expectedIdentity.format().equals(actualIdentity.format())
                || expectedIdentity.depthOrLayers() != actualIdentity.depthOrLayers()
                || expectedIdentity.mipLevel() != actualIdentity.mipLevel()
                || expectedIdentity.sampleCount() != actualIdentity.sampleCount()
                || expectedIdentity.usage() != actualIdentity.usage()) {
            return "captured attachment resource contract differs";
        }
        if (expected.width() != actual.width() || expected.height() != actual.height()) {
            return "captured attachment dimensions differ";
        }
        if (!expected.captureFormat().equals(actual.captureFormat())) {
            return "captured attachment format differs";
        }
        return expected.bytes().length == actual.bytes().length ? null : "captured attachment byte count differs";
    }

    private static Map<String, Object> byteDifference(
            final CapturedResource expected,
            final CapturedResource actual
    ) {
        byte[] reference = expected.bytes();
        byte[] candidate = actual.bytes();
        int mismatch = 0;
        int maxError = 0;
        long sum = 0L;
        int[] errors = new int[reference.length];
        for (int index = 0; index < reference.length; index++) {
            int error = Math.abs((candidate[index] & 0xff) - (reference[index] & 0xff));
            errors[index] = error;
            if (error != 0) mismatch++;
            maxError = Math.max(maxError, error);
            sum += error;
        }
        java.util.Arrays.sort(errors);
        double mean = errors.length == 0 ? 0.0 : (double) sum / errors.length;
        double p95 = errors.length == 0 ? 0.0 : errors[Math.min(errors.length - 1,
                Math.max(0, (int) Math.ceil(errors.length * 0.95) - 1))];
        return Map.of(
                "mismatchBytes", mismatch,
                "maxError", maxError,
                "meanAbsoluteByteError", mean,
                "p95AbsoluteByteError", p95
        );
    }

    private static DivergenceReport captureDivergence(
            final String lastMatching,
            final CaptureSnapshot candidate,
            final String reason,
            final Map<String, Object> metrics
    ) {
        return new DivergenceReport(
                false,
                lastMatching,
                candidate.semanticPassId(),
                candidate.frameId(),
                candidate.sequence(),
                candidate.semanticPassId(),
                candidate.producerIndex(),
                candidate.resource(),
                reason,
                metrics
        );
    }

    private static List<CaptureEntry> captureEntries(
            final List<CaptureSnapshot> snapshots,
            final List<RenderPassRecord> passes,
            final ManifestAlignmentPolicy policy
    ) {
        if (snapshots == null || snapshots.isEmpty()) return List.of();
        Map<PassLocation, Integer> occurrenceByLocation = passOccurrences(passes);
        Map<FrameSemantic, Integer> fallbackOccurrences = new LinkedHashMap<>();
        List<CaptureEntry> entries = new ArrayList<>(snapshots.size());
        for (CaptureSnapshot snapshot : snapshots) {
            PassLocation location = new PassLocation(
                    snapshot.frameId(), snapshot.sequence(), snapshot.semanticPassId()
            );
            Integer occurrence = occurrenceByLocation.get(location);
            if (occurrence == null) {
                FrameSemantic semantic = new FrameSemantic(snapshot.frameId(), snapshot.semanticPassId());
                // Direct callers that do not provide a pass manifest retain the
                // historical sequence-based ordering. A real manifest uses the
                // semantic occurrence, which remains stable across encoder
                // splitting and merging.
                occurrence = passes == null || passes.isEmpty()
                        ? snapshot.sequence()
                        : fallbackOccurrences.merge(semantic, 1, Integer::sum) - 1;
            }
            entries.add(new CaptureEntry(
                    snapshot,
                    new CaptureKey(
                            snapshot.frameId(), occurrence, snapshot.semanticPassId(),
                            snapshot.producerIndex(), captureResourceKey(snapshot, policy)
                    )
            ));
        }
        return entries;
    }

    private static String captureResourceKey(
            final CaptureSnapshot snapshot,
            final ManifestAlignmentPolicy policy
    ) {
        if (policy.compareResourceGenerationAbsolutely()) {
            return snapshot.resource();
        }
        return snapshot.value().semanticName();
    }

    private static Map<CaptureKey, List<CaptureEntry>> captureGroups(
            final List<CaptureEntry> entries
    ) {
        Map<CaptureKey, List<CaptureEntry>> groups = new LinkedHashMap<>();
        for (CaptureEntry entry : entries) {
            groups.computeIfAbsent(entry.key(), ignored -> new ArrayList<>()).add(entry);
        }
        return groups;
    }

    private static Map<PassLocation, Integer> passOccurrences(
            final List<RenderPassRecord> passes
    ) {
        Map<PassLocation, Integer> result = new LinkedHashMap<>();
        if (passes == null || passes.isEmpty()) return result;
        List<RenderPassRecord> ordered = new ArrayList<>(passes);
        ordered.sort(Comparator.comparingLong(RenderPassRecord::frameId)
                .thenComparingInt(RenderPassRecord::sequence));
        Map<FrameSemantic, Integer> nextOccurrence = new LinkedHashMap<>();
        for (RenderPassRecord pass : ordered) {
            FrameSemantic semantic = new FrameSemantic(pass.frameId(), pass.semanticPassId());
            int occurrence = nextOccurrence.getOrDefault(semantic, 0);
            nextOccurrence.put(semantic, occurrence + 1);
            result.put(new PassLocation(pass.frameId(), pass.sequence(), pass.semanticPassId()), occurrence);
        }
        return result;
    }

    private record CaptureEntry(CaptureSnapshot snapshot, CaptureKey key) {
    }

    private record CaptureKey(
            long frameId,
            int passOccurrence,
            String semanticPassId,
            int producerIndex,
            String resource
    ) {
        @Override
        public String toString() {
            return frameId + ":" + semanticPassId + "#" + passOccurrence
                    + ":producer=" + producerIndex + ":resource=" + resource;
        }
    }

    private record PassLocation(long frameId, int sequence, String semanticPassId) {
    }

    private record FrameSemantic(long frameId, String semanticPassId) {
    }

    private record LineageKey(
            String semanticPassId,
            String role,
            int slot,
            String resourceSemanticName
    ) {
    }

    private record GenerationSample(long generation, RenderPassRecord pass) {
    }

    private record LineageMismatch(
            String lastMatchingPass,
            RenderPassRecord pass,
            String resourceSemanticName,
            String expectedLineage,
            String actualLineage,
            int index
    ) {
    }
}
