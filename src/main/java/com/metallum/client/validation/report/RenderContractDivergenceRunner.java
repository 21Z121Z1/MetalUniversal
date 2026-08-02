package com.metallum.client.validation.report;

import com.metallum.client.validation.contract.CapturePointKind;
import com.metallum.client.validation.contract.ProducerRecord;
import com.metallum.client.validation.contract.RenderPassRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Replay-driven first-divergence localization.
 *
 * <p>The runner intentionally knows nothing about Minecraft or Metal. A
 * caller supplies a {@link ReplayRunner} that starts the fixed scene and
 * applies a {@link CapturePlan}. This class then uses prefix queries to find
 * the first logical pass and, when producer evidence is complete, the first
 * producer that no longer matches the reference.</p>
 */
public final class RenderContractDivergenceRunner {
    private RenderContractDivergenceRunner() {
    }

    public interface ReplayRunner {
        RunEvidence replay(CapturePlan plan) throws Exception;
    }

    public record CapturePlan(
            long frameStartInclusive,
            long frameEndInclusive,
            int passStartInclusive,
            int passEndInclusive,
            String semanticPassId,
            CapturePointKind capturePointKind,
            int producerStartInclusive,
            int producerEndInclusive
    ) {
        public CapturePlan {
            if (frameStartInclusive < 0L || frameEndInclusive < frameStartInclusive
                    || passStartInclusive < 0 || passEndInclusive < passStartInclusive
                    || producerStartInclusive < -1 || producerEndInclusive < -1
                    || producerEndInclusive < producerStartInclusive) {
                throw new IllegalArgumentException("Invalid render-contract capture plan");
            }
            semanticPassId = semanticPassId == null ? "" : semanticPassId;
            capturePointKind = Objects.requireNonNull(capturePointKind, "capturePointKind");
        }

        public static CapturePlan full(final long frameStart, final long frameEnd) {
            return new CapturePlan(
                    frameStart, frameEnd, 0, Integer.MAX_VALUE, "",
                    CapturePointKind.AFTER_PASS, -1, -1
            );
        }

        public CapturePlan withPassRange(final int start, final int end) {
            return new CapturePlan(
                    frameStartInclusive, frameEndInclusive, start, end, semanticPassId,
                    capturePointKind, producerStartInclusive, producerEndInclusive
            );
        }

        /**
         * Replays the complete temporal prefix up to a pass in the endpoint
         * frame. Pass bounds are local to {@code frameEndInclusive}; earlier
         * frames are replayed in full so history-dependent passes remain valid.
         */
        public CapturePlan withPrefixEndpoint(final PassKey pass) {
            Objects.requireNonNull(pass, "pass");
            if (pass.frameId() < frameStartInclusive || pass.frameId() > frameEndInclusive) {
                throw new IllegalArgumentException("Pass is outside the capture plan frame range");
            }
            return new CapturePlan(
                    frameStartInclusive, pass.frameId(), 0, pass.sequence(), "",
                    CapturePointKind.AFTER_PASS, -1, -1
            );
        }

        /**
         * Selects one pass in the endpoint frame while retaining the temporal
         * prefix from this plan. The range is deliberately the frame-local
         * sequence here; {@link #withPassRange(int, int)} is still available
         * for adapters that interpret a plan as a global ordered range.
         */
        public CapturePlan forPass(final PassKey pass) {
            Objects.requireNonNull(pass, "pass");
            if (pass.frameId() < frameStartInclusive || pass.frameId() > frameEndInclusive) {
                throw new IllegalArgumentException("Pass is outside the capture plan frame range");
            }
            return new CapturePlan(
                    frameStartInclusive, pass.frameId(), pass.sequence(), pass.sequence(),
                    pass.semanticPassId(), CapturePointKind.AFTER_PRODUCER,
                    producerStartInclusive, producerEndInclusive
            );
        }

        public CapturePlan withProducerRange(final int start, final int end) {
            return new CapturePlan(
                    frameStartInclusive, frameEndInclusive, passStartInclusive, passEndInclusive,
                    semanticPassId, CapturePointKind.AFTER_PRODUCER, start, end
            );
        }
    }

    public record RunEvidence(
            List<RenderPassRecord> passes,
            List<CaptureSnapshot> captures,
            String status,
            Map<String, Object> metadata
    ) {
        public RunEvidence {
            passes = List.copyOf(passes == null ? List.of() : passes);
            captures = List.copyOf(captures == null ? List.of() : captures);
            status = status == null || status.isBlank() ? "unknown" : status;
            metadata = Map.copyOf(new LinkedHashMap<>(metadata == null ? Map.of() : metadata));
        }

        public boolean completed() {
            return "passed".equals(status) || "ready".equals(status)
                    || "failed".equals(status) || "incomplete".equals(status);
        }

        /**
         * A replay result is usable for localization only when the caller has
         * explicitly proved that its selected prefix finished. A pass list by
         * itself is not proof: a crashed or budget-truncated replay can contain
         * a prefix that happens to compare equal.
         */
        public boolean evidenceComplete() {
            Object declared = metadata.get("evidenceComplete");
            if (declared instanceof Boolean booleanValue) {
                return booleanValue;
            }
            if (declared instanceof String stringValue) {
                return Boolean.parseBoolean(stringValue);
            }
            return "passed".equals(status) && Boolean.TRUE.equals(metadata.get("manifestComplete"))
                    && Boolean.TRUE.equals(metadata.get("capturesComplete"));
        }

        public String incompleteReason() {
            Object reason = metadata.get("incompleteReason");
            return reason == null ? "replay evidence did not declare evidenceComplete=true" : reason.toString();
        }
    }

    public record PassKey(long frameId, int globalIndex, String semanticPassId, int sequence) {
        public PassKey {
            if (frameId < 0L || globalIndex < 0 || sequence < 0
                    || semanticPassId == null || semanticPassId.isBlank()) {
                throw new IllegalArgumentException("Invalid pass key");
            }
        }
    }

    public record LocalizationResult(
            boolean matched,
            String status,
            DivergenceReport finalComparison,
            PassKey firstDivergentPass,
            int firstDivergentProducer,
            List<CapturePlan> replayPlans,
            Map<String, Object> evidence
    ) {
        public LocalizationResult {
            status = status == null || status.isBlank() ? "unknown" : status;
            replayPlans = List.copyOf(replayPlans == null ? List.of() : replayPlans);
            evidence = Map.copyOf(new LinkedHashMap<>(evidence == null ? Map.of() : evidence));
        }
    }

    public static LocalizationResult locate(
            final ReplayRunner runner,
            final RunEvidence reference,
            final RunEvidence initialActual,
            final CapturePlan basePlan
    ) throws Exception {
        return locate(
                runner, reference, initialActual, basePlan, ManifestAlignmentPolicy.strict()
        );
    }

    public static LocalizationResult locate(
            final ReplayRunner runner,
            final RunEvidence reference,
            final RunEvidence initialActual,
            final CapturePlan basePlan,
            final ManifestAlignmentPolicy alignmentPolicy
    ) throws Exception {
        Objects.requireNonNull(runner, "runner");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(initialActual, "initialActual");
        Objects.requireNonNull(basePlan, "basePlan");
        Objects.requireNonNull(alignmentPolicy, "alignmentPolicy");
        List<CapturePlan> plans = new ArrayList<>();
        if (!reference.evidenceComplete() || !initialActual.evidenceComplete()) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("referenceEvidenceComplete", reference.evidenceComplete());
            evidence.put("actualEvidenceComplete", initialActual.evidenceComplete());
            evidence.put("referenceIncompleteReason", reference.incompleteReason());
            evidence.put("actualIncompleteReason", initialActual.incompleteReason());
            return new LocalizationResult(
                    false,
                    "incomplete-evidence",
                    compareEvidence(reference, initialActual, alignmentPolicy),
                    null,
                    -1,
                    plans,
                    evidence
            );
        }
        DivergenceReport finalComparison = compareEvidence(reference, initialActual, alignmentPolicy);
        if (finalComparison.matched()) {
            return new LocalizationResult(true, "matched", finalComparison, null, -1, plans, Map.of());
        }
        List<PassKey> referencePasses = orderedPassKeys(reference.passes());
        if (referencePasses.isEmpty()) {
            return unsupported(finalComparison, plans, "reference manifest has no logical passes");
        }

        PassSearchResult passSearch = firstBadPass(
                runner, reference, referencePasses, basePlan, alignmentPolicy, plans
        );
        if (!passSearch.complete()) {
            return new LocalizationResult(
                    false,
                    "pass-localization-incomplete",
                    finalComparison,
                    null,
                    -1,
                    plans,
                    Map.of(
                            "reason", passSearch.reason(),
                            "evidenceComplete", false,
                            "search", "binary-prefix"
                    )
            );
        }
        int firstBad = passSearch.index();
        PassKey divergentPass = referencePasses.get(firstBad);
        RunEvidence actualPassEvidence = replayAndRemember(runner, basePlan.forPass(divergentPass), plans);
        if (!actualPassEvidence.evidenceComplete()) {
            return new LocalizationResult(false, "pass-localization-incomplete", finalComparison,
                    divergentPass, -1, plans, Map.of(
                            "reason", actualPassEvidence.incompleteReason(),
                            "evidenceComplete", false
                    ));
        }
        RenderPassRecord expectedPass = passAt(reference.passes(), divergentPass);
        RenderPassRecord actualPass = findPass(actualPassEvidence.passes(), divergentPass);
        if (expectedPass == null || actualPass == null) {
            return new LocalizationResult(false, "pass-localization-incomplete", finalComparison,
                    divergentPass, -1, plans, Map.of("reason", "selected pass was not present in replay evidence"));
        }

        ProducerSearchResult producerSearch = locateProducer(
                runner, reference, expectedPass, actualPass, actualPassEvidence,
                divergentPass, basePlan, alignmentPolicy, plans
        );
        if (!producerSearch.complete()) {
            return new LocalizationResult(
                    false,
                    "producer-localization-incomplete",
                    finalComparison,
                    divergentPass,
                    -1,
                    plans,
                    Map.of(
                            "reason", producerSearch.reason(),
                            "evidenceComplete", false,
                            "search", "binary-prefix"
                    )
            );
        }
        int firstProducer = producerSearch.index();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("passSearch", "binary-prefix");
        evidence.put("producerSearch", firstProducer >= 0 ? "binary-prefix" : "not-available");
        evidence.put("referencePassCount", referencePasses.size());
        evidence.put("replayCount", plans.size());
        return new LocalizationResult(
                false,
                firstProducer >= 0 ? "localized-pass-and-producer" : "localized-pass-only",
                finalComparison,
                divergentPass,
                firstProducer,
                plans,
                evidence
        );
    }

    private static PassSearchResult firstBadPass(
            final ReplayRunner runner,
            final RunEvidence reference,
            final List<PassKey> passKeys,
            final CapturePlan basePlan,
            final ManifestAlignmentPolicy alignmentPolicy,
            final List<CapturePlan> plans
    ) throws Exception {
        int low = 0;
        int high = passKeys.size() - 1;
        while (low < high) {
            int middle = low + (high - low) / 2;
            CapturePlan plan = basePlan.withPrefixEndpoint(passKeys.get(middle));
            RunEvidence actual = replayAndRemember(runner, plan, plans);
            if (!actual.evidenceComplete()) {
                return PassSearchResult.incomplete(actual.incompleteReason());
            }
            if (passAt(actual.passes(), passKeys.get(middle)) == null) {
                return PassSearchResult.incomplete("prefix replay did not produce the requested endpoint pass");
            }
            if (matchesPrefix(reference, actual, passKeys, middle, alignmentPolicy)) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return PassSearchResult.complete(low);
    }

    private static ProducerSearchResult locateProducer(
            final ReplayRunner runner,
            final RunEvidence reference,
            final RenderPassRecord expectedPass,
            final RenderPassRecord initialActualPass,
            final RunEvidence initialActualEvidence,
            final PassKey pass,
            final CapturePlan basePlan,
            final ManifestAlignmentPolicy alignmentPolicy,
            final List<CapturePlan> plans
    ) throws Exception {
        if (!producerEvidenceComplete(expectedPass) || !producerEvidenceComplete(initialActualPass)) {
            return ProducerSearchResult.unavailable("producer details were not captured completely");
        }
        boolean producerManifestDiffers = !producerManifestMatches(
                expectedPass, initialActualPass, alignmentPolicy
        );
        boolean producerCaptureEvidence = hasProducerCaptureEvidence(
                reference.captures(), initialActualEvidence.captures(), pass
        );
        // AFTER_PASS data can prove that the pass output is wrong, but it
        // cannot identify which producer wrote it. Do not manufacture a
        // producer index from an otherwise identical producer manifest.
        if (!producerManifestDiffers && !producerCaptureEvidence) {
            return ProducerSearchResult.unavailable("producer attachment evidence was not captured");
        }
        int producerCount = Math.min(expectedPass.producers().size(), initialActualPass.producers().size());
        if (producerCount == 0) return ProducerSearchResult.unavailable("divergent pass has no producer records");
        int low = 0;
        int high = producerCount - 1;
        boolean requireCaptureEvidence = producerCaptureEvidence;
        while (low < high) {
            int middle = low + (high - low) / 2;
            CapturePlan plan = basePlan.forPass(pass).withProducerRange(0, middle);
            RunEvidence actual = replayAndRemember(runner, plan, plans);
            if (!actual.evidenceComplete()) {
                return ProducerSearchResult.incomplete(actual.incompleteReason());
            }
            RenderPassRecord actualPass = findPass(actual.passes(), pass);
            if (actualPass == null) {
                return ProducerSearchResult.incomplete("producer replay did not produce the requested pass");
            }
            ProducerPrefixResult prefix = producerPrefixMatches(
                    expectedPass, actualPass, reference, actual, pass, middle + 1,
                    alignmentPolicy, requireCaptureEvidence
            );
            if (!prefix.complete()) {
                return ProducerSearchResult.incomplete(prefix.reason());
            }
            if (prefix.matched()) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return ProducerSearchResult.complete(low);
    }

    private static boolean matchesPrefix(
            final RunEvidence reference,
            final RunEvidence actual,
            final List<PassKey> keys,
            final int lastIndex,
            final ManifestAlignmentPolicy alignmentPolicy
    ) {
        List<RenderPassRecord> expectedPasses = new ArrayList<>();
        for (int index = 0; index <= lastIndex; index++) {
            RenderPassRecord pass = passAt(reference.passes(), keys.get(index));
            if (pass != null) expectedPasses.add(pass);
        }
        if (!actual.evidenceComplete()) return false;
        DivergenceReport manifest = PassManifestComparator.compare(expectedPasses, actual.passes(), alignmentPolicy);
        if (!manifest.matched()) return false;
        List<CaptureSnapshot> expectedCaptures = capturesForPassPrefix(
                reference.captures(), reference.passes(), lastIndex + 1
        );
        List<CaptureSnapshot> actualCaptures = capturesForPassPrefix(
                actual.captures(), actual.passes(), lastIndex + 1
        );
        if (expectedCaptures.isEmpty() && actualCaptures.isEmpty()) return true;
        return PassManifestComparator.compareCaptures(
                expectedCaptures, expectedPasses, actualCaptures, actual.passes(), alignmentPolicy
        ).matched();
    }

    private static ProducerPrefixResult producerPrefixMatches(
            final RenderPassRecord expected,
            final RenderPassRecord actual,
            final RunEvidence referenceEvidence,
            final RunEvidence actualEvidence,
            final PassKey pass,
            final int count,
            final ManifestAlignmentPolicy alignmentPolicy,
            final boolean requireCaptureEvidence
    ) {
        if (actual.producers().size() < count || expected.producers().size() < count) {
            return ProducerPrefixResult.mismatch();
        }
        for (int index = 0; index < count; index++) {
            ProducerRecord reference = expected.producers().get(index);
            ProducerRecord candidate = actual.producers().get(index);
            if (!producerMatches(reference, candidate, alignmentPolicy)) {
                return ProducerPrefixResult.mismatch();
            }
        }
        List<CaptureSnapshot> expectedCaptures = capturesForProducerPrefix(
                referenceEvidence.captures(), pass, count
        );
        List<CaptureSnapshot> actualCaptures = capturesForProducerPrefix(
                actualEvidence.captures(), pass, count
        );
        if (requireCaptureEvidence) {
            if (expectedCaptures.isEmpty() || actualCaptures.isEmpty()) {
                return ProducerPrefixResult.incomplete(
                        "producer capture evidence was not returned for the requested prefix"
                );
            }
            if (expectedCaptures.size() != actualCaptures.size()) {
                return ProducerPrefixResult.incomplete(
                        "producer capture evidence coverage differs for the requested prefix"
                );
            }
        }
        if (expectedCaptures.isEmpty() && actualCaptures.isEmpty()) {
            return ProducerPrefixResult.success();
        }
        return PassManifestComparator.compareCaptures(
                expectedCaptures,
                List.of(expected),
                actualCaptures,
                List.of(actual),
                alignmentPolicy
        ).matched() ? ProducerPrefixResult.success() : ProducerPrefixResult.mismatch();
    }

    private static boolean producerManifestMatches(
            final RenderPassRecord expected,
            final RenderPassRecord actual,
            final ManifestAlignmentPolicy policy
    ) {
        if (expected.producers().size() != actual.producers().size()) return false;
        for (int index = 0; index < expected.producers().size(); index++) {
            if (!producerMatches(expected.producers().get(index), actual.producers().get(index), policy)) {
                return false;
            }
        }
        return true;
    }

    private static DivergenceReport compareEvidence(
            final RunEvidence reference,
            final RunEvidence actual,
            final ManifestAlignmentPolicy alignmentPolicy
    ) {
        DivergenceReport manifest = PassManifestComparator.compare(
                reference.passes(), actual.passes(), alignmentPolicy
        );
        if (!manifest.matched()) return manifest;
        if (reference.captures().isEmpty() && actual.captures().isEmpty()) return manifest;
        return PassManifestComparator.compareCaptures(
                reference.captures(), reference.passes(), actual.captures(), actual.passes(), alignmentPolicy
        );
    }

    private static RunEvidence replayAndRemember(
            final ReplayRunner runner,
            final CapturePlan plan,
            final List<CapturePlan> plans
    ) throws Exception {
        plans.add(plan);
        try {
            RunEvidence evidence = runner.replay(plan);
            if (evidence == null) {
                return incompleteEvidence("replay returned null evidence");
            }
            return evidence;
        } catch (Exception exception) {
            return incompleteEvidence("replay failed: " + exception.getClass().getSimpleName()
                    + (exception.getMessage() == null ? "" : ": " + exception.getMessage()));
        }
    }

    private static RunEvidence incompleteEvidence(final String reason) {
        return new RunEvidence(
                List.of(),
                List.of(),
                "incomplete",
                Map.of(
                        "evidenceComplete", false,
                        "incompleteReason", reason
                )
        );
    }

    private static List<PassKey> orderedPassKeys(final List<RenderPassRecord> passes) {
        List<RenderPassRecord> ordered = new ArrayList<>(passes);
        ordered.sort(Comparator.comparingLong(RenderPassRecord::frameId)
                .thenComparingInt(RenderPassRecord::sequence));
        List<PassKey> keys = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            RenderPassRecord pass = ordered.get(index);
            keys.add(new PassKey(pass.frameId(), index, pass.semanticPassId(), pass.sequence()));
        }
        return keys;
    }

    private static RenderPassRecord passAt(
            final List<RenderPassRecord> passes,
            final PassKey key
    ) {
        return passes.stream()
                .filter(pass -> pass.frameId() == key.frameId()
                        && pass.sequence() == key.sequence()
                        && pass.semanticPassId().equals(key.semanticPassId()))
                .findFirst()
                .orElse(null);
    }

    private static RenderPassRecord findPass(
            final List<RenderPassRecord> passes,
            final PassKey key
    ) {
        return passAt(passes, key);
    }

    private static boolean producerEvidenceComplete(final RenderPassRecord pass) {
        return Boolean.parseBoolean(pass.metadata().getOrDefault("producerDetailsCaptured", "false"))
                && Boolean.parseBoolean(pass.metadata().getOrDefault("producerDetailsComplete", "false"))
                && !Boolean.parseBoolean(pass.metadata().getOrDefault("producerDetailsTruncated", "true"));
    }

    private static List<CaptureSnapshot> capturesForPassPrefix(
            final List<CaptureSnapshot> snapshots,
            final List<RenderPassRecord> passes,
            final int passCount
    ) {
        if (snapshots == null || snapshots.isEmpty() || passes == null || passes.isEmpty() || passCount <= 0) {
            return List.of();
        }
        List<RenderPassRecord> ordered = new ArrayList<>(passes);
        ordered.sort(Comparator.comparingLong(RenderPassRecord::frameId)
                .thenComparingInt(RenderPassRecord::sequence));
        int selectedCount = Math.min(passCount, ordered.size());
        java.util.Set<PassLocation> selected = new java.util.HashSet<>();
        for (int index = 0; index < selectedCount; index++) {
            RenderPassRecord pass = ordered.get(index);
            selected.add(new PassLocation(pass.frameId(), pass.sequence(), pass.semanticPassId()));
        }
        return snapshots.stream()
                .filter(snapshot -> selected.contains(new PassLocation(
                        snapshot.frameId(), snapshot.sequence(), snapshot.semanticPassId()
                )))
                .toList();
    }

    private static List<CaptureSnapshot> capturesForProducerPrefix(
            final List<CaptureSnapshot> snapshots,
            final PassKey pass,
            final int producerCount
    ) {
        if (snapshots == null || snapshots.isEmpty() || producerCount <= 0) return List.of();
        return snapshots.stream()
                .filter(snapshot -> snapshot.frameId() == pass.frameId()
                        && snapshot.sequence() == pass.sequence()
                        && snapshot.semanticPassId().equals(pass.semanticPassId())
                        && snapshot.producerIndex() >= 0
                        && snapshot.producerIndex() < producerCount)
                .toList();
    }

    private static boolean hasProducerCaptureEvidence(
            final List<CaptureSnapshot> expected,
            final List<CaptureSnapshot> actual,
            final PassKey pass
    ) {
        return hasProducerCaptureEvidence(expected, pass) && hasProducerCaptureEvidence(actual, pass);
    }

    private static boolean hasProducerCaptureEvidence(
            final List<CaptureSnapshot> snapshots,
            final PassKey pass
    ) {
        if (snapshots == null) return false;
        return snapshots.stream().anyMatch(snapshot -> snapshot.frameId() == pass.frameId()
                && snapshot.sequence() == pass.sequence()
                && snapshot.semanticPassId().equals(pass.semanticPassId())
                && snapshot.producerIndex() >= 0);
    }

    private record PassLocation(long frameId, int sequence, String semanticPassId) {
    }

    private record PassSearchResult(int index, boolean complete, String reason) {
        private static PassSearchResult complete(final int index) {
            return new PassSearchResult(index, true, "");
        }

        private static PassSearchResult incomplete(final String reason) {
            return new PassSearchResult(-1, false, reason == null ? "incomplete replay evidence" : reason);
        }
    }

    private record ProducerSearchResult(int index, boolean complete, String reason) {
        private static ProducerSearchResult complete(final int index) {
            return new ProducerSearchResult(index, true, "");
        }

        private static ProducerSearchResult unavailable(final String reason) {
            return new ProducerSearchResult(-1, true, reason == null ? "producer localization unavailable" : reason);
        }

        private static ProducerSearchResult incomplete(final String reason) {
            return new ProducerSearchResult(-1, false, reason == null ? "incomplete replay evidence" : reason);
        }
    }

    private record ProducerPrefixResult(boolean matched, boolean complete, String reason) {
        private static ProducerPrefixResult success() {
            return new ProducerPrefixResult(true, true, "");
        }

        private static ProducerPrefixResult mismatch() {
            return new ProducerPrefixResult(false, true, "");
        }

        private static ProducerPrefixResult incomplete(final String reason) {
            return new ProducerPrefixResult(false, false,
                    reason == null ? "incomplete producer replay evidence" : reason);
        }
    }

    private static boolean producerMatches(
            final ProducerRecord expected,
            final ProducerRecord actual,
            final ManifestAlignmentPolicy policy
    ) {
        if (expected.producerType() != actual.producerType()) return false;
        if (policy.comparePipelineAndShaders()
                && (!expected.pipelineId().equals(actual.pipelineId())
                || !expected.shaderIds().equals(actual.shaderIds()))) return false;
        return expected.parameters().equals(actual.parameters())
                && expected.boundResources().equals(actual.boundResources())
                && expected.viewport().equals(actual.viewport())
                && expected.scissor().equals(actual.scissor())
                && expected.writtenAttachments().equals(actual.writtenAttachments());
    }

    private static LocalizationResult unsupported(
            final DivergenceReport comparison,
            final List<CapturePlan> plans,
            final String reason
    ) {
        return new LocalizationResult(false, "unsupported", comparison, null, -1, plans, Map.of("reason", reason));
    }
}
