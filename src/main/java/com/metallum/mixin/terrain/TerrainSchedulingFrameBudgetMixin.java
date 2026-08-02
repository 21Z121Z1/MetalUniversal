package com.metallum.mixin.terrain;

import com.metallum.client.performance.IrisMetalFrameBudgetController;
import com.metallum.client.terrain.TerrainSchedulingController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies the display-oriented budget after Sodium's existing policy is computed. */
@Mixin(TerrainSchedulingController.class)
public abstract class TerrainSchedulingFrameBudgetMixin {
    @Inject(method = "overrideBuildBudget", at = @At("RETURN"), cancellable = true, remap = false)
    private void metallum$clampBuildBudget(long sodiumBudget, CallbackInfoReturnable<Long> cir) {
        cir.setReturnValue(admit(cir.getReturnValue(), IrisMetalFrameBudgetController.WorkCategory.BACKGROUND_VISIBLE));
    }

    @Inject(method = "overrideUploadBudget", at = @At("RETURN"), cancellable = true, remap = false)
    private void metallum$clampUploadBudget(long sodiumBudget, CallbackInfoReturnable<Long> cir) {
        cir.setReturnValue(admit(cir.getReturnValue(), IrisMetalFrameBudgetController.WorkCategory.VISIBLE_NEAR_CAMERA));
    }

    private static long admit(long requested, IrisMetalFrameBudgetController.WorkCategory category) {
        IrisMetalFrameBudgetController controller = IrisMetalFrameBudgetController.runtime();
        if (!controller.isEnabled()) return requested;
        long now = System.nanoTime();
        long clamped = controller.clampBudget(category, requested, now);
        if (clamped <= 0L) return 0L;
        return controller.reserve(category, clamped, now) ? clamped : 0L;
    }
}
