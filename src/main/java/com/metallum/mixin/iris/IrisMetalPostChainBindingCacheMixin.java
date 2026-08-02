package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalPerformanceCounters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Caches immutable resource classification and sampler requirements for a
 * pinned Iris generation.
 */
@Mixin(targets = "com.metallum.client.metal.render.IrisMetalPostChain")
public abstract class IrisMetalPostChainBindingCacheMixin {
    @Unique
    private static final ConcurrentMap<String, Integer> metallum$renderTargetIndices =
            new ConcurrentHashMap<>();
    @Unique
    private static final ConcurrentMap<String, Integer> metallum$colorImageIndices =
            new ConcurrentHashMap<>();
    @Unique
    private final ConcurrentMap<String, Boolean> metallum$requiredSamplers =
            new ConcurrentHashMap<>();
    @Unique
    private final ConcurrentMap<String, Set<String>> metallum$samplerTypes =
            new ConcurrentHashMap<>();

    @Inject(method = "renderTargetIndex", at = @At("HEAD"), cancellable = true)
    private static void metallum$reuseRenderTargetIndex(
            final String name,
            final CallbackInfoReturnable<Integer> cir
    ) {
        Integer cached = metallum$renderTargetIndices.get(name);
        if (cached != null) {
            IrisMetalPerformanceCounters.recordBindingClassificationCacheHit();
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "renderTargetIndex", at = @At("RETURN"))
    private static void metallum$rememberRenderTargetIndex(
            final String name,
            final CallbackInfoReturnable<Integer> cir
    ) {
        metallum$renderTargetIndices.putIfAbsent(name, cir.getReturnValue());
    }

    @Inject(method = "colorImageIndex", at = @At("HEAD"), cancellable = true)
    private static void metallum$reuseColorImageIndex(
            final String name,
            final CallbackInfoReturnable<Integer> cir
    ) {
        Integer cached = metallum$colorImageIndices.get(name);
        if (cached != null) {
            IrisMetalPerformanceCounters.recordBindingClassificationCacheHit();
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "colorImageIndex", at = @At("RETURN"))
    private static void metallum$rememberColorImageIndex(
            final String name,
            final CallbackInfoReturnable<Integer> cir
    ) {
        metallum$colorImageIndices.putIfAbsent(name, cir.getReturnValue());
    }

    @Inject(method = "requiresSampler", at = @At("HEAD"), cancellable = true)
    private void metallum$reuseRequiredSampler(
            final String name,
            final CallbackInfoReturnable<Boolean> cir
    ) {
        Boolean cached = this.metallum$requiredSamplers.get(name);
        if (cached != null) {
            IrisMetalPerformanceCounters.recordBindingClassificationCacheHit();
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "requiresSampler", at = @At("RETURN"))
    private void metallum$rememberRequiredSampler(
            final String name,
            final CallbackInfoReturnable<Boolean> cir
    ) {
        this.metallum$requiredSamplers.putIfAbsent(name, cir.getReturnValue());
    }

    @Inject(method = "samplerTypes", at = @At("HEAD"), cancellable = true)
    private void metallum$reuseSamplerTypes(
            final String name,
            final CallbackInfoReturnable<Set<String>> cir
    ) {
        Set<String> cached = this.metallum$samplerTypes.get(name);
        if (cached != null) {
            IrisMetalPerformanceCounters.recordBindingClassificationCacheHit();
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "samplerTypes", at = @At("RETURN"))
    private void metallum$rememberSamplerTypes(
            final String name,
            final CallbackInfoReturnable<Set<String>> cir
    ) {
        this.metallum$samplerTypes.putIfAbsent(name, cir.getReturnValue());
    }
}
