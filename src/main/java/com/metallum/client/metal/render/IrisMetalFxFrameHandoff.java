package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Exact per-frame ownership receipt between the Iris final-output path and
 * MetalFX.
 *
 * <p>Iris owns the scene color until both its final resolve and output
 * color-space stage have completed. MetalFX may consume that color only when
 * the receipt belongs to the current MetalFX history epoch, Iris generation,
 * and render dimensions. A missing or stale receipt rejects one frame rather
 * than letting Temporal or Frame Generation retain ambiguous history.</p>
 */
@Environment(EnvType.CLIENT)
final class IrisMetalFxFrameHandoff {
    private static @Nullable Receipt current;
    private static long acceptedFrames;
    private static long rejectedFrames;
    private static int lastAcceptedGeneration;
    private static @Nullable String lastFailureReason;

    private IrisMetalFxFrameHandoff() {
    }

    record Receipt(
            FrameSynthesisContract.FrameStamp stamp,
            int irisGeneration,
            int width,
            int height,
            FrameSynthesisContract.DepthConvention depthConvention,
            boolean finalResolved,
            boolean colorSpaceCompleted,
            @Nullable String invalidReason
    ) {
        Receipt {
            Objects.requireNonNull(stamp, "stamp");
            Objects.requireNonNull(depthConvention, "depthConvention");
            if (irisGeneration <= 0 || width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Iris/MetalFX receipt identity must be positive");
            }
        }
    }

    record Admission(
            boolean irisActive,
            boolean accepted,
            String reason,
            @Nullable Receipt receipt
    ) {
    }

    record Diagnostics(
            long acceptedFrames,
            long rejectedFrames,
            int lastAcceptedGeneration,
            @Nullable String lastFailureReason
    ) {
    }

    static synchronized void beginFrame(
            final int irisGeneration,
            final FrameSynthesisContract.FrameStamp stamp,
            final int width,
            final int height,
            final FrameSynthesisContract.DepthConvention depthConvention
    ) {
        current = new Receipt(
                stamp, irisGeneration, width, height, depthConvention, false, false, null
        );
    }

    static synchronized void recordFinal(final int irisGeneration, final boolean resolved) {
        Receipt receipt = currentForGeneration(irisGeneration);
        if (receipt == null) {
            return;
        }
        current = new Receipt(
                receipt.stamp(), receipt.irisGeneration(), receipt.width(), receipt.height(),
                receipt.depthConvention(),
                resolved, receipt.colorSpaceCompleted(),
                resolved ? receipt.invalidReason() : "Iris final output did not resolve the Minecraft scene target"
        );
    }

    static synchronized void recordColorSpace(final int irisGeneration) {
        Receipt receipt = currentForGeneration(irisGeneration);
        if (receipt == null) {
            return;
        }
        current = new Receipt(
                receipt.stamp(), receipt.irisGeneration(), receipt.width(), receipt.height(),
                receipt.depthConvention(),
                receipt.finalResolved(), true, receipt.invalidReason()
        );
    }

    static synchronized void invalidateCurrent(final String reason) {
        Receipt receipt = current;
        if (receipt == null) {
            return;
        }
        current = new Receipt(
                receipt.stamp(), receipt.irisGeneration(), receipt.width(), receipt.height(),
                receipt.depthConvention(),
                receipt.finalResolved(), receipt.colorSpaceCompleted(), reason
        );
    }

    static synchronized Admission admit(
            final int activeIrisGeneration,
            final FrameSynthesisContract.@Nullable FrameStamp expectedStamp,
            final int width,
            final int height,
            final FrameSynthesisContract.DepthConvention expectedDepthConvention
    ) {
        Objects.requireNonNull(expectedDepthConvention, "expectedDepthConvention");
        if (activeIrisGeneration <= 0) {
            return new Admission(false, true, "Iris semantic rendering is inactive", null);
        }
        Receipt receipt = current;
        String rejection = null;
        if (expectedStamp == null) {
            rejection = "MetalFX has no current frame stamp";
        } else if (receipt == null) {
            rejection = "Iris did not publish a receipt for the current frame";
        } else if (receipt.irisGeneration() != activeIrisGeneration) {
            rejection = "Iris generation changed before MetalFX consumption";
        } else if (!receipt.stamp().equals(expectedStamp)) {
            rejection = "Iris and MetalFX frame stamps differ";
        } else if (receipt.width() != width || receipt.height() != height) {
            rejection = "Iris and MetalFX render dimensions differ";
        } else if (receipt.depthConvention() != expectedDepthConvention) {
            rejection = "Iris and MetalFX depth conventions differ";
        } else if (receipt.invalidReason() != null) {
            rejection = receipt.invalidReason();
        } else if (!receipt.finalResolved()) {
            rejection = "Iris final output has not resolved";
        } else if (!receipt.colorSpaceCompleted()) {
            rejection = "Iris output color-space stage has not completed";
        }
        if (rejection != null) {
            rejectedFrames++;
            lastFailureReason = rejection;
            return new Admission(true, false, rejection, receipt);
        }
        acceptedFrames++;
        lastAcceptedGeneration = activeIrisGeneration;
        lastFailureReason = null;
        return new Admission(true, true, "accepted", receipt);
    }

    static synchronized Diagnostics diagnostics() {
        return new Diagnostics(
                acceptedFrames,
                rejectedFrames,
                lastAcceptedGeneration,
                lastFailureReason
        );
    }

    static synchronized void resetForTests() {
        current = null;
        acceptedFrames = 0L;
        rejectedFrames = 0L;
        lastAcceptedGeneration = 0;
        lastFailureReason = null;
    }

    private static @Nullable Receipt currentForGeneration(final int irisGeneration) {
        Receipt receipt = current;
        return receipt != null && receipt.irisGeneration() == irisGeneration ? receipt : null;
    }
}
