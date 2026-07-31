package com.metallum.client.metal.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audits the machine-readable OpenGL semantic coverage contract. */
final class IrisMetalSemanticMatrixTest {
    private static final String RESOURCE = "/iris-metal-opengl-semantic-matrix.json";

    @Test
    void matrixHasAuditableStaticThresholdAndRuntimeGates() {
        JsonObject matrix;
        try (InputStream stream = getClass().getResourceAsStream(RESOURCE)) {
            assertNotNull(stream, "semantic matrix resource");
            matrix = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)
            ).getAsJsonObject();
        } catch (Exception failure) {
            throw new AssertionError("Could not read semantic matrix", failure);
        }

        assertEquals("iris-metal-opengl-semantic-matrix-v1", matrix.get("schema").getAsString());
        assertEquals("generation-owned-iris-execution-graph-phase2", matrix.get("scope").getAsString());
        double threshold = matrix.get("threshold").getAsDouble();
        JsonArray entries = matrix.getAsJsonArray("entries");
        assertEquals(matrix.get("declaredTotal").getAsInt(), entries.size());

        Set<String> ids = new HashSet<>();
        int staticVerified = 0;
        for (var element : entries) {
            JsonObject entry = element.getAsJsonObject();
            String id = entry.get("id").getAsString();
            assertTrue(ids.add(id), "duplicate matrix id: " + id);
            assertFalse(entry.get("contract").getAsString().isBlank(), id);
            String status = entry.get("status").getAsString();
            if ("verified".equals(status)) {
                staticVerified++;
                JsonArray evidence = entry.getAsJsonArray("evidence");
                assertNotNull(evidence, id + " evidence");
                assertFalse(evidence.isEmpty(), id + " evidence");
                for (var evidenceElement : evidence) {
                    JsonObject item = evidenceElement.getAsJsonObject();
                    assertFalse(item.get("kind").getAsString().isBlank(), id + " evidence kind");
                    assertFalse(item.get("id").getAsString().isBlank(), id + " evidence id");
                }
            } else if ("runtime".equals(status)) {
                boolean hasRuntimeGate = entry.has("receiptEvents")
                        || entry.has("controlEvents")
                        || entry.has("receiptMetrics")
                        || entry.has("requiresExitStatus");
                assertTrue(hasRuntimeGate, id + " runtime gate");
            } else {
                throw new AssertionError("Unknown semantic matrix status: " + status);
            }
        }

        assertTrue(
                (double) staticVerified / entries.size() >= threshold,
                "static semantic evidence below threshold: " + staticVerified + "/" + entries.size()
        );

        JsonObject readback = null;
        for (var element : entries) {
            JsonObject entry = element.getAsJsonObject();
            if ("runtime.readback-dynamic-exit".equals(entry.get("id").getAsString())) {
                readback = entry;
                break;
            }
        }
        assertNotNull(readback, "runtime readback semantic entry");
        assertTrue(
                readback.getAsJsonArray("controlEvents").toString().contains("world.entered"),
                "runtime readback must require an explicit world-entry receipt"
        );
    }
}
