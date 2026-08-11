package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalPerformanceCounters;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Caches token-to-block results whose layouts are immutable for one Iris generation. */
@Mixin(targets = "com.metallum.client.metal.render.IrisMetalUniformValues")
public abstract class IrisMetalUniformLookupCacheMixin {
    @Unique
    private final ConcurrentMap<Object, Object> metallum$blocks = new ConcurrentHashMap<>();
    @Unique
    private final ConcurrentMap<Object, GpuBufferSlice> metallum$slices = new ConcurrentHashMap<>();
    @Unique
    private final ConcurrentMap<Object, Integer> metallum$drawBlockSizes = new ConcurrentHashMap<>();
    @Unique
    private final ConcurrentMap<Object, Boolean> metallum$dynamicTransforms = new ConcurrentHashMap<>();
    @Unique
    private final ConcurrentMap<Object, Boolean> metallum$projections = new ConcurrentHashMap<>();

    @Inject(method = "findBlock", at = @At("HEAD"), cancellable = true)
    private void metallum$reuseBlock(
            final Object token,
            final CallbackInfoReturnable<Object> cir
    ) {
        Object cached = this.metallum$blocks.get(token);
        if (cached != null) {
            IrisMetalPerformanceCounters.recordUniformLookupCacheHit();
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "findBlock", at = @At("RETURN"))
    private void metallum$rememberBlock(
            final Object token,
            final CallbackInfoReturnable<Object> cir
    ) {
        Object value = cir.getReturnValue();
        if (value != null) {
            this.metallum$blocks.putIfAbsent(token, value);
        }
    }

    @Inject(
            method = "slice(Ljava/lang/Object;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void metallum$reuseSlice(
            final Object token,
            final CallbackInfoReturnable<GpuBufferSlice> cir
    ) {
        GpuBufferSlice cached = this.metallum$slices.get(token);
        if (cached != null) {
            IrisMetalPerformanceCounters.recordUniformLookupCacheHit();
            cir.setReturnValue(cached);
        }
    }

    @Inject(
            method = "slice(Ljava/lang/Object;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;",
            at = @At("RETURN")
    )
    private void metallum$rememberSlice(
            final Object token,
            final CallbackInfoReturnable<GpuBufferSlice> cir
    ) {
        GpuBufferSlice value = cir.getReturnValue();
        if (value != null) {
            this.metallum$slices.putIfAbsent(token, value);
        }
    }

    @Inject(method = "drawBlockSize", at = @At("HEAD"), cancellable = true)
    private void metallum$reuseDrawBlockSize(
            final Object token,
            final CallbackInfoReturnable<Integer> cir
    ) {
        Integer cached = this.metallum$drawBlockSizes.get(token);
        if (cached != null) {
            IrisMetalPerformanceCounters.recordUniformLookupCacheHit();
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "drawBlockSize", at = @At("RETURN"))
    private void metallum$rememberDrawBlockSize(
            final Object token,
            final CallbackInfoReturnable<Integer> cir
    ) {
        this.metallum$drawBlockSizes.putIfAbsent(token, cir.getReturnValue());
    }

    @Inject(method = "requiresDynamicTransforms", at = @At("HEAD"), cancellable = true)
    private void metallum$reuseDynamicTransforms(
            final Object token,
            final CallbackInfoReturnable<Boolean> cir
    ) {
        Boolean cached = this.metallum$dynamicTransforms.get(token);
        if (cached != null) {
            IrisMetalPerformanceCounters.recordUniformLookupCacheHit();
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "requiresDynamicTransforms", at = @At("RETURN"))
    private void metallum$rememberDynamicTransforms(
            final Object token,
            final CallbackInfoReturnable<Boolean> cir
    ) {
        this.metallum$dynamicTransforms.putIfAbsent(token, cir.getReturnValue());
    }

    @Inject(method = "requiresProjection", at = @At("HEAD"), cancellable = true)
    private void metallum$reuseProjection(
            final Object token,
            final CallbackInfoReturnable<Boolean> cir
    ) {
        Boolean cached = this.metallum$projections.get(token);
        if (cached != null) {
            IrisMetalPerformanceCounters.recordUniformLookupCacheHit();
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "requiresProjection", at = @At("RETURN"))
    private void metallum$rememberProjection(
            final Object token,
            final CallbackInfoReturnable<Boolean> cir
    ) {
        this.metallum$projections.putIfAbsent(token, cir.getReturnValue());
    }
}
