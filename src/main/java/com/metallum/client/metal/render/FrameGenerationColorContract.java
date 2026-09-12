package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fail-closed evidence model for the color pipeline consumed by MetalFX Frame Interpolation.
 *
 * <p>Pixel storage is intentionally not treated as a transfer-function proof. In particular,
 * {@link GpuFormat#RGBA8_UNORM} maps to a non-sRGB Metal pixel format in the current backend, so
 * seeing that storage format says nothing about whether the numeric values are linear or
 * display-referred. Every semantic stage which crosses the Temporal -> Frame Interpolation -> UI
 * -> drawable boundary must therefore be proven independently.</p>
 */
@Environment(EnvType.CLIENT)
final class FrameGenerationColorContract {
    enum SourcePath {
        TEMPORAL_OUTPUT,
        NATIVE_DIRECT
    }

    enum TemporalEncoding {
        /** MetalFX Temporal's documented input/output semantic contract. */
        LINEAR,
        NOT_APPLICABLE,
        UNPROVEN
    }

    /** Numeric meaning of the renderer's scene texture before MetalFX consumes it. */
    enum SceneEncoding {
        LINEAR,
        DISPLAY_REFERRED_SRGB,
        UNPROVEN
    }

    enum ToneMapPlacement {
        AFTER_TEMPORAL_BEFORE_FRAME_INTERPOLATION,
        BEFORE_NATIVE_DIRECT_FRAME_INTERPOLATION,
        UNPROVEN
    }

    enum FrameInterpolationEncoding {
        DISPLAY_REFERRED_SRGB,
        UNPROVEN
    }

    enum UiAlphaEncoding {
        PREMULTIPLIED,
        UNPROVEN
    }

    enum DrawableEncoding {
        EXPLICIT_SRGB,
        UNTAGGED_BGRA8_UNORM,
        UNPROVEN
    }

    record Evidence(
            SourcePath sourcePath,
            GpuFormat sceneStorage,
            GpuFormat uiStorage,
            SceneEncoding sceneEncoding,
            TemporalEncoding temporalEncoding,
            ToneMapPlacement toneMapPlacement,
            FrameInterpolationEncoding frameInterpolationEncoding,
            UiAlphaEncoding uiAlphaEncoding,
            DrawableEncoding drawableEncoding
    ) {
        Evidence {
            Objects.requireNonNull(sourcePath, "sourcePath");
            Objects.requireNonNull(sceneStorage, "sceneStorage");
            Objects.requireNonNull(uiStorage, "uiStorage");
            Objects.requireNonNull(sceneEncoding, "sceneEncoding");
            Objects.requireNonNull(temporalEncoding, "temporalEncoding");
            Objects.requireNonNull(toneMapPlacement, "toneMapPlacement");
            Objects.requireNonNull(frameInterpolationEncoding, "frameInterpolationEncoding");
            Objects.requireNonNull(uiAlphaEncoding, "uiAlphaEncoding");
            Objects.requireNonNull(drawableEncoding, "drawableEncoding");
        }

        boolean productionProven() {
            if (sceneStorage != GpuFormat.RGBA8_UNORM || uiStorage != GpuFormat.RGBA8_UNORM) {
                return false;
            }
            boolean temporalStageProven = switch (sourcePath) {
                case TEMPORAL_OUTPUT -> sceneEncoding == SceneEncoding.LINEAR
                        && temporalEncoding == TemporalEncoding.LINEAR
                        && toneMapPlacement
                        == ToneMapPlacement.AFTER_TEMPORAL_BEFORE_FRAME_INTERPOLATION;
                case NATIVE_DIRECT -> sceneEncoding == SceneEncoding.DISPLAY_REFERRED_SRGB
                        && temporalEncoding == TemporalEncoding.NOT_APPLICABLE
                        && toneMapPlacement
                        == ToneMapPlacement.BEFORE_NATIVE_DIRECT_FRAME_INTERPOLATION;
            };
            return temporalStageProven
                    && frameInterpolationEncoding
                    == FrameInterpolationEncoding.DISPLAY_REFERRED_SRGB
                    && uiAlphaEncoding == UiAlphaEncoding.PREMULTIPLIED
                    && drawableEncoding == DrawableEncoding.EXPLICIT_SRGB;
        }

        FrameSynthesisContract.ColorEncodingEvidence admissionEvidence(
                final boolean diagnosticAssumption
        ) {
            if (productionProven()) {
                return FrameSynthesisContract.ColorEncodingEvidence
                        .LINEAR_TEMPORAL_POST_TONEMAP_FG_PREMULTIPLIED_UI;
            }
            return diagnosticAssumption
                    ? FrameSynthesisContract.ColorEncodingEvidence
                    .DIAGNOSTIC_UNPROVEN_RGBA8_UNORM_SRGB_VIEW
                    : FrameSynthesisContract.ColorEncodingEvidence
                    .UNPROVEN_RGBA8_UNORM_SRGB_VIEW;
        }

        List<String> missingProofs() {
            List<String> missing = new ArrayList<>();
            if (sceneStorage != GpuFormat.RGBA8_UNORM) {
                missing.add("scene-storage");
            }
            if (uiStorage != GpuFormat.RGBA8_UNORM) {
                missing.add("ui-storage");
            }
            if (sourcePath == SourcePath.TEMPORAL_OUTPUT) {
                if (sceneEncoding != SceneEncoding.LINEAR) {
                    missing.add("temporal-input-linearization");
                }
                if (temporalEncoding != TemporalEncoding.LINEAR) {
                    missing.add("temporal-linear");
                }
                if (toneMapPlacement
                        != ToneMapPlacement.AFTER_TEMPORAL_BEFORE_FRAME_INTERPOLATION) {
                    missing.add("post-temporal-tone-map");
                }
            } else {
                if (sceneEncoding != SceneEncoding.DISPLAY_REFERRED_SRGB) {
                    missing.add("native-direct-display-referred-scene");
                }
                if (temporalEncoding != TemporalEncoding.NOT_APPLICABLE) {
                    missing.add("native-direct-temporal-n/a");
                }
                if (toneMapPlacement
                        != ToneMapPlacement.BEFORE_NATIVE_DIRECT_FRAME_INTERPOLATION) {
                    missing.add("native-direct-tone-map");
                }
            }
            if (frameInterpolationEncoding
                    != FrameInterpolationEncoding.DISPLAY_REFERRED_SRGB) {
                missing.add("frame-interpolation-transfer");
            }
            if (uiAlphaEncoding != UiAlphaEncoding.PREMULTIPLIED) {
                missing.add("ui-premultiplied-alpha");
            }
            if (drawableEncoding != DrawableEncoding.EXPLICIT_SRGB) {
                missing.add("drawable-srgb-colorspace");
            }
            return List.copyOf(missing);
        }
    }

    private FrameGenerationColorContract() {
    }

    /**
     * Evidence for the renderer as it exists today.
     *
     * <p>The Temporal API has a documented linear semantic, but the current Minecraft scene
     * target is a plain non-sRGB UNORM texture carrying already tone-mapped/display-referred
     * values. There is no explicit display-referred -> linear conversion before Temporal, nor an
     * explicit post-Temporal tone-map/transfer pass before {@code sceneOutputTarget} is copied into
     * the Frame Interpolator ring. The native-direct path likewise has no machine-verifiable
     * transfer attestation. The UI target is RGBA8_UNORM, but the complete set of GUI/overlay blend modes
     * has not yet been proven to preserve a premultiplied-alpha invariant. Finally, the
     * CAMetalLayer uses BGRA8Unorm without an explicit sRGB color-space tag. Those unknowns are
     * deliberately represented rather than inferred from storage formats.</p>
     */
    static Evidence currentRenderer(final SourcePath sourcePath) {
        return new Evidence(
                sourcePath,
                GpuFormat.RGBA8_UNORM,
                GpuFormat.RGBA8_UNORM,
                SceneEncoding.DISPLAY_REFERRED_SRGB,
                sourcePath == SourcePath.TEMPORAL_OUTPUT
                        ? TemporalEncoding.LINEAR
                        : TemporalEncoding.NOT_APPLICABLE,
                ToneMapPlacement.UNPROVEN,
                FrameInterpolationEncoding.UNPROVEN,
                UiAlphaEncoding.UNPROVEN,
                DrawableEncoding.UNTAGGED_BGRA8_UNORM
        );
    }
}
