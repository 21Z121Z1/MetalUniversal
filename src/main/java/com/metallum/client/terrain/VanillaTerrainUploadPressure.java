package com.metallum.client.terrain;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/** Opt-in process-wide CPU pressure diagnostics, independent of scheduling and work-event capture. */
public final class VanillaTerrainUploadPressure {
    public static final String ENABLE_PROPERTY = "metallum.terrain.vanillaUploadPressure";
    private static final TerrainUploadPressureCounters COUNTERS = new TerrainUploadPressureCounters();

    private VanillaTerrainUploadPressure() {
    }

    public static TerrainUploadPressureCounters counters() {
        return COUNTERS;
    }

    public static JsonObject report(final String sourceSha, final String trialId, final String status) {
        if (sourceSha == null || !sourceSha.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("upload pressure requires exact source SHA");
        }
        if (trialId == null || trialId.isBlank() || trialId.length() > 160
                || status == null || status.isBlank()) {
            throw new IllegalArgumentException("upload pressure requires trial identity and status");
        }
        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", 1);
        report.addProperty("evidenceClass", "diagnostic");
        report.addProperty("performanceEligible", false);
        report.addProperty("scope", "process-observation-including-warmup");
        report.addProperty("clock", "System.nanoTime");
        report.addProperty("validationStatus", status);
        JsonObject source = new JsonObject();
        source.addProperty("sourceSha", sourceSha);
        source.addProperty("trialId", trialId);
        source.addProperty("minecraftVersion", "26.3");
        report.add("source", source);
        report.add("counters", new GsonBuilder().create().toJsonTree(COUNTERS.snapshot()));
        JsonObject limits = new JsonObject();
        limits.addProperty("requestedBytesIncludingRetries", "not accepted bytes or GPU upload bytes");
        limits.addProperty("attemptCpuNanos", "wall time including copy-lock wait and render-thread upload retries");
        limits.addProperty("waitNanos", "copy-lock call wall time including uncontended acquisition overhead");
        limits.addProperty("uploadCpuNanos", "CPU encoding call wall time, not GPU completion");
        limits.addProperty("gpuCompletion", "unavailable: no completion hook");
        limits.addProperty("inFlightBytes", "unavailable: no allocation retirement accounting");
        limits.addProperty("spinWaitNanos", "unavailable: retry attempts do not measure Thread.onSpinWait duration");
        report.add("measurementLimits", limits);
        return report;
    }
}
