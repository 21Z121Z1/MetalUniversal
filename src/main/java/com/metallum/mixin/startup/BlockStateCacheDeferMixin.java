package com.metallum.mixin.startup;

import com.metallum.client.startup.StartupPerformance;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Blocks.class)
public abstract class BlockStateCacheDeferMixin {
    @Redirect(
            method = "lambda$static$429",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;initCache()V"
            )
    )
    private static void metallum$deferStateCache(BlockState state) {
        if (StartupPerformance.parallelBlockStateCacheEnabled()) {
            StartupPerformance.deferBlockState(state);
        } else {
            state.initCache();
        }
    }
}
