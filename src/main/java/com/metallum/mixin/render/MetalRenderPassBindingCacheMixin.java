package com.metallum.mixin.render;

import com.metallum.client.metal.render.IrisMetalPerformanceCounters;
import com.metallum.client.metal.render.MetalUploadDedupBuffer;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Keeps buffer descriptor state stable across repeated Iris/Sodium draw setup.
 *
 * <p>This cache deliberately mirrors MobileGL's compact state shadow: one map
 * lookup resolves the complete binding fingerprint. The previous implementation
 * split identity, backing generation, offset and length across eight maps,
 * multiplying hashing and boxing work on every draw.</p>
 *
 * <p>Texture and storage-image calls are not cancelled here because those
 * methods also flush deferred clears or mark potential shader writes. Their
 * redundant native setters are suppressed later by the encoder-local shadow.</p>
 */
@Mixin(targets = "com.metallum.client.metal.render.MetalRenderPass")
public abstract class MetalRenderPassBindingCacheMixin {
    @Unique
    private final Map<String, BindingState> metallum$uniformBindings =
            new Object2ObjectOpenHashMap<>();
    @Unique
    private final Int2ObjectOpenHashMap<BindingState> metallum$storageBindings =
            new Int2ObjectOpenHashMap<>();

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
        // These Mojang blocks also invalidate the generated Iris draw block;
        // preserve that semantic boundary even when their native binding did
        // not move.
        if ("DynamicTransforms".equals(name) || "Projection".equals(name)) {
            return;
        }
        BindingState state = this.metallum$uniformBindings.get(name);
        if (state != null && state.matches(value)) {
            IrisMetalPerformanceCounters.recordDescriptorBindingSkipped();
            ci.cancel();
            return;
        }
        if (state == null) {
            state = new BindingState();
            this.metallum$uniformBindings.put(name, state);
        }
        state.update(value);
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
        BindingState state = this.metallum$storageBindings.get(binding);
        if (state != null && state.matches(value)) {
            IrisMetalPerformanceCounters.recordDescriptorBindingSkipped();
            ci.cancel();
            return;
        }
        if (state == null) {
            state = new BindingState();
            this.metallum$storageBindings.put(binding, state);
        }
        state.update(value);
    }

    @Unique
    private static long metallum$bindingVersion(final GpuBuffer buffer) {
        return buffer instanceof MetalUploadDedupBuffer versioned
                ? versioned.metallum$bindingVersion()
                : 0L;
    }

    private static final class BindingState {
        private GpuBuffer buffer;
        private long backingVersion;
        private long offset;
        private long length;

        private boolean matches(final GpuBufferSlice value) {
            return this.buffer == value.buffer()
                    && this.backingVersion == metallum$bindingVersion(value.buffer())
                    && this.offset == value.offset()
                    && this.length == value.length();
        }

        private void update(final GpuBufferSlice value) {
            this.buffer = value.buffer();
            this.backingVersion = metallum$bindingVersion(value.buffer());
            this.offset = value.offset();
            this.length = value.length();
        }
    }
}
