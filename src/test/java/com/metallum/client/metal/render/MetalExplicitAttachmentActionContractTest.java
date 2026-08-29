package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalExplicitAttachmentActionContractTest {
    @Test
    void privateActionsRequireV3AndDefaultPathRemainsAutomatic() throws Exception {
        String encoder = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java"));
        String pass = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalRenderPass.java"));
        assertTrue(encoder.contains("label, null, null"));
        assertTrue(encoder.contains("Explicit color attachment actions require render-pass ABI V3"));
        assertTrue(encoder.contains("requestedLoad >= 0"));
        assertTrue(encoder.contains("requestedStore >= 0"));
        assertTrue(encoder.contains("colorStoreActions[index] = requestedStore >= 0"));
        assertTrue(pass.contains("explicitColorActionsConsumed"));
        assertTrue(pass.contains("cannot reopen its native encoder"));
    }
}
