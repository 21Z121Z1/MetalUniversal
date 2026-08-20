package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalNativeBridgeCriticalPolicyTest {
    private static final Path BRIDGE = Path.of(
            "src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java"
    );

    @Test
    void synchronousCompilerBoundariesAreNeverCriticalDowncalls() throws IOException {
        String source = Files.readString(BRIDGE);

        assertTrue(source.contains(
                "initPipelines = downcallWithoutCritical(lookup, \"metallum_init_pipelines\""
        ));
        assertTrue(source.contains(
                "MTLDeviceMakeRenderPipelineState = downcallWithoutCritical("
        ));
        assertTrue(source.contains(
                "MTLDeviceMakeComputePipelineState = optionalDowncallWithoutCritical("
        ));
        assertTrue(source.contains(
                "private static MethodHandle optionalDowncallWithoutCritical("
        ));

        assertFalse(source.contains(
                "MTLDeviceMakeRenderPipelineState = downcall("
        ));
        assertFalse(source.contains(
                "MTLDeviceMakeComputePipelineState = optionalDowncall("
        ));
    }

    @Test
    void shortEncoderStateCallsKeepTheCriticalFastPath() throws IOException {
        String source = Files.readString(BRIDGE);

        assertTrue(source.contains(
                "MTLRenderCommandEncoderSetRenderPipelineState = downcall("
        ));
        assertTrue(source.contains(
                "MTLComputeCommandEncoderSetComputePipelineState = optionalDowncall("
        ));
    }
}
