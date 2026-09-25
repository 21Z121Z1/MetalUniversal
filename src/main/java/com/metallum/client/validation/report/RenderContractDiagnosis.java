package com.metallum.client.validation.report;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Offline comparison entry point for bounded, already-captured contract evidence. */
public final class RenderContractDiagnosis {
    private RenderContractDiagnosis() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: RenderContractDiagnosis <reference-root> <actual-root> <report.json>"
            );
        }
        Path referenceRoot = Path.of(args[0]).toAbsolutePath().normalize();
        Path actualRoot = Path.of(args[1]).toAbsolutePath().normalize();
        Path reportPath = Path.of(args[2]).toAbsolutePath().normalize();
        RenderContractEvidenceLoader.LoadedEvidence reference =
                RenderContractEvidenceLoader.load(referenceRoot);
        RenderContractEvidenceLoader.LoadedEvidence actual =
                RenderContractEvidenceLoader.load(actualRoot);
        ManifestAlignmentPolicy alignmentPolicy = ManifestAlignmentPolicy.crossBackend();
        DivergenceReport comparison = PassManifestComparator.compareCaptures(
                reference.captures(), reference.passes(), actual.captures(), actual.passes(), alignmentPolicy
        );
        if (comparison.matched() && reference.complete() && actual.complete()) {
            comparison = PassManifestComparator.compare(reference.passes(), actual.passes(), alignmentPolicy);
        } else if (comparison.matched()) {
            comparison = incompleteEvidence(reference, actual);
        } else {
            DivergenceReport manifest = PassManifestComparator.compare(
                    reference.passes(), actual.passes(), alignmentPolicy
            );
            if (!manifest.matched()) {
                comparison = mergeEvidence(manifest, comparison);
            }
        }
        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", 1);
        report.addProperty("runId", "diagnosis");
        report.addProperty("gitCommit", System.getProperty("metallum.validation.sourceCommit", "unknown"));
        report.addProperty("status", comparison.matched() ? "passed" : "failed");
        report.addProperty("comparisonKind", "offline-captured-evidence");
        report.addProperty("frameId", comparison.frameId());
        report.addProperty("referenceRoot", referenceRoot.toString());
        report.addProperty("actualRoot", actualRoot.toString());
        report.addProperty("referenceComplete", reference.complete());
        report.addProperty("actualComplete", actual.complete());
        report.addProperty("referenceIncompleteReason", reference.incompleteReason());
        report.addProperty("actualIncompleteReason", actual.incompleteReason());
        report.add("comparison", new GsonBuilder().create().toJsonTree(comparison));
        report.addProperty("referencePasses", reference.passes().size());
        report.addProperty("actualPasses", actual.passes().size());
        report.addProperty("referenceCaptures", reference.captures().size());
        report.addProperty("actualCaptures", actual.captures().size());
        if (reportPath.getParent() != null) Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
        if (!comparison.matched()) {
            throw new IllegalStateException("Render-contract evidence diverged; see " + reportPath);
        }
    }

    private static DivergenceReport mergeEvidence(
            final DivergenceReport manifest,
            final DivergenceReport capture
    ) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("manifestReason", manifest.reason());
        metrics.put("captureReason", capture.reason());
        metrics.put("manifest", manifest);
        metrics.put("capture", capture);
        return new DivergenceReport(
                false,
                manifest.lastMatchingPass(),
                manifest.firstDivergentPass(),
                manifest.frameId(),
                manifest.sequence(),
                manifest.semanticPassId(),
                capture.producerIndex(),
                manifest.resource(),
                "logical pass manifest differs before capture comparison completed",
                metrics
        );
    }

    private static DivergenceReport incompleteEvidence(
            final RenderContractEvidenceLoader.LoadedEvidence reference,
            final RenderContractEvidenceLoader.LoadedEvidence actual
    ) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("referenceComplete", reference.complete());
        metrics.put("actualComplete", actual.complete());
        metrics.put("referenceIncompleteReason", reference.incompleteReason());
        metrics.put("actualIncompleteReason", actual.incompleteReason());
        return new DivergenceReport(
                false,
                "none",
                "none",
                -1L,
                -1,
                "none",
                -1,
                "none",
                "evidence incomplete; matching bytes are not a validation pass",
                metrics
        );
    }
}
