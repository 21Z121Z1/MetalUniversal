package com.metallum.mixin.render;

import com.metallum.client.validation.BackendFrameComparisonClient;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pins render interpolation input for cross-backend comparison captures only. */
@Mixin(DeltaTracker.Timer.class)
abstract class BackendFrameComparisonDeltaTrackerMixin {
    @Inject(method = "getGameTimeDeltaPartialTick", at = @At("HEAD"), cancellable = true)
    private void metallum$fixComparisonPartialTick(
            final boolean ignoreFreeze,
            final CallbackInfoReturnable<Float> callbackInfo
    ) {
        if (Boolean.getBoolean("metallum.backend.compare.enabled")) {
            callbackInfo.setReturnValue(BackendFrameComparisonClient.fixedPartialTick());
        }
    }
}
