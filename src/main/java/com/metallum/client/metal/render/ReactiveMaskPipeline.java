package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/** Collects per-frame coverage decisions for Temporal and frame interpolation. */
@Environment(EnvType.CLIENT)
final class ReactiveMaskPipeline {
    private final EnumMap<ProducerDomain, ProducerReceipt> receipts = new EnumMap<>(ProducerDomain.class);
    private FrameStamp stamp;

    void beginFrame(final FrameStamp frameStamp) {
        stamp = frameStamp;
        receipts.clear();
    }

    void recordCameraDepth() {
        record(ProducerDomain.CAMERA_DEPTH, ProducerCoverage.REAL_MOTION, 1);
    }

    void recordFirstPerson(final boolean visible) {
        record(
                ProducerDomain.FIRST_PERSON,
                visible ? ProducerCoverage.REACTIVE_ONLY : ProducerCoverage.REAL_MOTION,
                visible ? 1 : 0
        );
    }

    void recordTransparency(final boolean reactiveMaskAvailable) {
        record(
                ProducerDomain.TRANSPARENCY,
                reactiveMaskAvailable ? ProducerCoverage.REACTIVE_ONLY : ProducerCoverage.UNSUPPORTED,
                reactiveMaskAvailable ? 1 : 0
        );
    }

    void recordParticlesWeather(final boolean reactiveMaskAvailable) {
        record(
                ProducerDomain.PARTICLES_WEATHER,
                reactiveMaskAvailable ? ProducerCoverage.REACTIVE_ONLY : ProducerCoverage.UNSUPPORTED,
                reactiveMaskAvailable ? 1 : 0
        );
    }

    void recordModdedRenderers(final boolean unsupportedProviderActive) {
        record(
                ProducerDomain.MODDED_RENDERERS,
                unsupportedProviderActive ? ProducerCoverage.UNSUPPORTED : ProducerCoverage.REAL_MOTION,
                unsupportedProviderActive ? 1 : 0
        );
    }

    void recordDynamicDiagnostics(final MetalEntityMotionCapture.Diagnostics diagnostics) {
        int attached = diagnostics.samplesAttachedBySource().values().stream().mapToInt(Integer::intValue).sum();
        int encoded = diagnostics.motionDrawsEncoded();
        ProducerCoverage dynamicCoverage = ProducerCoverage.REAL_MOTION;

        int rendererLocal = diagnostics.samplesAttached(MetalEntityMotionCapture.Source.DISPLAY)
                + diagnostics.samplesAttached(MetalEntityMotionCapture.Source.ARMOR_STAND)
                + diagnostics.samplesAttached(MetalEntityMotionCapture.Source.END_CRYSTAL)
                + diagnostics.samplesAttached(MetalEntityMotionCapture.Source.END_CRYSTAL_BEAM)
                + diagnostics.samplesAttached(MetalEntityMotionCapture.Source.BLOCK_ENTITY);
        if (rendererLocal > 0) {
            // Root motion is real, but text, limbs, lids and beam-local animation
            // have no full-coverage reactive writer yet.
            dynamicCoverage = ProducerCoverage.UNSUPPORTED;
        }
        if (!diagnostics.motionDrawFailures().isEmpty()) {
            dynamicCoverage = ProducerCoverage.UNSUPPORTED;
        }
        record(ProducerDomain.DYNAMIC_CONTENT, dynamicCoverage, Math.max(attached, encoded));
    }

    void recordUnsupported(final ProducerDomain domain, final int samples) {
        record(domain, ProducerCoverage.UNSUPPORTED, samples);
    }

    List<ProducerReceipt> finish(final FrameStamp expectedStamp) {
        requireFrame(expectedStamp);
        List<ProducerReceipt> result = new ArrayList<>(ProducerDomain.values().length);
        for (ProducerDomain domain : ProducerDomain.values()) {
            ProducerReceipt receipt = receipts.get(domain);
            if (receipt == null) {
                throw new IllegalStateException("Producer receipt missing for " + domain);
            }
            result.add(receipt);
        }
        return List.copyOf(result);
    }

    void reset() {
        receipts.clear();
        stamp = null;
    }

    private void record(
            final ProducerDomain domain,
            final ProducerCoverage coverage,
            final int samples
    ) {
        if (stamp == null) {
            throw new IllegalStateException("Producer coverage recorded outside a frame");
        }
        ProducerReceipt previous = receipts.get(domain);
        ProducerCoverage merged = previous == null
                ? coverage
                : worse(previous.coverage(), coverage);
        int mergedSamples = previous == null ? samples : Math.max(previous.samples(), samples);
        receipts.put(domain, new ProducerReceipt(domain, merged, mergedSamples));
    }

    private void requireFrame(final FrameStamp expectedStamp) {
        if (stamp == null || !stamp.equals(expectedStamp)) {
            throw new IllegalStateException("Reactive receipts do not belong to " + expectedStamp);
        }
    }

    private static ProducerCoverage worse(
            final ProducerCoverage first,
            final ProducerCoverage second
    ) {
        if (first == ProducerCoverage.UNSUPPORTED || second == ProducerCoverage.UNSUPPORTED) {
            return ProducerCoverage.UNSUPPORTED;
        }
        if (first == ProducerCoverage.REACTIVE_ONLY || second == ProducerCoverage.REACTIVE_ONLY) {
            return ProducerCoverage.REACTIVE_ONLY;
        }
        return ProducerCoverage.REAL_MOTION;
    }
}
