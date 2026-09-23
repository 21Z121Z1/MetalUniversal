package com.metallum.client.terrain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class VanillaTerrainAdmissionReportTest {
    private static final String SHA = "1234567890123456789012345678901234567890";

    @AfterEach
    void reset() {
        VanillaTerrainAdmissionTelemetry.resetForTest();
    }

    @Test
    void bindsDiagnosticEvidenceToExactSourceAndNeverSelfPromotes() {
        var report = VanillaTerrainAdmissionReport.create(SHA, "t1a-fixture", "passed");
        assertEquals(1, report.get("schemaVersion").getAsInt());
        assertEquals("diagnostic", report.get("evidenceClass").getAsString());
        assertFalse(report.get("performanceEligible").getAsBoolean());
        assertEquals(SHA, report.getAsJsonObject("source").get("sourceSha").getAsString());
        assertEquals("26.3", report.getAsJsonObject("source").get("minecraftVersion").getAsString());
        assertEquals("passed", report.get("validationStatus").getAsString());
    }

    @Test
    void rejectsUnknownSourceIdentity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> VanillaTerrainAdmissionReport.create("unknown", "trial", "passed")
        );
    }
}
