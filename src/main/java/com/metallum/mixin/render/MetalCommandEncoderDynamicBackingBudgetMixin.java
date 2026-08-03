package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalDynamicBackingPoolBudget;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.foreign.MemorySegment;
import java.util.ArrayDeque;

/**
 * Applies a total byte and bucket budget to dynamic uniform backing reuse.
 *
 * <p>The target rotates its deferred destruction queue before this RETURN
 * injection runs. Every handle visible in {@code dynamicBackingPool} is
 * therefore already GPU-safe to retain or release.</p>
 */
@Mixin(targets = "com.metallum.client.metal.render.MetalCommandEncoder")
public abstract class MetalCommandEncoderDynamicBackingBudgetMixin {
    @Unique
    private static final boolean metallum$ENABLED = !"false".equalsIgnoreCase(
            System.getProperty("metallum.opt.dynamicBackingPoolBudget", "true")
    );
    @Unique
    private static final long metallum$MAX_RETAINED_BYTES = Math.max(
            0L,
            Long.getLong("metallum.opt.dynamicBackingPoolBytes", 32L * 1024L * 1024L)
    );
    @Unique
    private static final int metallum$MAX_BUCKETS = Math.max(
            0,
            Integer.getInteger("metallum.opt.dynamicBackingPoolBuckets", 64)
    );
    @Unique
    private static final int metallum$TRIM_INTERVAL = Math.max(
            1,
            Integer.getInteger("metallum.opt.dynamicBackingPoolTrimInterval", 16)
    );

    @Shadow
    @Final
    private Long2ObjectOpenHashMap<ArrayDeque<MemorySegment>> dynamicBackingPool;

    @Unique
    private int metallum$submitsSinceBackingTrim;

    @Inject(method = "submit", at = @At("RETURN"))
    private void metallum$trimDynamicBackingPool(final CallbackInfo ci) {
        if (!metallum$ENABLED || this.dynamicBackingPool.isEmpty()) {
            return;
        }
        this.metallum$submitsSinceBackingTrim++;
        if (this.dynamicBackingPool.size() <= metallum$MAX_BUCKETS
                && this.metallum$submitsSinceBackingTrim < metallum$TRIM_INTERVAL) {
            return;
        }
        this.metallum$submitsSinceBackingTrim = 0;
        MetalDynamicBackingPoolBudget.trim(
                this.dynamicBackingPool,
                metallum$MAX_RETAINED_BYTES,
                metallum$MAX_BUCKETS,
                MetalNativeBridge::metallum_release_object
        );
    }
}
