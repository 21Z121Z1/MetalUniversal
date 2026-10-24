package com.metallum.mixin.sodium;

import com.metallum.client.sodium.SodiumPerformanceOptions;
import it.unimi.dsi.fastutil.longs.Long2ReferenceLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSection;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opt-in clone-cache tuning for Sodium 0.9.3.
 *
 * <p>The cache is access ordered: an acquire moves the entry to the tail and
 * refreshes its timestamp. Expired entries therefore form a prefix, allowing
 * cleanup to stop after the first live entry while preserving the same
 * eviction result as Sodium's full predicate walk.
 */
@Mixin(value = ClonedChunkSectionCache.class, remap = false)
public abstract class ClonedChunkSectionCacheOptimizationMixin {
    @Shadow @Final private static long MAX_CACHE_DURATION;
    @Shadow @Final private Long2ReferenceLinkedOpenHashMap<ClonedChunkSection> positionToEntry;
    @Shadow private long time;

    @ModifyConstant(method = "acquire", constant = @Constant(intValue = 512))
    private int metallum$cloneCacheCapacity(int sodiumCapacity) {
        return SodiumPerformanceOptions.cloneCacheEntries();
    }

    @Inject(method = "cleanup", at = @At("HEAD"), cancellable = true)
    private void metallum$cleanupExpiredPrefix(CallbackInfo ci) {
        if (!SodiumPerformanceOptions.cloneCacheCleanupEnabled()) {
            return;
        }

        this.time = System.nanoTime();
        long now = this.time;
        ObjectIterator<ClonedChunkSection> iterator = this.positionToEntry.values().iterator();

        while (iterator.hasNext()) {
            ClonedChunkSection section = iterator.next();
            if (now > section.getLastUsedTimestamp() + MAX_CACHE_DURATION) {
                iterator.remove();
                continue;
            }
            break;
        }

        ci.cancel();
    }
}
