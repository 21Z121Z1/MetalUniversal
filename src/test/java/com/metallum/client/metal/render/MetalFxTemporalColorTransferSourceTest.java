package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalFxTemporalColorTransferSourceTest {
    private static String nativeSource() throws Exception {
        return Files.readString(Path.of("src/main/native/MetallumNative.swift"));
    }

    @Test
    void transferUsesCanonicalSrgbBreakpointsAndPreservesAlpha() throws Exception {
        String source = nativeSource();
        assertTrue(source.contains("x <= 0.04045f"));
        assertTrue(source.contains("x / 12.92f"));
        assertTrue(source.contains("(x + 0.055f) / 1.055f"));
        assertTrue(source.contains("2.4f"));
        assertTrue(source.contains("x <= 0.0031308f"));
        assertTrue(source.contains("x * 12.92f"));
        assertTrue(source.contains("1.055f * pow(x, 1.0f / 2.4f) - 0.055f"));
        assertTrue(source.contains("destinationTexture.write(float4(rgb, sample.a), pixel)"));
    }

    @Test
    void transferIsExplicitAndImplementedForBothCommandBufferBackends() throws Exception {
        String source = nativeSource();
        assertTrue(source.contains("@_cdecl(\"metallum_metalfx_color_transfer\")"));
        assertTrue(source.contains("private func metal3MetalFxColorTransfer("));
        assertTrue(source.contains("let lease = metal4MainLease(commandBufferPointer)"));
        assertTrue(source.contains("encodeMetal4Compute("));
        assertTrue(source.contains("static var colorTransferPipeline: MTLComputePipelineState?"));
        assertTrue(source.contains("residencyTrackReleased(NativeState.colorTransferPipeline)"));
    }

    @Test
    void presenterDoesNotRequireSceneAndUiToSharePixelFormat() throws Exception {
        String source = nativeSource();
        assertTrue(source.contains("private var uiFormat: MTLPixelFormat"));
        assertTrue(source.contains("pixelFormat: uiFormat"));
        assertTrue(source.contains("uiColor.pixelFormat != uiFormat"));
        assertTrue(source.contains("sceneColor.pixelFormat == nativeSceneColor.pixelFormat"));
        assertFalse(source.contains("sceneColor.pixelFormat == uiColor.pixelFormat"));
        assertFalse(source.contains("nativeSceneColor.pixelFormat == uiColor.pixelFormat"));
    }
}
