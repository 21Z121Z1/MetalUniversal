package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalFxFirstPersonValidityContractTest {
    @Test
    void handPixelsUseDedicatedExactValidityAndKeepProductionGatesClosed() throws Exception {
        String manager = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalFxManager.java"));
        String encoder = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java"));
        String bridge = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java"));
        String synthetic = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalSyntheticExactMotion.java"));
        String nativeSource = Files.readString(Path.of("src/main/native/MetallumNative.swift"));

        assertTrue(manager.contains("MetalFX First-Person Exact Motion Validity R8"));
        assertTrue(manager.contains("replay.sample().domain() == FrameSynthesisContract.ProducerDomain.FIRST_PERSON"));
        assertTrue(manager.contains(".withColorAttachment(handExactValidityView)"));
        assertTrue(manager.contains("private static final boolean OBJECT_MOTION_PRODUCER_CONNECTED = false;"));
        assertTrue(manager.contains("motionEligibility.reject(MetalFxMotionEligibility.FIRST_PERSON);"));

        assertTrue(synthetic.contains("MetalFxManager.markExactFirstPersonProducerCandidate(sample);"));
        assertTrue(synthetic.contains("MetalFxManager.observeFirstPersonMotion();"));
        assertTrue(manager.contains("MetalEntityMotionCapture.recordMotionDrawSkip(MetalSyntheticExactMotion.PROJECTION_HISTORY_UNAVAILABLE);"));
        assertTrue(manager.contains("MetalPreviousVertexReplay.captureHandTransform(previousVertexToken, sourceInputs)"));
        assertTrue(manager.contains("MetalPreviousVertexReplay.handTransforms(replay.previousVertexToken())"));
        assertTrue(manager.contains("pass.setUniform(\"Projection\", replay.projection());"));

        assertTrue(encoder.contains("metallum_metalfx_encode_v3_available()"));
        assertTrue(bridge.contains("metallum_metalfx_encode_v3"));
        assertTrue(bridge.contains("metallum_metalfx_encode_hand_overlay_v2"));
        assertTrue(nativeSource.contains("handExactValidityTexture [[texture(9)]]"));
        assertTrue(nativeSource.contains("float handValid = handExactValidityTexture.read(pixel).r;"));
        assertTrue(nativeSource.contains("if (!exactHand)"));
        assertTrue(nativeSource.contains("texture2d<half, access::read_write> objectMotionTexture [[texture(1)]]"));
        assertTrue(nativeSource.contains("if (ownsExactMotion && !validExactMotion) reactive = 1.0;"));
        assertTrue(nativeSource.contains("selected = float2(0.0);"));
        assertTrue(nativeSource.contains("@_cdecl(\"metallum_metalfx_encode_v2\")"));
        assertTrue(nativeSource.contains("@_cdecl(\"metallum_metalfx_encode_v3\")"));
        assertFalse(nativeSource.contains("hand exact validity is inferred from objectValidity"));
    }
}
