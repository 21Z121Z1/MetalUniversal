package com.metallum.client.terrain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

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
        assertTrue(json.get("targetPresentIntervalNanosDerived").getAsBoolean());
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
        assertFalse(json.get("measuredPresentIntervalNanosDerived").getAsBoolean());
        assertTrue(json.get("drawableWaitNanosMeasured").getAsBoolean());
        assertEquals(3L, json.get("framesInFlight").getAsLong());
        assertEquals("count", json.get("framesInFlightUnit").getAsString());
    }

    @Test
    void adapterOutputValidatesAgainstDraft202012Schema() throws Exception {
        Path schemaPath = Path.of("docs/agent/presentation-pacing-evidence.schema.json");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode schemaNode = mapper.readTree(Files.readString(schemaPath));
        JsonSchema schema = JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schemaNode);
        JsonNode instance = mapper.readTree(PresentationPacingEvidenceAdapter.toJsonString(
                PresentationPacingSnapshot.capture(9L, -1, 4_000_000L, -1L)
        ));

        Set<ValidationMessage> errors = schema.validate(instance);
        assertTrue(errors.isEmpty(), () -> "schema errors: " + errors);

        ObjectNode invalid = (ObjectNode) instance.deepCopy();
        invalid.put("targetPresentIntervalNanosAvailable", false);
        invalid.put("targetPresentIntervalNanos", 16_666_667L);
        assertFalse(schema.validate(invalid).isEmpty(),
                "schema must reject an unavailable metric with a numeric value");
    }
}
