package com.metallum.client.terrain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class VanillaTerrainGenerationTelemetryTest {
    private static final String SHA = "1234567890123456789012345678901234567890";

    @Test
    void requestedFlagDoesNotManufactureObservedMixinEvidence() {
        var report = VanillaTerrainGenerationTelemetry.create(SHA, "missing-hooks", "passed", true, false, null);
        assertTrue(report.get("requested").getAsBoolean());
        assertFalse(report.get("mixinHooksObserved").getAsBoolean());
        assertTrue(report.get("evidence").isJsonNull());
        assertFalse(report.get("performanceEligible").getAsBoolean());
    }

    @Test
    void reportPreservesExactLongGenerationsAndDiagnosticBoundary() throws Exception {
        var guard = new TerrainPublicationGenerationGuard<>(
                new TerrainPublicationGenerationGuard.Config(true, 8, 8),
                new TerrainPublicationGenerationGuard.TaskOps<Object>() {
                    public boolean isCancelled(Object task) { return false; }
                    public void cancel(Object task) { }
                }, 16);
        Object task = new Object();
        Object mesh = new Object();
        guard.registerTask(task, Long.MAX_VALUE);
        assertTrue(guard.enterTask(task));
        guard.bindMeshFromActiveTask(mesh);
        guard.exitTask(task);
        assertEquals(TerrainPublicationGenerationGuard.PublicationDecision.ALLOW_CURRENT,
                guard.withPublicationDecision(Long.MAX_VALUE, mesh, decision -> decision));
        var report = VanillaTerrainGenerationTelemetry.create(
                SHA, "long-id", "passed", true, true, guard.snapshotEvidence());
        var evidence = report.getAsJsonObject("evidence");
        var first = evidence.getAsJsonArray("events").get(0).getAsJsonObject();
        assertEquals(Long.toString(Long.MAX_VALUE), first.get("sectionId").getAsString());
        assertTrue(first.getAsJsonPrimitive("sectionId").isString());
        assertEquals(SHA, report.getAsJsonObject("source").get("sourceSha").getAsString());
        assertFalse(report.get("performanceEligible").getAsBoolean());
        java.nio.file.Path fixture = java.nio.file.Path.of("build/agent-state/terrain-generation-java-fixture.json");
        java.nio.file.Files.createDirectories(fixture.getParent());
        java.nio.file.Files.writeString(fixture, new com.google.gson.GsonBuilder()
                .setPrettyPrinting().create().toJson(report) + "\n");
    }

    @Test
    void reportRejectsUnboundEvidenceIdentity() {
        assertThrows(IllegalArgumentException.class, () ->
                VanillaTerrainGenerationTelemetry.create("unknown", "trial", "passed", true, false, null));
        assertThrows(IllegalArgumentException.class, () ->
                VanillaTerrainGenerationTelemetry.create(SHA, "", "passed", true, false, null));
    }
}
