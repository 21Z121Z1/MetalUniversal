package com.metallum.mixin.render;

import com.metallum.client.metal.render.IrisMetalPerformanceCounters;
import com.metallum.client.metal.render.MetalBindingToken;
import com.metallum.client.metal.render.MetalBindingTokenCache;
import com.metallum.client.metal.render.MetalCompiledBindingPlan;
import com.metallum.client.metal.render.MetalCompiledBindingPlanProvider;
import com.metallum.client.metal.render.MetalUploadDedupBuffer;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * Keeps buffer descriptor state stable across repeated Iris/Sodium draw setup.
 *
 * <p>Compatibility-facing calls still arrive with a resource name. The name is
 * compiled once into a process-stable integer token. Once a pipeline is bound,
 * the token resolves to its generation-time dense binding slot and the stable
 * path becomes an identity probe plus an array lookup.</p>
 *
 * <p>Texture and storage-image calls are not cancelled here because those
 * methods also flush deferred clears or mark potential shader writes. Their
 * redundant native setters are suppressed later by the encoder-local shadow.</p>
 */
@Mixin(targets = "com.metallum.client.metal.render.MetalRenderPass")
public abstract class MetalRenderPassBindingCacheMixin {
    @Unique
    private static final BindingState[] metallum$EMPTY_BINDINGS = new BindingState[0];

    @Unique
    private final Int2ObjectOpenHashMap<BindingState> metallum$fallbackUniformBindings =
            new Int2ObjectOpenHashMap<>();
    @Unique
    private final Int2ObjectOpenHashMap<BindingState> metallum$storageBindings =
            new Int2ObjectOpenHashMap<>();
    @Unique
    private final MetalBindingTokenCache metallum$tokenCache = new MetalBindingTokenCache();
    @Unique
    private @Nullable MetalCompiledBindingPlan metallum$currentBindingPlan;
    @Unique
    private BindingState[] metallum$uniformBindingsBySlot = metallum$EMPTY_BINDINGS;

    @Inject(method = "setPipeline", at = @At("RETURN"))
    private void metallum$installCompiledBindingPlan(
            final RenderPipeline pipeline,
            final CallbackInfo ci
    ) {
        Object compiledPipeline = metallum$compiledPipeline(this);
        MetalCompiledBindingPlan nextPlan = compiledPipeline instanceof MetalCompiledBindingPlanProvider provider
                ? provider.metallum$bindingPlan()
                : null;
        if (nextPlan == this.metallum$currentBindingPlan) {
            return;
        }
        this.metallum$currentBindingPlan = nextPlan;
        this.metallum$uniformBindingsBySlot = nextPlan == null
                ? metallum$EMPTY_BINDINGS
                : new BindingState[nextPlan.bindingCount()];
        // A fallback entry may have been learned before the first pipeline was
        // installed. Drop it so the dense slot becomes the single source of
        // truth for this pipeline generation.
        this.metallum$fallbackUniformBindings.clear();
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
        MetalBindingToken token = this.metallum$tokenCache.resolve(name);
        // These Mojang blocks also invalidate the generated Iris draw block;
        // preserve that semantic boundary even when their native binding did
        // not move.
        if (token.invalidatesGeneratedIrisBlock()) {
            return;
        }

        int slot = this.metallum$currentBindingPlan == null
                ? -1
                : this.metallum$currentBindingPlan.slotFor(token);
        BindingState state;
        if (slot >= 0) {
            state = this.metallum$uniformBindingsBySlot[slot];
            if (state == null) {
                state = new BindingState();
                this.metallum$uniformBindingsBySlot[slot] = state;
            } else if (state.matches(value)) {
                IrisMetalPerformanceCounters.recordDescriptorBindingSkipped();
                ci.cancel();
                return;
            }
        } else {
            state = this.metallum$fallbackUniformBindings.get(token.id());
            if (state != null && state.matches(value)) {
                IrisMetalPerformanceCounters.recordDescriptorBindingSkipped();
                ci.cancel();
                return;
            }
            if (state == null) {
                state = new BindingState();
                this.metallum$fallbackUniformBindings.put(token.id(), state);
            }
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
    private static Object metallum$compiledPipeline(final Object renderPass) {
        try {
            return CompiledPipelineFieldHolder.FIELD.get(renderPass);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to read MetalRenderPass.compiledPipeline", exception);
        }
    }

    @Unique
    private static long metallum$bindingVersion(final GpuBuffer buffer) {
        return buffer instanceof MetalUploadDedupBuffer versioned
                ? versioned.metallum$bindingVersion()
                : 0L;
    }

    @Unique
    private static final class CompiledPipelineFieldHolder {
        private static final Field FIELD = resolve();

        private static Field resolve() {
            try {
                Class<?> type = Class.forName(
                        "com.metallum.client.metal.render.MetalRenderPass",
                        false,
                        MetalRenderPassBindingCacheMixin.class.getClassLoader()
                );
                Field field = type.getDeclaredField("compiledPipeline");
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }
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
