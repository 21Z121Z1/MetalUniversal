package com.metallum.client.terrain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class VanillaTerrainUploadPressureTest {
    private static final String SHA = "1234567890123456789012345678901234567890";

    @Test
    void reportCannotPromoteCpuDiagnosticToGpuOrPerformanceEvidence() {
        var report = VanillaTerrainUploadPressure.report(SHA, "upload-probe", "passed");
        assertEquals(SHA, report.getAsJsonObject("source").get("sourceSha").getAsString());
        assertEquals("process-observation-including-warmup", report.get("scope").getAsString());
        assertFalse(report.get("performanceEligible").getAsBoolean());
        assertTrue(report.getAsJsonObject("measurementLimits").get("gpuCompletion")
                .getAsString().startsWith("unavailable:"));
        assertTrue(report.getAsJsonObject("measurementLimits").get("inFlightBytes")
                .getAsString().startsWith("unavailable:"));
    }

    @Test
    void rejectsUnboundReport() {
        assertThrows(IllegalArgumentException.class,
                () -> VanillaTerrainUploadPressure.report("unknown", "trial", "passed"));
        assertThrows(IllegalArgumentException.class,
                () -> VanillaTerrainUploadPressure.report(SHA, "", "passed"));
        assertThrows(IllegalArgumentException.class,
                () -> VanillaTerrainUploadPressure.report(SHA, "trial", ""));
    }
}
