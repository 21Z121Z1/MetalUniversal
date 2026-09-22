package com.metallum.e2e;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StationaryTerrainTest {
    private JsonObject state(String identity) {
        var result = new JsonObject();
        result.addProperty("visibleDrawSha256", identity);
        return result;
    }
    @Test void pendingWorkOrChangedDrawsRestartConvergenceAndTimeCannotReplaceChecks() {
        var gate = new StationaryTerrain();
        for (int i = 0; i < 39; i++) assertFalse(gate.observe(state("a"), i * 100_000_000L));
        assertFalse(gate.observe(null, 4_000_000_000L));
        assertFalse(gate.observe(state("a"), 5_000_000_000L));
        assertFalse(gate.observe(state("b"), 20_000_000_000L));
        for (int i = 1; i < 39; i++) assertFalse(gate.observe(state("b"), 20_000_000_000L + i * 100_000_000L));
        assertTrue(gate.observe(state("b"), 24_000_000_000L));
        assertFalse(gate.observe(state("c"), 25_000_000_000L));
    }
    @Test void fastPollingCannotReplaceTwoSecondsOfStability() {
        var gate = new StationaryTerrain();
        for (int i = 0; i < 100; i++) assertFalse(gate.observe(state("a"), i));
        assertTrue(gate.observe(state("a"), 2_000_000_000L));
    }

    @Test void inFlightSectionTaskCannotPassScheduledWorkGate() {
        var idle = StationaryTerrain.scheduledSectionWorkEvidence(0, 4, 4);
        assertTrue(idle.get("scheduledSectionWorkComplete").getAsBoolean());
        assertEquals(0, idle.get("tasksInFlight").getAsInt());

        var running = StationaryTerrain.scheduledSectionWorkEvidence(0, 4, 3);
        assertFalse(running.get("scheduledSectionWorkComplete").getAsBoolean());
        assertEquals(1, running.get("tasksInFlight").getAsInt());

        assertFalse(StationaryTerrain.scheduledSectionWorkEvidence(1, 4, 4)
                .get("scheduledSectionWorkComplete").getAsBoolean());
        assertFalse(StationaryTerrain.scheduledSectionWorkEvidence(0, 0, 0)
                .get("scheduledSectionWorkComplete").getAsBoolean());
    }
}
