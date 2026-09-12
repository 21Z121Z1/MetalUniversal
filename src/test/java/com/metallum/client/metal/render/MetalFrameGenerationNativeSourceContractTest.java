package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalFrameGenerationNativeSourceContractTest {
    @Test
    void motionDepthResampleStaysInTextureCoordinateOrientation() throws Exception {
        String nativeSource = Files.readString(Path.of("src/main/native/MetallumNative.swift"));
        int start = nativeSource.indexOf("private func buildMotionDepthResamplePipeline(");
        int end = nativeSource.indexOf("private func buildDepthResampleState", start);
        assertTrue(start >= 0 && end > start);

        String block = nativeSource.substring(start, end);
        assertTrue(block.contains("device.makeLibrary(source: copyMslSource(), options: nil)"));
        assertFalse(block.contains("device.makeLibrary(source: presentMslSource(), options: nil)"));
    }

    @Test
    void scalerReleaseRemovesTrackedHistoryBeforeDroppingCacheReferences() throws Exception {
        String nativeSource = Files.readString(Path.of("src/main/native/MetallumNative.swift"));
        int start = nativeSource.indexOf("@_cdecl(\"metallum_metalfx_release_scalers\")");
        int end = nativeSource.indexOf(
                "@_cdecl(\"metallum_metalfx_frame_generation_scaler_link_status\")",
                start
        );
        assertTrue(start >= 0 && end > start);

        String block = nativeSource.substring(start, end);
        int previousDepthRelease = block.indexOf(
                "for texture in NativeState.metalFxPreviousDepthTextures.values"
        );
        int previousDepthRemove = block.indexOf(
                "NativeState.metalFxPreviousDepthTextures.removeAll()"
        );
        int reactiveRelease = block.indexOf(
                "for texture in NativeState.metalFxValidationReactiveTextures.values"
        );
        int reactiveRemove = block.indexOf(
                "NativeState.metalFxValidationReactiveTextures.removeAll()"
        );

        assertTrue(previousDepthRelease >= 0 && previousDepthRelease < previousDepthRemove);
        assertTrue(reactiveRelease >= 0 && reactiveRelease < reactiveRemove);
        assertTrue(block.contains("residencyTrackReleased(texture)"));
    }

    @Test
    void frameGenerationDocumentIsNotBuildScriptPayload() throws Exception {
        String document = Files.readString(Path.of("docs/metalfx-frame-generation.md"));
        assertTrue(document.startsWith("# MetalFX Frame Generation"));
        assertFalse(document.startsWith("plugins {"));
    }
}
