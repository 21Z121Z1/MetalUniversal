package com.metallum.client.metal.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalTerrainIcbScopeTest {
    static {
        // Must be set before MetalTerrainIcbScope initializes its static gate.
        System.setProperty("metallum.opt.terrainIcbPilot", "true");
    }

    @BeforeAll
    static void confirmTestGate() {
        MetalTerrainIcbScope.resetForTest();
    }

    @AfterEach
    void reset() {
        MetalTerrainIcbScope.resetForTest();
    }

    @Test
    void nestedScopesRemainActiveUntilFinalExit() {
        assertFalse(MetalTerrainIcbScope.active());
        MetalTerrainIcbScope.enter();
        MetalTerrainIcbScope.enter();
        assertTrue(MetalTerrainIcbScope.active());
        assertEquals(2, MetalTerrainIcbScope.depthForTest());

        MetalTerrainIcbScope.exit();
        assertTrue(MetalTerrainIcbScope.active());
        assertEquals(1, MetalTerrainIcbScope.depthForTest());

        MetalTerrainIcbScope.exit();
        assertFalse(MetalTerrainIcbScope.active());
        assertEquals(0, MetalTerrainIcbScope.depthForTest());
    }

    @Test
    void underflowFailsLoudly() {
        assertThrows(IllegalStateException.class, MetalTerrainIcbScope::exit);
    }
}
