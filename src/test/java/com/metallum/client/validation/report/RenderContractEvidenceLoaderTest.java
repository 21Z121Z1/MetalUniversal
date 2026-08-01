package com.metallum.client.validation.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.metallum.client.validation.contract.CaptureFormat;
import com.metallum.client.validation.contract.PassType;
import com.metallum.client.validation.contract.RenderPassRecord;
import com.metallum.client.validation.contract.ResourceIdentity;
import com.metallum.client.validation.contract.ScissorRecord;
import com.metallum.client.validation.contract.ViewportRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RenderContractEvidenceLoaderTest {
    private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    @Test
    void retainsRawResourcesFromFailedCaptureButMarksRunIncomplete(@TempDir final Path root) throws Exception {
        writeEvidenceRoot(root, "failed", "failed", false, 1);

        RenderContractEvidenceLoader.LoadedEvidence loaded =
                RenderContractEvidenceLoader.load(root);

        assertEquals(1, loaded.passes().size());
        assertEquals(1, loaded.captures().size());
        assertFalse(loaded.complete());
        assertEquals("manifest status=failed, results status=failed, manifestComplete=false, "
                        + "failedCaptures=1, failedCaptureEntries=1",
                loaded.incompleteReason());
    }

    @Test
    void diagnosisCannotPassWhenBothRunsHaveMatchingButIncompleteEvidence(@TempDir final Path root)
            throws Exception {
        Path reference = root.resolve("reference");
        Path actual = root.resolve("actual");
        writeEvidenceRoot(reference, "failed", "failed", false, 1);
        writeEvidenceRoot(actual, "failed", "failed", false, 1);
        Path report = root.resolve("diagnosis.json");

        assertThrows(IllegalStateException.class, () -> RenderContractDiagnosis.main(new String[]{
                reference.toString(), actual.toString(), report.toString()
        }));
        JsonObject result = GSON.fromJson(Files.readString(report), JsonObject.class);
        assertEquals("failed", result.get("status").getAsString());
        assertEquals("evidence incomplete; matching bytes are not a validation pass",
                result.getAsJsonObject("comparison").get("reason").getAsString());
        assertFalse(result.get("referenceComplete").getAsBoolean());
        assertFalse(result.get("actualComplete").getAsBoolean());
    }

    private static void writeEvidenceRoot(
            final Path root,
            final String manifestStatus,
            final String resultsStatus,
            final boolean manifestComplete,
            final int failedCaptures
    ) throws Exception {
        Files.createDirectories(root);
        ResourceIdentity identity = new ResourceIdentity(
                "color0", 1L, 2L, "debug-color0", "RGBA8_UNORM", 1, 1, 1, 0, 1, 3
        );
        CaptureFormat format = CaptureFormat.fromFormat("RGBA8_UNORM", 4);
        Files.createDirectories(root.resolve("frames/frame-000000/test-pass/after_pass/color0"));
        Files.write(root.resolve("frames/frame-000000/test-pass/after_pass/color0/actual.bin"),
                new byte[]{1, 2, 3, 4});

        RenderPassRecord pass = new RenderPassRecord(
                0L, 0, "test/pass", PassType.RENDER, List.of(), null, null,
                new ViewportRecord(0, 0, 1, 1), ScissorRecord.disabled(),
                "pipeline/test", List.of(), List.of(),
                Map.of("producerCount", "0")
        );
        JsonObject manifest = new JsonObject();
        manifest.addProperty("schemaVersion", 1);
        manifest.addProperty("runId", "test-run");
        manifest.addProperty("gitCommit", "test-commit");
        manifest.addProperty("status", manifestStatus);
        manifest.addProperty("manifestComplete", manifestComplete);
        manifest.addProperty("frameCount", 1);
        manifest.addProperty("passCount", 1);
        manifest.addProperty("resourceCount", 1);
        manifest.addProperty("droppedEvents", 0);
        manifest.add("passes", new JsonArray());
        manifest.getAsJsonArray("passes").add(GSON.toJsonTree(pass));
        manifest.add("openPasses", new JsonArray());
        Files.writeString(root.resolve("pass-manifest.json"), GSON.toJson(manifest) + "\n");

        JsonObject resource = new JsonObject();
        resource.addProperty("semanticName", "color0");
        resource.addProperty("resourceId", identity.stableKey());
        resource.add("resource", GSON.toJsonTree(identity));
        resource.add("captureFormat", GSON.toJsonTree(format));
        resource.addProperty("width", 1);
        resource.addProperty("height", 1);
        resource.addProperty("actual", "frames/frame-000000/test-pass/after_pass/color0/actual.bin");
        resource.addProperty("status", "captured");

        JsonObject capture = new JsonObject();
        capture.addProperty("schemaVersion", 1);
        capture.addProperty("runId", "test-run");
        capture.addProperty("gitCommit", "test-commit");
        capture.addProperty("frameId", 0L);
        capture.addProperty("semanticPassId", "test/pass");
        capture.addProperty("capturePoint", "AFTER_PASS");
        capture.addProperty("producerIndex", -1);
        capture.addProperty("status", failedCaptures == 0 ? "captured" : "failed");
        capture.add("resources", new JsonArray());
        capture.getAsJsonArray("resources").add(resource);

        JsonObject results = new JsonObject();
        results.addProperty("schemaVersion", 1);
        results.addProperty("runId", "test-run");
        results.addProperty("gitCommit", "test-commit");
        results.addProperty("status", resultsStatus);
        results.addProperty("requestedCaptures", 1);
        results.addProperty("completedCaptures", 1);
        results.addProperty("failedCaptures", failedCaptures);
        results.addProperty("pendingCaptures", 0);
        results.addProperty("droppedCaptures", 0);
        results.add("captures", new JsonArray());
        results.getAsJsonArray("captures").add(capture);
        Files.writeString(root.resolve("results.json"), GSON.toJson(results) + "\n");
    }
}
