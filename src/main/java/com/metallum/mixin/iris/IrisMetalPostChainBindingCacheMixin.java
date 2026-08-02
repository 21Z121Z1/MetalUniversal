package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalPerformanceCounters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Caches immutable sampler/image name classification for the lifetime of the
 * process. Iris resource aliases are fixed by the pinned Iris contract, so the
 * same names do not need regex parsing for every fullscreen pass and dispatch.
 */
@Mixin(targets = "com.metallum.client.metal.render.IrisMetalPostChain")
public abstract class IrisMetalPostChainBindingCacheMixin {
    @Unique
    private static final ConcurrentMap<String, Integer> metallum$renderTargetIndices =
            new ConcurrentHashMap<>();
    @Unique
    private static final ConcurrentMap<String, Integer> metallum$colorImageIndices =
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
}
