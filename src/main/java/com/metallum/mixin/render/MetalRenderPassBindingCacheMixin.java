package com.metallum.mixin.render;

import com.metallum.client.metal.render.IrisMetalPerformanceCounters;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps the Java-side descriptor state stable across repeated Iris/Sodium draw
 * setup calls. MetalRenderPass already pushes only dirty descriptors; this
 * layer prevents identical API calls from dirtying them in the first place.
 */
@Mixin(targets = "com.metallum.client.metal.render.MetalRenderPass")
public abstract class MetalRenderPassBindingCacheMixin {
    @Unique
    private final Map<String, GpuTextureView> metallum$textureViews = new HashMap<>();
    @Unique
    private final Map<String, GpuSampler> metallum$textureSamplers = new HashMap<>();
    @Unique
    private final Map<String, GpuBuffer> metallum$uniformBuffers = new HashMap<>();
    @Unique
    private final Map<String, Long> metallum$uniformOffsets = new HashMap<>();
    @Unique
    private final Map<String, Long> metallum$uniformLengths = new HashMap<>();
    @Unique
    private final Map<String, GpuTextureView> metallum$storageImages = new HashMap<>();
    @Unique
    private final Map<Integer, GpuBuffer> metallum$storageBuffers = new HashMap<>();
    @Unique
    private final Map<Integer, Long> metallum$storageOffsets = new HashMap<>();
    @Unique
    private final Map<Integer, Long> metallum$storageLengths = new HashMap<>();

    @Inject(
            method = "bindTexture(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void metallum$deduplicateTexture(
            final String name,
            final GpuTextureView textureView,
            final GpuSampler sampler,
            final CallbackInfo ci
    ) {
        if (textureView == null && sampler == null) {
            if (!this.metallum$textureViews.containsKey(name)) {
                IrisMetalPerformanceCounters.recordDescriptorBindingSkipped();
                ci.cancel();
                return;
            }
            this.metallum$textureViews.remove(name);
            this.metallum$textureSamplers.remove(name);
            return;
        }
        if (textureView == null || sampler == null) {
            return;
        }
        if (this.metallum$textureViews.get(name) == textureView
                && this.metallum$textureSamplers.get(name) == sampler) {
            IrisMetalPerformanceCounters.recordDescriptorBindingSkipped();
            ci.cancel();
            return;
        }
        this.metallum$textureViews.put(name, textureView);
        this.metallum$textureSamplers.put(name, sampler);
    }

    @Inject(
            method = "setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void metallum$deduplicateUniform(
            final String name,
            final GpuBufferSlice value,
            final CallbackInfo ci
    ) {
        if (this.metallum$sameUniform(name, value)) {
            IrisMetalPerformanceCounters.recordDescriptorBindingSkipped();
            ci.cancel();
            return;
        }
        this.metallum$uniformBuffers.put(name, value.buffer());
        this.metallum$uniformOffsets.put(name, value.offset());
        this.metallum$uniformLengths.put(name, value.length());
    }

    @Inject(
            method = "bindStorageImage(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void metallum$deduplicateStorageImage(
            final String name,
            final GpuTextureView textureView,
            final CallbackInfo ci
    ) {
        if (this.metallum$storageImages.get(name) == textureView) {
            IrisMetalPerformanceCounters.recordDescriptorBindingSkipped();
            ci.cancel();
            return;
        }
        this.metallum$storageImages.put(name, textureView);
    }

    @Inject(
            method = "bindStorageBuffer(ILcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void metallum$deduplicateStorageBuffer(
            final int binding,
            final GpuBufferSlice value,
            final CallbackInfo ci
    ) {
        if (this.metallum$sameStorageBuffer(binding, value)) {
            IrisMetalPerformanceCounters.recordDescriptorBindingSkipped();
            ci.cancel();
            return;
        }
        this.metallum$storageBuffers.put(binding, value.buffer());
        this.metallum$storageOffsets.put(binding, value.offset());
        this.metallum$storageLengths.put(binding, value.length());
    }

    @Unique
    private boolean metallum$sameUniform(final String name, final GpuBufferSlice value) {
        return this.metallum$uniformBuffers.get(name) == value.buffer()
                && this.metallum$uniformOffsets.getOrDefault(name, Long.MIN_VALUE) == value.offset()
                && this.metallum$uniformLengths.getOrDefault(name, Long.MIN_VALUE) == value.length();
    }

    @Unique
    private boolean metallum$sameStorageBuffer(final int binding, final GpuBufferSlice value) {
        return this.metallum$storageBuffers.get(binding) == value.buffer()
                && this.metallum$storageOffsets.getOrDefault(binding, Long.MIN_VALUE) == value.offset()
                && this.metallum$storageLengths.getOrDefault(binding, Long.MIN_VALUE) == value.length();
    }
}
