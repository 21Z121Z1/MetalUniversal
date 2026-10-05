package com.metallum.mixin.sodium;

import com.metallum.client.sodium.SodiumCullRecoveryPolicy;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.async.CullTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Preserves a graph invalidation when Sodium cancels an async cull before it starts.
 *
 * <p>Sodium clears {@code needsGraphUpdate} when it submits work. If that queued
 * work is cancelled at the beginning of a later {@code prepareRenderTrees} call,
 * restoring the dirty bit guarantees that the same frame can submit replacement
 * work instead of waiting for an unrelated future invalidation.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class SodiumCullRecoveryMixin {
    @Shadow private CullTask pendingTask;
    @Shadow private boolean needsGraphUpdate;

    @Unique
    private CullTask metallum$pendingAtPrepareStart;

    @Inject(method = "prepareRenderTrees", at = @At("HEAD"))
    private void metallum$capturePendingCull(CallbackInfo ci) {
        this.metallum$pendingAtPrepareStart = this.pendingTask;
    }

    @Inject(
            method = "prepareRenderTrees",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;consumeCullTaskResults(Z)V"
            )
    )
    private void metallum$restoreCancelledInvalidation(CallbackInfo ci) {
        if (SodiumCullRecoveryPolicy.shouldRestoreInvalidation(
                this.metallum$pendingAtPrepareStart != null,
                this.pendingTask != null
        )) {
            this.needsGraphUpdate = true;
        }
        this.metallum$pendingAtPrepareStart = null;
    }
}
