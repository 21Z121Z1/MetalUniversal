package com.metallum.client.terrain;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PresentationPacingEvidenceAdapterTest {
    @Test
    void jsonIsStableAndCarriesAvailabilityProvenanceAndFallback() {
        PresentationPacingSnapshot snapshot = PresentationPacingSnapshot.capture(7L, -1, 4_000_000L, -1L);
        String first = PresentationPacingEvidenceAdapter.toJsonString(snapshot);
        String second = PresentationPacingEvidenceAdapter.toJsonString(snapshot);
        assertEquals(first, second);

        JsonObject json = JsonParser.parseString(first).getAsJsonObject();
        assertEquals(1, json.get("schemaVersion").getAsInt());
        assertEquals(7L, json.get("frameIndex").getAsLong());
        assertTrue(json.get("refreshRateHz").isJsonNull());
        assertEquals(16_666_667L, json.get("targetPresentIntervalNanos").getAsLong());
        assertFalse(json.get("targetPresentIntervalNanosMeasured").getAsBoolean());
        assertEquals("conservative-60hz-fallback",
                json.get("targetPresentIntervalNanosProvenance").getAsString());
        assertEquals("display-refresh-source-unavailable",
                json.get("targetPresentIntervalNanosFallbackReason").getAsString());
        assertEquals(4_000_000L, json.get("cpuFrameTimeNanos").getAsLong());
        assertTrue(json.get("cpuFrameTimeNanosMeasured").getAsBoolean());
        assertTrue(json.get("cpuFrameTimeNanosAvailable").getAsBoolean());
        assertTrue(json.get("gpuFrameTimeNanos").isJsonNull());
        assertFalse(json.get("gpuFrameTimeNanosAvailable").getAsBoolean());
        assertTrue(json.get("measuredPresentIntervalNanos").isJsonNull());
        assertFalse(json.get("measuredPresentIntervalNanosAvailable").getAsBoolean());
        assertEquals(PresentationPacingSnapshot.PRESENT_INTERVAL_UNAVAILABLE_REASON,
                json.get("measuredPresentIntervalNanosFallbackReason").getAsString());
    }

    @Test
    void measuredPresentAndNativeWaitFieldsSerializeAsMeasured() {
        PresentationPacingSnapshot snapshot = PresentationPacingSnapshot.capture(
                8L, 90, 3_000_000L, 4_000_000L, 11_111_111L, 200_000L, 3L
        );
        JsonObject json = PresentationPacingEvidenceAdapter.toJson(snapshot);
        assertEquals(11_111_111L, json.get("measuredPresentIntervalNanos").getAsLong());
        assertTrue(json.get("measuredPresentIntervalNanosMeasured").getAsBoolean());
        assertTrue(json.get("drawableWaitNanosMeasured").getAsBoolean());
        assertEquals(3L, json.get("framesInFlight").getAsLong());
        assertEquals("count", json.get("framesInFlightUnit").getAsString());
    }
}
