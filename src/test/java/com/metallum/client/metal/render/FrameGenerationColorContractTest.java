package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FrameGenerationColorContractTest {
    @Test
    void currentTemporalPathRemainsProductionFailClosed() {
        FrameGenerationColorContract.Evidence evidence =
                FrameGenerationColorContract.currentRenderer(
                        FrameGenerationColorContract.SourcePath.TEMPORAL_OUTPUT
                );

        assertFalse(evidence.productionProven());
        assertEquals(
                FrameSynthesisContract.ColorEncodingEvidence.UNPROVEN_RGBA8_UNORM_SRGB_VIEW,
                evidence.admissionEvidence(false)
        );
        assertEquals(
                FrameSynthesisContract.ColorEncodingEvidence
                        .DIAGNOSTIC_UNPROVEN_RGBA8_UNORM_SRGB_VIEW,
                evidence.admissionEvidence(true)
        );
        assertTrue(evidence.missingProofs().containsAll(List.of(
                "temporal-input-linearization",
                "post-temporal-tone-map",
                "frame-interpolation-transfer",
                "ui-premultiplied-alpha",
                "drawable-srgb-colorspace"
        )));
    }

    @Test
    void currentNativeDirectPathRemainsProductionFailClosed() {
        FrameGenerationColorContract.Evidence evidence =
                FrameGenerationColorContract.currentRenderer(
                        FrameGenerationColorContract.SourcePath.NATIVE_DIRECT
                );

        assertFalse(evidence.productionProven());
        assertTrue(evidence.missingProofs().contains("native-direct-tone-map"));
        assertTrue(evidence.missingProofs().contains("frame-interpolation-transfer"));
    }

    @Test
    void rgba8StorageAloneNeverProvesTransferFunction() {
        FrameGenerationColorContract.Evidence evidence = new FrameGenerationColorContract.Evidence(
                FrameGenerationColorContract.SourcePath.TEMPORAL_OUTPUT,
                GpuFormat.RGBA8_UNORM,
                GpuFormat.RGBA8_UNORM,
                FrameGenerationColorContract.SceneEncoding.DISPLAY_REFERRED_SRGB,
                FrameGenerationColorContract.TemporalEncoding.LINEAR,
                FrameGenerationColorContract.ToneMapPlacement.UNPROVEN,
                FrameGenerationColorContract.FrameInterpolationEncoding.UNPROVEN,
                FrameGenerationColorContract.UiAlphaEncoding.UNPROVEN,
                FrameGenerationColorContract.DrawableEncoding.UNTAGGED_BGRA8_UNORM
        );

        assertFalse(evidence.productionProven());
        assertEquals(
                FrameSynthesisContract.ColorEncodingEvidence.UNPROVEN_RGBA8_UNORM_SRGB_VIEW,
                evidence.admissionEvidence(false)
        );
    }

    @Test
    void onlyCompleteStageEvidenceCanProduceShippingColorEvidence() {
        FrameGenerationColorContract.Evidence evidence = new FrameGenerationColorContract.Evidence(
                FrameGenerationColorContract.SourcePath.TEMPORAL_OUTPUT,
                GpuFormat.RGBA8_UNORM,
                GpuFormat.RGBA8_UNORM,
                FrameGenerationColorContract.SceneEncoding.LINEAR,
                FrameGenerationColorContract.TemporalEncoding.LINEAR,
                FrameGenerationColorContract.ToneMapPlacement
                        .AFTER_TEMPORAL_BEFORE_FRAME_INTERPOLATION,
                FrameGenerationColorContract.FrameInterpolationEncoding.DISPLAY_REFERRED_SRGB,
                FrameGenerationColorContract.UiAlphaEncoding.PREMULTIPLIED,
                FrameGenerationColorContract.DrawableEncoding.EXPLICIT_SRGB
        );

        assertTrue(evidence.productionProven());
        assertTrue(evidence.missingProofs().isEmpty());
        assertEquals(
                FrameSynthesisContract.ColorEncodingEvidence
                        .LINEAR_TEMPORAL_POST_TONEMAP_FG_PREMULTIPLIED_UI,
                evidence.admissionEvidence(false)
        );
    }
}
