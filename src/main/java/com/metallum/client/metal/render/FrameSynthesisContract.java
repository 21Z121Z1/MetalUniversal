package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector2f;
import org.joml.Matrix4fc;

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
        /**
         * The domain was not observed in this source frame. This is different
         * from a present producer whose exact motion was not encoded.
         */
        NOT_PRESENT,
        UNSUPPORTED
    }

    enum ProducerDomain {
        CAMERA_DEPTH,
        DYNAMIC_CONTENT,
        BLOCK_ENTITIES,
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
            if (coverage == ProducerCoverage.NOT_PRESENT && samples != 0) {
                throw new IllegalArgumentException("An observed producer cannot claim to be absent");
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
            // Frame interpolation has no reactive-mask input. A reactive-only
            // particle, transparency, weather or modded draw is just as unsafe
            // as a reactive-only entity. Absence is an explicit zero-sample
            // receipt, never inferred from a missing motion replay.
            boolean cameraMotion = false;
            for (ProducerReceipt receipt : receipts) {
                if (receipt.coverage() != ProducerCoverage.REAL_MOTION
                        && receipt.coverage() != ProducerCoverage.NOT_PRESENT) {
                    return false;
                }
                if (receipt.domain() == ProducerDomain.CAMERA_DEPTH) {
                    cameraMotion = receipt.coverage() == ProducerCoverage.REAL_MOTION;
                }
            }
            return cameraMotion;
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
            if (!(fieldOfViewDegrees > 0.0F && fieldOfViewDegrees < 180.0F)
                    || !(nearPlane > 0.0F && farPlane > nearPlane)
                    || !(aspectRatio > 0.0F && deltaSeconds > 0.0F)
                    || !Float.isFinite(fieldOfViewDegrees)
                    || !Float.isFinite(nearPlane)
                    || !Float.isFinite(farPlane)
                    || !Float.isFinite(aspectRatio)
                    || !Float.isFinite(deltaSeconds)) {
                throw new IllegalArgumentException("Invalid camera input for Frame Generation");
            }
        }
    }

    /**
     * The symmetric, finite, reversed-Z [0,1] perspective used by Vanilla.
     * Recover the actual frustum rather than substituting 70 degrees, 1000
     * blocks or the drawable aspect when metadata is absent. Bob/hurt are
     * rigid view transforms; a skewed/non-perspective base is not representable
     * by the Frame Interpolator camera fields and is deliberately rejected.
     */
    record Perspective(float fieldOfViewDegrees, float nearPlane, float farPlane, float aspectRatio) {
        static Perspective fromProjection(final Matrix4fc projection) {
            if (!MetalFxMath.isFinite(projection)
                    || !(projection.m00() > 0.0F && projection.m11() > 0.0F)
                    || projection.m23() != -1.0F || projection.m33() != 0.0F
                    || projection.m01() != 0.0F || projection.m02() != 0.0F
                    || projection.m03() != 0.0F || projection.m10() != 0.0F
                    || projection.m12() != 0.0F || projection.m13() != 0.0F
                    || projection.m20() != 0.0F || projection.m21() != 0.0F
                    || projection.m30() != 0.0F || projection.m31() != 0.0F
                    || !(projection.m22() > 0.0F && projection.m32() > 0.0F)) {
                throw new IllegalArgumentException("Frame Generation requires a finite reversed-Z perspective");
            }
            float near = projection.m32() / (projection.m22() + 1.0F);
            float far = projection.m32() / projection.m22();
            float fov = MetalFxMath.verticalFieldOfViewDegrees(projection);
            float aspect = projection.m11() / projection.m00();
            // Reuse the SDK-facing range checks without manufacturing source
            // timing: this literal is a validation witness, never frame data.
            new CameraFrameInput(fov, near, far, aspect, 1.0F);
            return new Perspective(fov, near, far, aspect);
        }

        CameraFrameInput atSourceInterval(final float deltaSeconds) {
            return new CameraFrameInput(fieldOfViewDegrees, nearPlane, farPlane, aspectRatio, deltaSeconds);
        }
    }

    static boolean sourceDepthMatches(final GpuTexture texture, final int width, final int height) {
        return texture != null && !texture.isClosed()
                && width > 0 && height > 0
                && texture.getFormat() == GpuFormat.D32_FLOAT
                && texture.getMipLevels() == 1 && texture.getDepthOrLayers() == 1
                && texture.getWidth(0) == width && texture.getHeight(0) == height
                && (texture.usage() & GpuTexture.USAGE_TEXTURE_BINDING) != 0;
    }

    /**
     * Evidence for the transfer function and composition contract consumed by
     * Frame Generation. RGBA8_UNORM storage does not prove whether the bound
     * view applies sRGB decoding or preserves linear values.
     */
    enum ColorEncodingEvidence {
        UNPROVEN_RGBA8_UNORM_SRGB_VIEW(false, false),
        DIAGNOSTIC_UNPROVEN_RGBA8_UNORM_SRGB_VIEW(false, true),
        LINEAR_TEMPORAL_POST_TONEMAP_FG_PREMULTIPLIED_UI(true, false);

        private final boolean provenForFrameGeneration;
        private final boolean diagnosticAssumption;

        ColorEncodingEvidence(
                boolean provenForFrameGeneration,
                boolean diagnosticAssumption
        ) {
            this.provenForFrameGeneration = provenForFrameGeneration;
            this.diagnosticAssumption = diagnosticAssumption;
        }

        boolean provenForFrameGeneration() {
            return provenForFrameGeneration;
        }

        boolean diagnosticAssumption() {
            return diagnosticAssumption;
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

    /**
     * Pure admission decision before texture-view roles are attached.
     *
     * <p>Color transfer evidence is explicit. The current backend records
     * {@code RGBA8_UNORM} storage with an unproven sRGB/linear view, so the
     * Frame Generation gate remains closed until a texture-view contract proves
     * the transfer function and composition order.</p>
     */
    record FrameGenerationAdmission(
            FrameStamp stamp,
            ProducerCoverageSet producerCoverage,
            CameraFrameInput camera,
            boolean reset,
            ColorEncodingEvidence colorEncoding
    ) {
        FrameGenerationAdmission {
            Objects.requireNonNull(stamp, "stamp");
            Objects.requireNonNull(producerCoverage, "producerCoverage");
            Objects.requireNonNull(camera, "camera");
            Objects.requireNonNull(colorEncoding, "colorEncoding");
            if (!producerCoverage.frameGenerationEligible()) {
                throw new IllegalArgumentException("Producer coverage is incomplete for Frame Generation");
            }
        }

        /**
         * Compatibility constructor for pure coverage tests. Production callers
         * must select the explicit color evidence when the texture-view contract
         * becomes proven.
         */
        FrameGenerationAdmission(
                FrameStamp stamp,
                ProducerCoverageSet producerCoverage,
                CameraFrameInput camera,
                boolean reset
        ) {
            this(
                    stamp,
                    producerCoverage,
                    camera,
                    reset,
                    ColorEncodingEvidence.UNPROVEN_RGBA8_UNORM_SRGB_VIEW
            );
        }

        boolean frameGenerationEligible() {
            return frameGenerationEligible(false);
        }

        /**
         * Diagnostic combined validation may assume the unproven RGBA8 view,
         * but the flag is intentionally explicit and production callers use the
         * no-argument fail-closed form above.
         */
        boolean frameGenerationEligible(boolean allowDiagnosticColorAssumption) {
            return producerCoverage.frameGenerationEligible()
                    && (colorEncoding.provenForFrameGeneration()
                    || allowDiagnosticColorAssumption && colorEncoding.diagnosticAssumption());
        }

        boolean colorContractProven() {
            return colorEncoding.provenForFrameGeneration();
        }

        boolean diagnosticColorAssumption() {
            return colorEncoding.diagnosticAssumption();
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
