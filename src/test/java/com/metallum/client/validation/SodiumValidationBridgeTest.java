package com.metallum.client.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class SodiumValidationBridgeTest {
    @Test
    void validationBridgeFailsClosedWhenSodiumIsAbsent() {
        assumeTrue(Boolean.getBoolean("metallum.test.noOptionalMods"));

        assertTrue(SodiumValidationBridge.terrainSettled());
        assertFalse(SodiumValidationBridge.enableFlawlessFrames("no-sodium-test"));
        assertDoesNotThrow(() -> SodiumValidationBridge.requestImportantRebuild(
                0, 0, 0, 1, 1, 1
        ));
    }
}
