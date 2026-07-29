package com.metallum.client.metal.render;

import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.mojang.blaze3d.GpuFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector2f;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Objects;

/** Immutable handoff from scene producers to Temporal and frame interpolation. */
@Environment(EnvType.CLIENT)
record FrameSynthesisInputs(
        FrameStamp stamp,
        SceneColorInput sourceColor,
        FinalizedMotionFrame motion,
        ColorTextureRole sceneOutput,
        @Nullable ColorTextureRole frameGenerationColor,
        @Nullable ColorTextureRole uiColor,
        CameraFrameInput camera,
        boolean reset
) {
    FrameSynthesisInputs {
        Objects.requireNonNull(stamp, "stamp");
        Objects.requireNonNull(sourceColor, "sourceColor");
        Objects.requireNonNull(motion, "motion");
        Objects.requireNonNull(sceneOutput, "sceneOutput");
        Objects.requireNonNull(camera, "camera");
        if (!stamp.equals(motion.stamp())) {
            throw new IllegalArgumentException("Scene and finalized motion must have the same frame stamp");
        }
        if (reset != motion.reset()) {
            throw new IllegalArgumentException("Scene and finalized motion must agree on history reset");
        }
        if (sceneOutput.metalFxView().mtlPixelFormat()
                != sourceColor.color().metalFxView().mtlPixelFormat()) {
            throw new IllegalArgumentException("Temporal source and output view formats must match");
        }
        if (frameGenerationColor != null && !frameGenerationColor.isSrgb()) {
            throw new IllegalArgumentException("Frame Generation scene color requires a real sRGB view");
        }
        if (uiColor != null && !uiColor.isSrgb()) {
            throw new IllegalArgumentException("Frame Generation UI color requires a real sRGB view");
        }
        if (sourceColor.color().texture().getWidth(0) != motion.inputWidth()
                || sourceColor.color().texture().getHeight(0) != motion.inputHeight()) {
            throw new IllegalArgumentException("Temporal color and motion input dimensions must match");
        }
    }

    boolean canEncodeTemporal() {
        return sourceColor.validForTemporal() && motion.temporalEligible();
    }

    boolean canGenerateFrames() {
        return frameGenerationColor != null
                && frameGenerationColor.isSrgb()
                && uiColor != null
                && uiColor.isSrgb()
                && sceneOutput.isSrgb()
                && frameGenerationColor.metalFxView().mtlPixelFormat()
                == sceneOutput.metalFxView().mtlPixelFormat()
                && uiColor.metalFxView().mtlPixelFormat()
                == sceneOutput.metalFxView().mtlPixelFormat()
                && motion.frameGenerationEligible()
                && camera.validForFrameGeneration();
    }
}

/** A monotonically increasing frame identity paired with the current history generation. */
record FrameStamp(long frameId, long historyEpoch) {
    FrameStamp {
        if (frameId <= 0L || historyEpoch <= 0L) {
            throw new IllegalArgumentException("Frame id and history epoch must be positive");
        }
    }
}

enum ColorEncoding {
    FINAL_LDR_SRGB,
    SCENE_LINEAR
}

/** Exposure metadata is explicit; NONE never means a fabricated numeric exposure of one. */
record ExposureInput(Mode mode, @Nullable MetalGpuTexture texture, float preExposure) {
    enum Mode {
        NONE,
        AUTO,
        MANUAL_R16F
    }

    static final ExposureInput NONE = new ExposureInput(Mode.NONE, null, Float.NaN);
    static final ExposureInput AUTO = new ExposureInput(Mode.AUTO, null, Float.NaN);

    ExposureInput {
        Objects.requireNonNull(mode, "mode");
        if (mode == Mode.MANUAL_R16F) {
            if (texture == null || texture.isClosed() || texture.getFormat() != GpuFormat.R16_FLOAT
                    || texture.getWidth(0) != 1 || texture.getHeight(0) != 1
                    || texture.getDepthOrLayers() != 1
                    || texture.getMipLevels() != 1
                    || !(preExposure > 0.0F) || !Float.isFinite(preExposure)) {
                throw new IllegalArgumentException("Manual exposure needs an R16F texture and finite positive preExposure");
            }
        } else if (texture != null || !Float.isNaN(preExposure)) {
            throw new IllegalArgumentException("NONE and AUTO exposure do not carry a texture or preExposure");
        }
    }

    static ExposureInput manualR16f(final MetalGpuTexture texture, final float preExposure) {
        return new ExposureInput(Mode.MANUAL_R16F, texture, preExposure);
    }
}

/** One owned texture and the exact Metal view a color consumer must bind. */
record ColorTextureRole(MetalGpuTexture texture, MetalGpuTextureView metalFxView) {
    ColorTextureRole {
        Objects.requireNonNull(texture, "texture");
        Objects.requireNonNull(metalFxView, "metalFxView");
        if (texture.isClosed() || metalFxView.isClosed()) {
            throw new IllegalArgumentException("Color role cannot reference a closed texture or view");
        }
        if (metalFxView.texture() != texture) {
            throw new IllegalArgumentException("Color view must reference its role's base texture");
        }
    }

    boolean isSrgb() {
        return metalFxView.mtlPixelFormat() == MTLPixelFormat.RGBA8Unorm_sRGB
                || metalFxView.mtlPixelFormat() == MTLPixelFormat.BGRA8Unorm_sRGB;
    }
}

record SceneColorInput(
        ColorTextureRole color,
        ColorEncoding encoding,
        ExposureInput exposure
) {
    SceneColorInput {
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(encoding, "encoding");
        Objects.requireNonNull(exposure, "exposure");
        if (encoding == ColorEncoding.FINAL_LDR_SRGB
                && (!color.isSrgb() || exposure.mode() != ExposureInput.Mode.NONE)) {
            throw new IllegalArgumentException("Final-LDR input requires a real sRGB view and NONE exposure");
        }
        if (encoding == ColorEncoding.SCENE_LINEAR
                && (color.metalFxView().mtlPixelFormat() != MTLPixelFormat.RGBA16Float
                || exposure.mode() == ExposureInput.Mode.NONE)) {
            throw new IllegalArgumentException("Scene-linear input requires an RGBA16F view and explicit exposure");
        }
    }

    static SceneColorInput vanillaSrgb(final ColorTextureRole color) {
        return new SceneColorInput(color, ColorEncoding.FINAL_LDR_SRGB, ExposureInput.NONE);
    }

    boolean validForTemporal() {
        return encoding == ColorEncoding.FINAL_LDR_SRGB
                ? exposure.mode() == ExposureInput.Mode.NONE
                : exposure.mode() != ExposureInput.Mode.NONE;
    }

}

enum DepthConvention {
    REVERSED_Z
}

enum MotionConvention {
    PREVIOUS_MINUS_CURRENT_NDC_TOP_LEFT
}

enum ProducerCoverage {
    REAL_MOTION,
    REACTIVE_ONLY,
    UNSUPPORTED
}

enum ProducerDomain {
    CAMERA_DEPTH(true),
    DYNAMIC_CONTENT(true),
    FIRST_PERSON(true),
    TRANSPARENCY(true),
    PARTICLES_WEATHER(true),
    MODDED_RENDERERS(true);

    private final boolean mandatoryForFrameGeneration;

    ProducerDomain(final boolean mandatoryForFrameGeneration) {
        this.mandatoryForFrameGeneration = mandatoryForFrameGeneration;
    }

    boolean mandatoryForFrameGeneration() {
        return mandatoryForFrameGeneration;
    }
}

record ProducerReceipt(ProducerDomain domain, ProducerCoverage coverage, int samples) {
    ProducerReceipt {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(coverage, "coverage");
        if (samples < 0) {
            throw new IllegalArgumentException("Producer sample count must not be negative");
        }
    }
}

record FinalizedMotionFrame(
        FrameStamp stamp,
        MetalGpuTexture depth,
        MetalGpuTexture motion,
        MetalGpuTexture reactive,
        int inputWidth,
        int inputHeight,
        Vector2f jitterPixels,
        Vector2f motionScale,
        MotionConvention motionConvention,
        DepthConvention depthConvention,
        boolean reset,
        List<ProducerReceipt> producerReceipts
) {
    FinalizedMotionFrame {
        Objects.requireNonNull(stamp, "stamp");
        Objects.requireNonNull(depth, "depth");
        Objects.requireNonNull(motion, "motion");
        Objects.requireNonNull(reactive, "reactive");
        Objects.requireNonNull(jitterPixels, "jitterPixels");
        Objects.requireNonNull(motionScale, "motionScale");
        Objects.requireNonNull(motionConvention, "motionConvention");
        Objects.requireNonNull(depthConvention, "depthConvention");
        producerReceipts = List.copyOf(producerReceipts);
        jitterPixels = new Vector2f(jitterPixels);
        motionScale = new Vector2f(motionScale);
        if (inputWidth <= 0 || inputHeight <= 0) {
            throw new IllegalArgumentException("Motion dimensions must be positive");
        }
        if (depth.isClosed() || motion.isClosed() || reactive.isClosed()) {
            throw new IllegalArgumentException("Finalized motion cannot reference closed textures");
        }
        if (depth.getFormat() != GpuFormat.D32_FLOAT
                || motion.getFormat() != GpuFormat.RG16_FLOAT
                || reactive.getFormat() != GpuFormat.R8_UNORM) {
            throw new IllegalArgumentException("Finalized motion requires D32F depth, RG16F motion and R8 reactive");
        }
        if (depth.getWidth(0) != inputWidth || depth.getHeight(0) != inputHeight
                || motion.getWidth(0) != inputWidth || motion.getHeight(0) != inputHeight
                || reactive.getWidth(0) != inputWidth || reactive.getHeight(0) != inputHeight) {
            throw new IllegalArgumentException("Finalized motion texture dimensions must match the input dimensions");
        }
        if (depth.getDepthOrLayers() != 1 || motion.getDepthOrLayers() != 1
                || reactive.getDepthOrLayers() != 1
                || depth.getMipLevels() != 1 || motion.getMipLevels() != 1
                || reactive.getMipLevels() != 1) {
            throw new IllegalArgumentException("Finalized motion textures must be single-layer, single-mip 2D textures");
        }
        if (!Float.isFinite(jitterPixels.x) || !Float.isFinite(jitterPixels.y)) {
            throw new IllegalArgumentException("Motion jitter must be finite");
        }
        Vector2f expectedScale = MetalMotionContract.motionVectorScale(inputWidth, inputHeight);
        if (!motionScale.equals(expectedScale, 1.0E-6F)) {
            throw new IllegalArgumentException("Motion scale must be input size divided by two");
        }
        EnumMap<ProducerDomain, Integer> receiptCounts = new EnumMap<>(ProducerDomain.class);
        for (ProducerReceipt receipt : producerReceipts) {
            receiptCounts.merge(receipt.domain(), 1, Integer::sum);
        }
        EnumSet<ProducerDomain> missing = EnumSet.allOf(ProducerDomain.class);
        missing.removeAll(receiptCounts.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing producer receipts: " + missing);
        }
        receiptCounts.forEach((domain, count) -> {
            if (count != 1) {
                throw new IllegalArgumentException("Duplicate producer receipt for " + domain);
            }
        });
    }

    @Override
    public Vector2f jitterPixels() {
        return new Vector2f(jitterPixels);
    }

    @Override
    public Vector2f motionScale() {
        return new Vector2f(motionScale);
    }

    boolean temporalEligible() {
        return producerReceipts.stream().noneMatch(receipt -> receipt.coverage() == ProducerCoverage.UNSUPPORTED);
    }

    boolean frameGenerationEligible() {
        EnumSet<ProducerDomain> realMotion = EnumSet.noneOf(ProducerDomain.class);
        for (ProducerReceipt receipt : producerReceipts) {
            if (receipt.coverage() == ProducerCoverage.UNSUPPORTED) {
                return false;
            }
            if (receipt.coverage() == ProducerCoverage.REAL_MOTION) {
                realMotion.add(receipt.domain());
            }
        }
        // Camera/depth and dynamic geometry drive interpolation. First-person,
        // transparency and particles may use the explicit reactive fallback;
        // requiring REAL_MOTION for those always-present domains would make the
        // opt-in Frame Generation path permanently unreachable.
        return realMotion.contains(ProducerDomain.CAMERA_DEPTH)
                && realMotion.contains(ProducerDomain.DYNAMIC_CONTENT);
    }
}

record CameraFrameInput(
        float fieldOfViewDegrees,
        float nearPlane,
        float farPlane,
        float aspectRatio,
        float deltaSeconds
) {
    boolean validForFrameGeneration() {
        return fieldOfViewDegrees > 0.0F && fieldOfViewDegrees < 180.0F
                && nearPlane > 0.0F && farPlane > nearPlane
                && aspectRatio > 0.0F && deltaSeconds > 0.0F
                && Float.isFinite(fieldOfViewDegrees)
                && Float.isFinite(nearPlane)
                && Float.isFinite(farPlane)
                && Float.isFinite(aspectRatio)
                && Float.isFinite(deltaSeconds);
    }
}

record TemporalEncodeReceipt(FrameStamp stamp, ColorTextureRole output, long scalerToken) {
    TemporalEncodeReceipt {
        Objects.requireNonNull(stamp, "stamp");
        Objects.requireNonNull(output, "output");
        if (scalerToken <= 0L) {
            throw new IllegalArgumentException("A Temporal encode receipt requires a positive scaler token");
        }
    }
}
