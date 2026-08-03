package com.metallum.mixin.render;

import com.metallum.client.metal.render.IrisMetalPerformanceCounters;
import com.metallum.client.metal.render.MetalBindingToken;
import com.metallum.client.metal.render.MetalBindingTokenRegistry;
import com.metallum.client.metal.render.MetalUploadDedupBuffer;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps buffer descriptor state stable across repeated Iris/Sodium draw setup.
 *
 * <p>Compatibility-facing calls still arrive with a resource name. The name is
 * compiled once into a process-stable integer token, then a small identity
 * cache handles the usual case where a pipeline reuses the exact same String
 * object every draw. Stable repeated calls therefore perform an identity check
 * plus one primitive-map lookup, with no String hash and no allocation.</p>
 *
 * <p>Texture and storage-image calls are not cancelled here because those
 * methods also flush deferred clears or mark potential shader writes. Their
 * redundant native setters are suppressed later by the encoder-local shadow.</p>
 */
@Mixin(targets = "com.metallum.client.metal.render.MetalRenderPass")
public abstract class MetalRenderPassBindingCacheMixin {
    @Unique
    private static final int metallum$TOKEN_CACHE_SIZE = 16;
    @Unique
    private static final int metallum$TOKEN_CACHE_MASK = metallum$TOKEN_CACHE_SIZE - 1;

    @Unique
    private final Int2ObjectOpenHashMap<BindingState> metallum$uniformBindings =
            new Int2ObjectOpenHashMap<>();
    @Unique
    private final Int2ObjectOpenHashMap<BindingState> metallum$storageBindings =
            new Int2ObjectOpenHashMap<>();
    @Unique
    private final String[] metallum$tokenNames = new String[metallum$TOKEN_CACHE_SIZE];
    @Unique
    private final MetalBindingToken[] metallum$tokens = new MetalBindingToken[metallum$TOKEN_CACHE_SIZE];

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
        MetalBindingToken token = this.metallum$resolveToken(name);
        // These Mojang blocks also invalidate the generated Iris draw block;
        // preserve that semantic boundary even when their native binding did
        // not move.
        if (token.invalidatesGeneratedIrisBlock()) {
            return;
        }
        BindingState state = this.metallum$uniformBindings.get(token.id());
        if (state != null && state.matches(value)) {
            IrisMetalPerformanceCounters.recordDescriptorBindingSkipped();
            ci.cancel();
            return;
        }
        if (state == null) {
            state = new BindingState();
            this.metallum$uniformBindings.put(token.id(), state);
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
    private MetalBindingToken metallum$resolveToken(final String name) {
        int cacheIndex = System.identityHashCode(name) & metallum$TOKEN_CACHE_MASK;
        if (this.metallum$tokenNames[cacheIndex] == name) {
            return this.metallum$tokens[cacheIndex];
        }
        MetalBindingToken token = MetalBindingTokenRegistry.resolve(name);
        this.metallum$tokenNames[cacheIndex] = name;
        this.metallum$tokens[cacheIndex] = token;
        return token;
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
