package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Narrow allocation seam for attachments proven pass-local by the physical
 * lifetime compiler.
 *
 * <p>The normal texture factory remains unchanged and private-storage-backed.
 * This allocator is intentionally package-local and fail-closed: it only emits
 * a memoryless texture for a resolved raster receipt whose classification is
 * {@code PASS_LOCAL_TRANSIENT}. The caller still owns the pass-level decision
 * to bind that texture only for the receipt's single pass.</p>
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
        if (!eligible(attachment) || width <= 0 || height <= 0) {
            return null;
        }
        return MetalGpuTexture.createMemorylessRenderTarget(
                device,
                GpuTexture.USAGE_RENDER_ATTACHMENT,
                label == null ? "" : label,
                format,
                width,
                height
        );
    }
}
