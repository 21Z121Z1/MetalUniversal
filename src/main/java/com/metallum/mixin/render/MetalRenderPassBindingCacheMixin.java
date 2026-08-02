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
    private final Map<String, metallum$TextureBinding> metallum$textures = new HashMap<>();
    @Unique
    private final Map<String, metallum$BufferBinding> metallum$uniforms = new HashMap<>();
    @Unique
    private final Map<String, GpuTextureView> metallum$storageImages = new HashMap<>();
    @Unique
    private final Map<Integer, metallum$BufferBinding> metallum$storageBuffers = new HashMap<>();

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
            if (!this.metallum$textures.containsKey(name)) {
                IrisMetalPerformanceCounters.recordDescriptorBindingSkipped();
                ci.cancel();
                return;
            }
            this.metallum$textures.remove(name);
            return;
        }
        if (textureView == null || sampler == null) {
            return;
        }
        metallum$TextureBinding previous = this.metallum$textures.get(name);
        if (previous != null && previous.matches(textureView, sampler)) {
            IrisMetalPerformanceCounters.recordDescriptorBindingSkipped();
            ci.cancel();
            return;
        }
        this.metallum$textures.put(name, new metallum$TextureBinding(textureView, sampler));
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
        metallum$BufferBinding previous = this.metallum$uniforms.get(name);
        if (previous != null && previous.matches(value)) {
            IrisMetalPerformanceCounters.recordDescriptorBindingSkipped();
            ci.cancel();
            return;
        }
        this.metallum$uniforms.put(name, metallum$BufferBinding.of(value));
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
        metallum$BufferBinding previous = this.metallum$storageBuffers.get(binding);
        if (previous != null && previous.matches(value)) {
            IrisMetalPerformanceCounters.recordDescriptorBindingSkipped();
            ci.cancel();
            return;
        }
        this.metallum$storageBuffers.put(binding, metallum$BufferBinding.of(value));
    }

    @Unique
    private record metallum$TextureBinding(GpuTextureView view, GpuSampler sampler) {
        private boolean matches(final GpuTextureView otherView, final GpuSampler otherSampler) {
            return this.view == otherView && this.sampler == otherSampler;
        }
    }

    @Unique
    private record metallum$BufferBinding(GpuBuffer buffer, long offset, long length) {
        private static metallum$BufferBinding of(final GpuBufferSlice slice) {
            return new metallum$BufferBinding(slice.buffer(), slice.offset(), slice.length());
        }

        private boolean matches(final GpuBufferSlice slice) {
            return this.buffer == slice.buffer()
                    && this.offset == slice.offset()
                    && this.length == slice.length();
        }
    }
}
