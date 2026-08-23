package com.metallum.client.terrain;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** Converts pacing snapshots into the authoritative unified-evidence shape. */
public final class PresentationPacingEvidenceAdapter {
    public static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private PresentationPacingEvidenceAdapter() {
    }

    public static JsonObject toJson(final PresentationPacingSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("frameIndex", snapshot.frameIndex());
        addNullableNumber(root, "refreshRateHz", snapshot.refreshRateHz(), snapshot.refreshRateHz() > 0);
        addValue(root, "targetPresentIntervalNanos", snapshot.targetPresentInterval());
        addValue(root, "measuredPresentIntervalNanos", snapshot.measuredPresentInterval());
        addValue(root, "cpuFrameTimeNanos", snapshot.cpuFrameTime());
        addValue(root, "gpuFrameTimeNanos", snapshot.gpuFrameTime());
        addValue(root, "drawableWaitNanos", snapshot.drawableWait());
        addValue(root, "framesInFlight", snapshot.framesInFlight());
        root.addProperty("provenance", snapshot.provenance());
        addNullableString(root, "fallbackReason", snapshot.fallbackReason());
        return root;
    }

    public static String toJsonString(final PresentationPacingSnapshot snapshot) {
        return GSON.toJson(toJson(snapshot));
    }

    private static void addValue(
            final JsonObject root,
            final String name,
            final PresentationPacingSnapshot.Value value
    ) {
        addNullableNumber(root, name, value.value(), value.available());
        root.addProperty(name + "Available", value.available());
        root.addProperty(name + "Measured", value.measured());
        root.addProperty(name + "Unit", value.unit());
        root.addProperty(name + "Provenance", value.provenance());
        addNullableString(root, name + "FallbackReason", value.fallbackReason());
    }

    private static void addNullableNumber(
            final JsonObject root,
            final String name,
            final long value,
            final boolean available
    ) {
        if (available) {
            root.addProperty(name, value);
        } else {
            root.add(name, JsonNull.INSTANCE);
        }
    }

    private static void addNullableString(
            final JsonObject root,
            final String name,
            final String value
    ) {
        if (value == null) {
            root.add(name, JsonNull.INSTANCE);
        } else {
            root.addProperty(name, value);
        }
    }
}
