package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.GpuFormat;
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
                "post-temporal-tone-map",
                "ui-premultiplied-alpha"
        )));
        assertTrue(evidence.missingProofs().contains("temporal-input-linearization"));
        assertTrue(evidence.missingProofs().contains("frame-interpolation-transfer"));
        assertEquals(
                FrameGenerationColorContract.DrawableEncoding.EXPLICIT_SRGB,
                evidence.drawableEncoding()
        );
    }

    @Test
    void transferProofRequiresThisSourceReceiptAndStillDoesNotProveHdrToneMapOrUi() {
        var path = FrameGenerationColorContract.SourcePath.TEMPORAL_OUTPUT;
        var proof = FrameGenerationColorContract.withSdrTransferReceipt(path, true);
        assertFalse(proof.missingProofs().contains("temporal-input-linearization"));
        assertFalse(proof.missingProofs().contains("frame-interpolation-transfer"));
        assertTrue(proof.missingProofs().containsAll(List.of("post-temporal-tone-map", "ui-premultiplied-alpha")));
        assertFalse(proof.productionProven());
        assertEquals(FrameGenerationColorContract.currentRenderer(path),
                FrameGenerationColorContract.withSdrTransferReceipt(path, false));
        var direct = FrameGenerationColorContract.SourcePath.NATIVE_DIRECT;
        assertEquals(FrameGenerationColorContract.currentRenderer(direct),
                FrameGenerationColorContract.withSdrTransferReceipt(direct, true));
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
