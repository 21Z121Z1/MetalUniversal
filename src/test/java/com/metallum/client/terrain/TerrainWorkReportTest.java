package com.metallum.client.terrain;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerrainWorkReportTest {
    private static final String SHA = "1234567890123456789012345678901234567890";

    @TempDir
    Path directory;

    @Test
    void independentOracleAcceptsRepeatedContentVersionsAndEmptyRetirement() throws Exception {
        var tracker = new VanillaTerrainWorkTracker(new TerrainWorkEventRecorder(128));
        Object previous = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            Object region = new Object();
            Object mesh = new Object();
            tracker.beginWork(-1234567890123456789L, region, true, System.nanoTime());
            assertTrue(tracker.beginBuild(region, System.nanoTime()));
            tracker.endBuild(System.nanoTime());
            tracker.bindConstructedMesh(mesh);
            var token = tracker.publish(-1234567890123456789L, mesh, System.nanoTime(), "ready");
            assertNotNull(token);
            tracker.firstValidDraw(token, attempt, System.nanoTime());
            if (previous != null) {
                tracker.retireMesh(previous, System.nanoTime(), "replaced");
            }
            previous = mesh;
        }

        Object emptyRegion = new Object();
        tracker.beginWork(9L, emptyRegion, false, System.nanoTime());
        assertTrue(tracker.beginBuild(emptyRegion, System.nanoTime()));
        tracker.endBuild(System.nanoTime());
        assertNotNull(tracker.publishEmpty(9L, System.nanoTime(), "empty"));
        tracker.invalidateSection(9L, System.nanoTime(), "reset");

        var reports = TerrainWorkReport.create(
                tracker.snapshot(),
                SHA,
                "runtime-oracle-test",
                1L,
                true,
                null
        );
        JsonObject report = reports.get(1L);
        assertEquals("observation", report.get("lossScope").getAsString());
        JsonObject verdict = check(report);
        assertTrue(verdict.get("performanceEligible").getAsBoolean(), verdict.toString());
        assertEquals(3, verdict.getAsJsonObject("summary").get("observedWorkItems").getAsInt());
        assertEquals(2, verdict.getAsJsonObject("summary").get("latencySampleCount").getAsInt());
        assertEquals(1, verdict.getAsJsonObject("summary").get("noDrawRequiredItems").getAsInt());
    }

    @Test
    void epochSplittingLabelsObservationWideLossAndNeverMergesWorlds() throws Exception {
        var tracker = new VanillaTerrainWorkTracker(new TerrainWorkEventRecorder(16), 1);
        tracker.beginWork(1L, new Object(), true, System.nanoTime());
        tracker.beginWork(2L, new Object(), true, System.nanoTime());
        tracker.advanceWorldEpoch(System.nanoTime());
        tracker.beginWork(1L, new Object(), true, System.nanoTime());

        var reports = TerrainWorkReport.create(tracker.snapshot(), SHA, "epochs", 2L, true, null);
        assertEquals(2, reports.size());
        for (var report : reports.values()) {
            assertEquals("observation", report.get("lossScope").getAsString());
            JsonObject verdict = check(report);
            assertFalse(verdict.get("performanceEligible").getAsBoolean());
            assertTrue(report.get("overflowed").getAsBoolean());
        }
    }

    @Test
    void refusesUnknownIdentityAndPreservesFailedTrial() throws Exception {
        var snapshot = new TerrainWorkEventRecorder().snapshot();
        assertThrows(
                IllegalArgumentException.class,
                () -> TerrainWorkReport.create(snapshot, "unknown", "test", 1L, true, null)
        );
        var reports = TerrainWorkReport.create(snapshot, SHA, "failed", 1L, false, "client-failed");
        assertEquals("valid-failed-trial", check(reports.get(1L)).get("state").getAsString());
    }

    private JsonObject check(final JsonObject report) throws Exception {
        Path input = directory.resolve("report.json");
        Path output = directory.resolve("verdict.json");
        Path log = directory.resolve("checker.log");
        Files.writeString(input, new GsonBuilder().serializeNulls().create().toJson(report));
        Process child = new ProcessBuilder(
                "python3",
                "scripts/agent/verify_terrain_work_events.py",
                input.toString(),
                "--output",
                output.toString()
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "oracle timed out");
            assertEquals(0, child.exitValue(), Files.readString(log));
            return JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        }
    }
}
