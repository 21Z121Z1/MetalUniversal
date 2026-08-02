package com.metallum.mixin.render;

import com.metallum.client.metal.render.IrisMetalPerformanceCounters;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps buffer descriptor state stable across repeated Iris/Sodium draw setup.
 *
 * <p>Texture and storage-image calls are deliberately not cancelled: those
 * methods also flush deferred clears or mark potential shader writes. Uniform
 * and SSBO binding calls are pure descriptor-state updates and can safely be
 * suppressed when buffer identity, offset and range are unchanged.</p>
 */
@Mixin(targets = "com.metallum.client.metal.render.MetalRenderPass")
public abstract class MetalRenderPassBindingCacheMixin {
    @Unique
    private final Map<String, GpuBuffer> metallum$uniformBuffers = new HashMap<>();
    @Unique
    private final Map<String, Long> metallum$uniformOffsets = new HashMap<>();
    @Unique
    private final Map<String, Long> metallum$uniformLengths = new HashMap<>();
    @Unique
    private final Map<Integer, GpuBuffer> metallum$storageBuffers = new HashMap<>();
    @Unique
    private final Map<Integer, Long> metallum$storageOffsets = new HashMap<>();
    @Unique
    private final Map<Integer, Long> metallum$storageLengths = new HashMap<>();

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
