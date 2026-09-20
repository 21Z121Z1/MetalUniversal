package com.metallum.client.terrain;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.LongSerializationPolicy;
import java.util.Objects;
import java.util.function.Supplier;

/** Diagnostic bridge: observing a requested flag alone is not evidence of mixin execution. */
public final class VanillaTerrainGenerationTelemetry {
    private static volatile Supplier<TerrainPublicationGenerationGuard.Evidence> provider;
    private static volatile boolean hooksObserved;

    private VanillaTerrainGenerationTelemetry() { }

    public static void register(Supplier<TerrainPublicationGenerationGuard.Evidence> source) {
        provider = Objects.requireNonNull(source, "source");
    }

    public static void observeHook() {
        // Called only by opt-in generation mixins. Avoid writes after the first observed hook.
        if (!hooksObserved) hooksObserved = true;
    }

    public static JsonObject report(String sourceSha, String trialId, String validationStatus) {
        var source = provider;
        return create(sourceSha, trialId, validationStatus,
                Boolean.getBoolean("metallum.terrain.vanillaGenerationGuard"), hooksObserved,
                source == null ? null : source.get());
    }

    static JsonObject create(String sourceSha, String trialId, String validationStatus,
                             boolean requested, boolean observed,
                             TerrainPublicationGenerationGuard.Evidence evidence) {
        if (sourceSha == null || !sourceSha.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Generation report requires exact source SHA");
        }
        if (trialId == null || trialId.isBlank() || trialId.length() > 160
                || validationStatus == null || validationStatus.isBlank()) {
            throw new IllegalArgumentException("Generation report requires trial identity and status");
        }
        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", 1);
        report.addProperty("evidenceClass", "diagnostic");
        report.addProperty("performanceEligible", false);
        JsonObject source = new JsonObject();
        source.addProperty("sourceSha", sourceSha);
        source.addProperty("minecraftVersion", "26.3");
        source.addProperty("trialId", trialId);
        report.add("source", source);
        report.addProperty("validationStatus", validationStatus);
        report.addProperty("requested", requested);
        report.addProperty("mixinHooksObserved", observed);
        report.addProperty("scope", "process-observation-including-warmup");
        report.addProperty("limitation", "decision evidence only; no task/mesh ownership chain or performance acceptance");
        report.add("evidence", new GsonBuilder().serializeNulls()
                .setLongSerializationPolicy(LongSerializationPolicy.STRING).create().toJsonTree(evidence));
        return report;
    }
}
