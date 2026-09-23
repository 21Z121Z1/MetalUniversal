package com.metallum.client.terrain;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.Map;

/** Lossless JSON boundary for the bounded recorder. Materialized only outside draw/build hooks. */
public final class TerrainWorkReport {
    private TerrainWorkReport() {
    }

    public static Map<Long, JsonObject> create(
            final TerrainWorkEventRecorder.Snapshot snapshot,
            final String sourceSha,
            final String trialId,
            final long currentEpoch,
            final boolean completed,
            final String failureReason
    ) {
        if (sourceSha == null || !sourceSha.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("terrain report requires exact source SHA");
        }
        if (trialId == null || trialId.isBlank() || trialId.length() > 160) {
            throw new IllegalArgumentException("terrain report requires trial identity");
        }
        if (!completed && (failureReason == null || failureReason.isBlank())) {
            throw new IllegalArgumentException("failed terrain trial requires a reason");
        }

        Map<Long, JsonObject> reports = new LinkedHashMap<>();
        for (TerrainWorkEventRecorder.Event event : snapshot.events()) {
            JsonObject report = reports.computeIfAbsent(
                    event.key().worldEpoch(),
                    epoch -> header(snapshot, sourceSha, trialId, epoch, completed, failureReason)
            );
            report.getAsJsonArray("events").add(eventJson(event));
        }
        if (reports.isEmpty()) {
            reports.put(
                    currentEpoch,
                    header(snapshot, sourceSha, trialId, currentEpoch, completed, failureReason)
            );
        }
        return Map.copyOf(reports);
    }

    private static JsonObject header(
            final TerrainWorkEventRecorder.Snapshot snapshot,
            final String sourceSha,
            final String trialId,
            final long epoch,
            final boolean completed,
            final String failureReason
    ) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 2);
        JsonObject source = new JsonObject();
        source.addProperty("sourceSha", sourceSha);
        source.addProperty("minecraftVersion", "26.3");
        source.addProperty("trialId", trialId);
        source.addProperty("worldEpoch", Long.toString(epoch));
        source.addProperty("clock", "System.nanoTime");
        root.add("source", source);
        root.addProperty("status", completed ? "complete" : "failed");
        root.addProperty("observationStartNanos", Long.toString(snapshot.observationStartNanos()));
        root.addProperty("observationEndNanos", Long.toString(snapshot.observationEndNanos()));

        // The ring is shared across epochs. Loss cannot be assigned to one epoch after the fact,
        // therefore every split report explicitly labels this counter as observation-wide.
        root.addProperty("lossScope", "observation");
        root.addProperty("droppedEvents", snapshot.droppedEvents());
        root.addProperty("overflowed", snapshot.overflowed());
        root.add("failureReason", completed ? JsonNull.INSTANCE : new JsonPrimitive(failureReason));
        root.add("events", new JsonArray());
        return root;
    }

    private static JsonObject eventJson(final TerrainWorkEventRecorder.Event event) {
        JsonObject result = new JsonObject();
        result.addProperty("sequence", event.sequence());
        result.addProperty("workId", Long.toString(event.workId()));
        JsonObject key = new JsonObject();
        key.addProperty("worldEpoch", Long.toString(event.key().worldEpoch()));
        key.addProperty("sectionId", Long.toString(event.key().sectionId()));
        key.addProperty("geometryRevision", Long.toString(event.key().geometryRevision()));
        key.addProperty("lightingRevision", Long.toString(event.key().lightingRevision()));
        key.addProperty("materialGeneration", Long.toString(event.key().materialGeneration()));
        result.add("key", key);
        result.addProperty("stage", event.stage().name());
        result.addProperty("monotonicNanos", Long.toString(event.monotonicNanos()));
        result.addProperty("bytes", event.bytes());
        result.addProperty("reason", event.reason());
        result.addProperty("domain", event.domain());
        result.add("frameIndex", event.hasFrameIndex()
                ? new JsonPrimitive(event.frameIndex()) : JsonNull.INSTANCE);
        result.add("meshGeneration", event.hasMeshGeneration()
                ? new JsonPrimitive(Long.toString(event.meshGeneration())) : JsonNull.INSTANCE);
        return result;
    }
}
