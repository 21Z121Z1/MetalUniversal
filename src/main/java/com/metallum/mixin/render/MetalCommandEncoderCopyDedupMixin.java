package com.metallum.mixin.render;

import com.metallum.client.metal.render.IrisMetalPerformanceCounters;
import com.metallum.client.metal.render.MetalCopyTrackedTexture;
import com.metallum.client.metal.render.MetalMipmapTrackedTexture;
import com.metallum.client.metal.render.MetalOptimizationProperties;
import com.mojang.blaze3d.textures.GpuTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips a full-surface copy only when the destination still contains the exact
 * same source texture content version produced by an earlier identical copy.
 */
@Mixin(targets = "com.metallum.client.metal.render.MetalCommandEncoder")
public abstract class MetalCommandEncoderCopyDedupMixin {
    private static final boolean ENABLED = MetalOptimizationProperties.enabled(
            MetalOptimizationProperties.TEXTURE_COPY_DEDUP, false
    );

    @Inject(
            method = "copyTextureToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/textures/GpuTexture;IIIIIII)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void metallum$skipRedundantFullCopy(
            final GpuTexture source,
            final GpuTexture destination,
            final int mipLevel,
            final int destX,
            final int destY,
            final int sourceX,
            final int sourceY,
            final int width,
            final int height,
            final CallbackInfo ci
    ) {
        if (!ENABLED) {
            return;
        }
        if (!metallum$isFullCopy(
                source, destination, mipLevel,
                destX, destY, sourceX, sourceY, width, height
        )) {
            return;
        }
        if (source instanceof MetalMipmapTrackedTexture sourceVersion
                && destination instanceof MetalCopyTrackedTexture tracked
                && tracked.metallum$matchesFullCopy(
                        source, sourceVersion.metallum$contentVersion(), mipLevel, width, height
                )) {
            long bytes = Math.multiplyExact(
                    Math.multiplyExact((long) width, height),
                    source.getFormat().blockSize()
            );
            IrisMetalPerformanceCounters.recordTextureCopySkipped(bytes);
            ci.cancel();
        }
    }

    @Inject(
            method = "copyTextureToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/textures/GpuTexture;IIIIIII)V",
            at = @At("RETURN")
    )
    private void metallum$rememberFullCopy(
            final GpuTexture source,
            final GpuTexture destination,
            final int mipLevel,
            final int destX,
            final int destY,
            final int sourceX,
            final int sourceY,
            final int width,
            final int height,
            final CallbackInfo ci
    ) {
        if (!ENABLED) {
            return;
        }
        if (!metallum$isFullCopy(
                source, destination, mipLevel,
                destX, destY, sourceX, sourceY, width, height
        )) {
            return;
        }
        if (source instanceof MetalMipmapTrackedTexture sourceVersion
                && destination instanceof MetalCopyTrackedTexture tracked) {
            tracked.metallum$recordFullCopy(
                    source, sourceVersion.metallum$contentVersion(), mipLevel, width, height
            );
        }
    }

    private static boolean metallum$isFullCopy(
            final GpuTexture source,
            final GpuTexture destination,
            final int mipLevel,
            final int destX,
            final int destY,
            final int sourceX,
            final int sourceY,
            final int width,
            final int height
    ) {
        return source != destination
                && mipLevel >= 0
                && destX == 0 && destY == 0
                && sourceX == 0 && sourceY == 0
                && width == source.getWidth(mipLevel)
                && height == source.getHeight(mipLevel)
                && width == destination.getWidth(mipLevel)
                && height == destination.getHeight(mipLevel)
                && source.getFormat() == destination.getFormat();
    }
}
