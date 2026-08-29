package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalArgumentTableAuthorityContractTest {
    @Test
    void performanceLaneDoesNotEnableTheLegacySnapshotMirror() throws IOException {
        String runtime = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/IrisMetalArgumentBindingRuntime.java"
        ));
        assertTrue(runtime.contains("metallum.iris.argumentSnapshotDiagnostics"));
        assertTrue(runtime.contains("private static boolean enabled() {\n        return SNAPSHOT_DIAGNOSTICS;"));
        assertFalse(runtime.contains("return IrisMetalOptimizationPlan.ENABLE_ARGUMENT_TABLES"));
        assertFalse(runtime.contains("|| IrisMetalAdvancedOptimizationConfig.ARGUMENT_TABLES"));
    }

    @Test
    void metal4ExecutionBindsOneTablePerStageAndPatchesItThroughTheRealEncoder() throws IOException {
        String nativeSource = Files.readString(Path.of("src/main/native/MetallumNative.swift"));
        assertTrue(nativeSource.contains("private let vertexArguments: MTL4ArgumentTable"));
        assertTrue(nativeSource.contains("private let fragmentArguments: MTL4ArgumentTable"));
        assertTrue(nativeSource.contains(
                "encoder.setArgumentTable(vertexArguments, stages: MTLRenderStages.vertex)"
        ));
        assertTrue(nativeSource.contains(
                "encoder.setArgumentTable(fragmentArguments, stages: MTLRenderStages.fragment)"
        ));
        // MTL4ArgumentTable buffer bindings are GPU addresses, not MTLBuffer object setters.
        assertTrue(nativeSource.contains("vertexArguments.setAddress("));
        assertTrue(nativeSource.contains("fragmentArguments.setAddress("));
        assertTrue(nativeSource.contains("vertexArguments.setTexture("));
        assertTrue(nativeSource.contains("fragmentArguments.setTexture("));
    }

    @Test
    void javaExecutionAlreadyBatchesAdmittedStateAcrossOneFfmPacket() throws IOException {
        String packet = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/mtl/MetalRenderStatePacket.java"
        ));
        String encoder = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/mtl/MTLRenderCommandEncoder.java"
        ));
        assertTrue(packet.contains("MetalRenderStatePacketBridge.apply(encoder, this.storage, byteCount)"));
        assertTrue(encoder.contains("MetalRenderStatePacket.createIfAvailable()"));
        assertTrue(encoder.contains("statePacket.appendBuffer("));
        assertTrue(encoder.contains("statePacket.appendTextureAndSampler("));
    }
}
