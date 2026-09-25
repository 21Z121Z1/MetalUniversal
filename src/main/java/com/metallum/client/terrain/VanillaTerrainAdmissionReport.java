package com.metallum.client.terrain;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Structured diagnostic envelope for T1a admission telemetry.
 *
 * <p>This report is deliberately not a performance-acceptance artifact yet. It carries exact source
 * identity and explicitly marks itself ineligible for promotion until paired physical baselines and
 * a dedicated independent admission schema/oracle are added.</p>
 */
public final class VanillaTerrainAdmissionReport {
    private VanillaTerrainAdmissionReport() {
    }

    public static JsonObject create(
            final String sourceSha,
            final String trialId,
            final String validationStatus
    ) {
        if (sourceSha == null || !sourceSha.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("terrain admission report requires exact source SHA");
        }
        if (trialId == null || trialId.isBlank() || trialId.length() > 160) {
            throw new IllegalArgumentException("terrain admission report requires trial identity");
        }
        if (validationStatus == null || validationStatus.isBlank()) {
            throw new IllegalArgumentException("terrain admission report requires validation status");
        }

        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("evidenceClass", "diagnostic");
        root.addProperty("performanceEligible", false);

        JsonObject source = new JsonObject();
        source.addProperty("sourceSha", sourceSha);
        source.addProperty("minecraftVersion", "26.3");
        source.addProperty("trialId", trialId);
        root.add("source", source);

        root.addProperty("validationStatus", validationStatus);
        root.add(
                "snapshot",
                new GsonBuilder().serializeNulls().create().toJsonTree(VanillaTerrainAdmissionTelemetry.snapshot())
        );
        return root;
    }
}
