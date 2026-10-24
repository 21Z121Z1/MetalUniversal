package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.TerrainSceneSnapshot;
import com.metallum.client.sodium.SodiumMultiDrawMerge;
import com.metallum.client.sodium.SodiumPerformanceOptions;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.VKMultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VKMultiDrawBatch.class, remap = false)
public abstract class VKMultiDrawBatchMergeMixin extends MultiDrawBatch {
    @Shadow @Final private long pCommands;

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void metallum$mergeContiguousRanges(DrawContext context, CallbackInfo ci) {
        if (!SodiumPerformanceOptions.drawMergeEnabled()
                || this.size < 2
                || TerrainSceneSnapshot.captureEnabled()) {
            return;
        }

        SodiumMultiDrawMerge.Result merged = SodiumMultiDrawMerge.merge(this.pCommands, this.size);
        if (merged == null) return;

        context.getPass().multiDrawIndexed(merged.commands(), 1, 0, merged.drawCount());
        ci.cancel();
    }
}
