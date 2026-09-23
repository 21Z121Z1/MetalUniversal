package com.metallum.client.validation;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class SodiumValidationBridgeTest {
    @Test
    void productionValidationEntrypointHasNoSodiumSymbolicReference() throws Exception {
        try (InputStream stream = MetalValidationClient.class.getResourceAsStream(
                "/com/metallum/client/validation/MetalValidationClient.class"
        )) {
            assertTrue(stream != null, "compiled MetalValidationClient class is missing");
            String classBytes = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(
                    classBytes.contains("net/caffeinemc/mods/sodium"),
                    "MetalValidationClient must cross the optional Sodium boundary only through SodiumValidationBridge"
            );
        }
    }

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
