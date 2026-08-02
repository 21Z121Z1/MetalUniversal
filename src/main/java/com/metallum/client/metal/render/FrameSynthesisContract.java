package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector2f;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Fail-closed contracts shared by Temporal and Frame Generation producers.
 *
 * <p>This class intentionally contains no global state and performs no native
 * calls. The current MetalFX manager can adopt the records incrementally
 * without replacing its render graph or presenter in one large merge.</p>
 */
@Environment(EnvType.CLIENT)
final class FrameSynthesisContract {
    private FrameSynthesisContract() {
    }

    /** Monotonic source-frame identity scoped to one history generation. */
    record FrameStamp(long frameId, long historyEpoch) {
        FrameStamp {
            if (frameId <= 0L || historyEpoch <= 0L) {
                throw new IllegalArgumentException("Frame id and history epoch must be positive");
            }
        }
    }

    enum ProducerCoverage {
        REAL_MOTION,
        REACTIVE_ONLY,
        UNSUPPORTED
    }

    enum ProducerDomain {
        CAMERA_DEPTH,
        DYNAMIC_CONTENT,
        FIRST_PERSON,
        TRANSPARENCY,
        PARTICLES_WEATHER,
        MODDED_RENDERERS
    }

    record ProducerReceipt(ProducerDomain domain, ProducerCoverage coverage, int samples) {
        ProducerReceipt {
            Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(coverage, "coverage");
            if (samples < 0) {
                throw new IllegalArgumentException("Producer sample count must not be negative");
            }
            if (coverage == ProducerCoverage.REAL_MOTION && samples == 0) {
                throw new IllegalArgumentException("Real-motion coverage requires at least one sample");
            }
        }
    }

    /** Exactly one receipt for every observable producer domain. */
    record ProducerCoverageSet(List<ProducerReceipt> receipts) {
        ProducerCoverageSet {
            receipts = List.copyOf(Objects.requireNonNull(receipts, "receipts"));
            EnumMap<ProducerDomain, Integer> counts = new EnumMap<>(ProducerDomain.class);
            for (ProducerReceipt receipt : receipts) {
                counts.merge(receipt.domain(), 1, Integer::sum);
            }
            EnumSet<ProducerDomain> missing = EnumSet.allOf(ProducerDomain.class);
            missing.removeAll(counts.keySet());
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("Missing producer receipts: " + missing);
            }
            counts.forEach((domain, count) -> {
                if (count != 1) {
                    throw new IllegalArgumentException("Duplicate producer receipt for " + domain);
                }
            });
        }

        boolean temporalEligible() {
            return receipts.stream().noneMatch(
                    receipt -> receipt.coverage() == ProducerCoverage.UNSUPPORTED
            );
        }

        boolean frameGenerationEligible() {
            if (!temporalEligible()) {
                return false;
            }
            EnumSet<ProducerDomain> realMotion = EnumSet.noneOf(ProducerDomain.class);
            for (ProducerReceipt receipt : receipts) {
                if (receipt.coverage() == ProducerCoverage.REAL_MOTION) {
                    realMotion.add(receipt.domain());
                }
            }
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
        CameraFrameInput {
            if (!valid()) {
                throw new IllegalArgumentException("Invalid camera input for Frame Generation");
            }
        }

        private boolean valid() {
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

    record FinalizedMotionFrame(
            FrameStamp stamp,
            MetalGpuTexture depth,
            MetalGpuTexture motion,
            MetalGpuTexture reactive,
            int inputWidth,
            int inputHeight,
            Vector2f jitterPixels,
            Vector2f motionScale,
            boolean reset,
            ProducerCoverageSet producerCoverage
    ) {
        FinalizedMotionFrame {
            Objects.requireNonNull(stamp, "stamp");
            Objects.requireNonNull(depth, "depth");
            Objects.requireNonNull(motion, "motion");
            Objects.requireNonNull(reactive, "reactive");
            Objects.requireNonNull(jitterPixels, "jitterPixels");
            Objects.requireNonNull(motionScale, "motionScale");
            Objects.requireNonNull(producerCoverage, "producerCoverage");
            jitterPixels = new Vector2f(jitterPixels);
            motionScale = new Vector2f(motionScale);
            if (inputWidth <= 0 || inputHeight <= 0) {
                throw new IllegalArgumentException("Motion dimensions must be positive");
            }
            validateTexture(depth, GpuFormat.D32_FLOAT, inputWidth, inputHeight, "depth");
            validateTexture(motion, GpuFormat.RG16_FLOAT, inputWidth, inputHeight, "motion");
            validateTexture(reactive, GpuFormat.R8_UNORM, inputWidth, inputHeight, "reactive");
            if (!Float.isFinite(jitterPixels.x) || !Float.isFinite(jitterPixels.y)) {
                throw new IllegalArgumentException("Motion jitter must be finite");
            }
            Vector2f expectedScale = MetalMotionContract.motionVectorScale(inputWidth, inputHeight);
            if (!motionScale.equals(expectedScale, 1.0E-6F)) {
                throw new IllegalArgumentException("Motion scale must be input size divided by two");
            }
        }

        @Override
        public Vector2f jitterPixels() {
            return new Vector2f(jitterPixels);
        }

        @Override
        public Vector2f motionScale() {
            return new Vector2f(motionScale);
        }
    }

    record FrameGenerationInput(
            FrameStamp stamp,
            MetalGpuTexture sceneColor,
            MetalGpuTexture nativeSceneColor,
            MetalGpuTexture uiColor,
            FinalizedMotionFrame motion,
            CameraFrameInput camera,
            boolean reset
    ) {
        FrameGenerationInput {
            Objects.requireNonNull(stamp, "stamp");
            Objects.requireNonNull(sceneColor, "sceneColor");
            Objects.requireNonNull(nativeSceneColor, "nativeSceneColor");
            Objects.requireNonNull(uiColor, "uiColor");
            Objects.requireNonNull(motion, "motion");
            Objects.requireNonNull(camera, "camera");
            if (!stamp.equals(motion.stamp())) {
                throw new IllegalArgumentException("Scene and motion inputs must share a frame stamp");
            }
            if (reset != motion.reset()) {
                throw new IllegalArgumentException("Scene and motion inputs must agree on history reset");
            }
            validateColor(sceneColor, "sceneColor");
            validateColor(nativeSceneColor, "nativeSceneColor");
            validateColor(uiColor, "uiColor");
            if (!motion.producerCoverage().frameGenerationEligible()) {
                throw new IllegalArgumentException("Producer coverage is incomplete for Frame Generation");
            }
        }
    }

    private static void validateColor(final MetalGpuTexture texture, final String role) {
        if (texture.isClosed()) {
            throw new IllegalArgumentException(role + " references a closed texture");
        }
        if (texture.getFormat() != GpuFormat.RGBA8_UNORM) {
            throw new IllegalArgumentException(role + " requires RGBA8_UNORM on the current backend");
        }
        if (texture.getDepthOrLayers() != 1 || texture.getMipLevels() != 1
                || texture.getWidth(0) <= 0 || texture.getHeight(0) <= 0) {
            throw new IllegalArgumentException(role + " must be a positive single-layer, single-mip texture");
        }
    }

    private static void validateTexture(
            final MetalGpuTexture texture,
            final GpuFormat expectedFormat,
            final int width,
            final int height,
            final String role
    ) {
        if (texture.isClosed()) {
            throw new IllegalArgumentException(role + " references a closed texture");
        }
        if (texture.getFormat() != expectedFormat) {
            throw new IllegalArgumentException(role + " requires " + expectedFormat);
        }
        if (texture.getWidth(0) != width || texture.getHeight(0) != height
                || texture.getDepthOrLayers() != 1 || texture.getMipLevels() != 1) {
            throw new IllegalArgumentException(
                    role + " must match the input dimensions and be single-layer, single-mip"
            );
        }
    }
}
