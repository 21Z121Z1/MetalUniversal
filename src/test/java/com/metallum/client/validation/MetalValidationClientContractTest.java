package com.metallum.client.validation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.metallum.client.metal.render.TerrainGpuVisibilityProbe;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalValidationClientContractTest {
    private static final List<String> TERRAIN_VISIBILITY_FIELDS = List.of(
            "terrainGpuVisibilityProbeEnabled",
            "terrainGpuVisibilityCandidateCount",
            "terrainGpuVisibilityVisibleCount",
            "terrainGpuVisibilityUncertainCount",
            "terrainGpuVisibilityAttempts",
            "terrainGpuVisibilityDispatches",
            "terrainGpuVisibilityProduced",
            "terrainGpuVisibilityFallbacks",
            "terrainGpuVisibilityFalseNegativeOracleCount",
            "terrainGpuVisibilityCompactedCount",
            "terrainGpuVisibilityCompactionDispatches",
            "terrainGpuVisibilityCompactionProduced",
            "terrainGpuVisibilityCompactionFallbacks",
            "terrainGpuVisibilityCompactionMismatchOracleCount",
            "terrainGpuVisibilityLastCompletedEpoch"
    );

    @Test
    void defaultTelemetryIsDisabledAndZeroValued() {
        TerrainGpuVisibilityProbe.Telemetry telemetry = TerrainGpuVisibilityProbe.telemetry();

        assertFalse(telemetry.enabled());
        assertEquals(0L, telemetry.candidateCount());
        assertEquals(0L, telemetry.visibleCount());
        assertEquals(0L, telemetry.uncertainCount());
        assertEquals(0L, telemetry.attemptedCount());
        assertEquals(0L, telemetry.dispatchCount());
        assertEquals(0L, telemetry.producedCount());
        assertEquals(0L, telemetry.fallbackCount());
        assertEquals(0L, telemetry.falseNegativeOracleCount());
        assertEquals(0L, telemetry.compactedCount());
        assertEquals(0L, telemetry.compactionDispatchCount());
        assertEquals(0L, telemetry.compactionProducedCount());
        assertEquals(0L, telemetry.compactionFallbackCount());
        assertEquals(0L, telemetry.compactionMismatchOracleCount());
        assertEquals(-1L, telemetry.lastCompletedEpoch());
    }

    @Test
    void terrainVisibilityJsonHasStableStructuredFieldOrderAndValues() {
        TerrainGpuVisibilityProbe.Telemetry telemetry = new TerrainGpuVisibilityProbe.Telemetry(
                false,
                33L,
                29L,
                2L,
                7L,
                5L,
                4L,
                3L,
                1L,
                42L
        );
        String fragment = MetalValidationClient.terrainGpuVisibilityJson(telemetry);
        JsonObject json = JsonParser.parseString("{" + fragment + "\"sentinel\": true}")
                .getAsJsonObject();

        assertEquals(
                TERRAIN_VISIBILITY_FIELDS,
                new ArrayList<>(json.keySet()).subList(0, TERRAIN_VISIBILITY_FIELDS.size())
        );
        assertFalse(json.get("terrainGpuVisibilityProbeEnabled").getAsBoolean());
        assertEquals(33L, json.get("terrainGpuVisibilityCandidateCount").getAsLong());
        assertEquals(29L, json.get("terrainGpuVisibilityVisibleCount").getAsLong());
        assertEquals(2L, json.get("terrainGpuVisibilityUncertainCount").getAsLong());
        assertEquals(7L, json.get("terrainGpuVisibilityAttempts").getAsLong());
        assertEquals(5L, json.get("terrainGpuVisibilityDispatches").getAsLong());
        assertEquals(4L, json.get("terrainGpuVisibilityProduced").getAsLong());
        assertEquals(3L, json.get("terrainGpuVisibilityFallbacks").getAsLong());
        assertEquals(1L, json.get("terrainGpuVisibilityFalseNegativeOracleCount").getAsLong());
        assertEquals(0L, json.get("terrainGpuVisibilityCompactedCount").getAsLong());
        assertEquals(0L, json.get("terrainGpuVisibilityCompactionDispatches").getAsLong());
        assertEquals(0L, json.get("terrainGpuVisibilityCompactionProduced").getAsLong());
        assertEquals(0L, json.get("terrainGpuVisibilityCompactionFallbacks").getAsLong());
        assertEquals(0L, json.get("terrainGpuVisibilityCompactionMismatchOracleCount").getAsLong());
        assertEquals(42L, json.get("terrainGpuVisibilityLastCompletedEpoch").getAsLong());
        assertTrue(json.get("sentinel").getAsBoolean());
    }
}
