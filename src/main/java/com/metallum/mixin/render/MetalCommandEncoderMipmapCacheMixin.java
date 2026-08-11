package com.metallum.mixin.render;

import com.metallum.client.metal.render.IrisMetalPerformanceCounters;
import com.metallum.client.metal.render.MetalMipmapTrackedTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Converts Iris's repeated glGenerateMipmap-style requests into content-version
 * checks while preserving generation after every real texture write.
 */
@Mixin(targets = "com.metallum.client.metal.render.MetalCommandEncoder")
public abstract class MetalCommandEncoderMipmapCacheMixin {
    @Inject(method = "generateMipmaps", at = @At("HEAD"), cancellable = true)
    private void metallum$skipCurrentMipmaps(@Coerce final Object texture, final CallbackInfo ci) {
        if (texture instanceof MetalMipmapTrackedTexture tracked && tracked.metallum$mipmapsCurrent()) {
            IrisMetalPerformanceCounters.recordMipmapGenerationSkipped();
            ci.cancel();
        }
    }

    @Inject(method = "generateMipmaps", at = @At("RETURN"))
    private void metallum$recordGeneratedMipmaps(@Coerce final Object texture, final CallbackInfo ci) {
        if (texture instanceof MetalMipmapTrackedTexture tracked) {
            tracked.metallum$markMipmapsGenerated();
        }
    }

    @Inject(
            method = "clearColorTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Lorg/joml/Vector4fc;)V",
            at = @At("HEAD")
    )
    private void metallum$invalidateColorClear(
            final GpuTexture colorTexture,
            final Vector4fc clearColor,
            final CallbackInfo ci
    ) {
        metallum$markChanged(colorTexture);
    }

    @Inject(
            method = "clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/blaze3d/textures/GpuTexture;D)V",
            at = @At("HEAD")
    )
    private void metallum$invalidateColorDepthClear(
            final GpuTexture colorTexture,
            final Vector4fc clearColor,
            final GpuTexture depthTexture,
            final double clearDepth,
            final CallbackInfo ci
    ) {
        metallum$markChanged(colorTexture);
        metallum$markChanged(depthTexture);
    }

    @Inject(
            method = "clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/blaze3d/textures/GpuTexture;DIIII)V",
            at = @At("HEAD")
    )
    private void metallum$invalidateColorDepthRegionClear(
            final GpuTexture colorTexture,
            final Vector4fc clearColor,
            final GpuTexture depthTexture,
            final double clearDepth,
            final int regionX,
            final int regionY,
            final int regionWidth,
            final int regionHeight,
            final CallbackInfo ci
    ) {
        metallum$markChanged(colorTexture);
        metallum$markChanged(depthTexture);
    }

    @Inject(
            method = "clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V",
            at = @At("HEAD")
    )
    private void metallum$invalidateDepthClear(
            final GpuTexture depthTexture,
            final double clearDepth,
            final CallbackInfo ci
    ) {
        metallum$markChanged(depthTexture);
    }

    private static void metallum$markChanged(final GpuTexture texture) {
        if (texture instanceof MetalMipmapTrackedTexture tracked) {
            tracked.metallum$markContentsChanged();
        }
    }
}
