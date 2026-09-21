package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Narrow execution seam for attachments proven to be pass-local.
 *
 * <p>Classification and allocation remain separate on purpose.  A receipt
 * can be collected in production diagnostics without changing storage mode;
 * allocation is attempted only when the explicit memoryless gate is on and
 * every lifetime/load/store invariant is satisfied.  A rejected candidate
 * returns {@code null}, so the caller keeps the ordinary private texture.</p>
 */
final class IrisMetalMemorylessAttachmentAllocator {
    private IrisMetalMemorylessAttachmentAllocator() {
    }

    static boolean eligible(final IrisMetalOptimizationPlan.ResolvedAttachment attachment) {
        return attachment != null
                && attachment.resolution() == IrisMetalOptimizationPlan.AttachmentResolution.RESOLVED_RASTER
                && IrisMetalTransientAttachmentClassifier.memorylessEligible(attachment.classification())
                && attachment.load() != IrisMetalOptimizationPlan.LoadAction.LOAD
                && attachment.store() == IrisMetalOptimizationPlan.StoreAction.DONT_CARE
                && attachment.lifetime() != null
                && attachment.lifetime().firstUse() == attachment.passIndex()
                && attachment.lifetime().lastWrite() == attachment.passIndex()
                && attachment.lifetime().lastUse() == attachment.passIndex()
                && attachment.lifetime().nextUse() == -1;
    }

    static @Nullable MetalGpuTexture tryCreate(
            final MetalDevice device,
            final IrisMetalOptimizationPlan.ResolvedAttachment attachment,
            final String label,
            final GpuFormat format,
            final int width,
            final int height
    ) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(format, "format");
        if (!IrisMetalOptimizationPlan.ENABLE_MEMORYLESS_ATTACHMENTS) {
            return null;
        }
        IrisMetalTransientAllocationTelemetry.memorylessRequested();
        if (!eligible(attachment) || width <= 0 || height <= 0) {
            IrisMetalTransientAllocationTelemetry.memorylessRejected();
            return null;
        }
        try {
            MetalGpuTexture texture = MetalGpuTexture.createMemorylessRenderTarget(
                    device,
                    GpuTexture.USAGE_RENDER_ATTACHMENT,
                    label == null ? "" : label,
                    format,
                    width,
                    height
            );
            IrisMetalTransientAllocationTelemetry.memorylessCreated();
            return texture;
        } catch (RuntimeException failure) {
            IrisMetalTransientAllocationTelemetry.memorylessRejected();
            return null;
        }
    }
}
