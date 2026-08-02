package com.metallum.client.validation.report;

import java.util.LinkedHashMap;
import java.util.Map;

/** Evidence-backed first-divergence result for pass/producer localization. */
public record DivergenceReport(
        boolean matched,
        String lastMatchingPass,
        String firstDivergentPass,
        long frameId,
        int sequence,
        String semanticPassId,
        int producerIndex,
        String resource,
        String reason,
        Map<String, Object> metrics
) {
    public DivergenceReport {
        lastMatchingPass = lastMatchingPass == null ? "none" : lastMatchingPass;
        firstDivergentPass = firstDivergentPass == null ? "none" : firstDivergentPass;
        semanticPassId = semanticPassId == null ? "none" : semanticPassId;
        resource = resource == null ? "none" : resource;
        reason = reason == null ? "" : reason;
        metrics = metrics == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metrics));
    }

    public static DivergenceReport success() {
        return new DivergenceReport(true, "last", "none", -1L, -1, "none", -1, "none", "manifests match", Map.of());
    }
}
