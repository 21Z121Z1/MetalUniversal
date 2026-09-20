package com.metallum.e2e;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Loaded only by the opt-in gameplay harness, never the shipping mod. */
@Mixin(Minecraft.class)
abstract class GameplayFrameMixin {
    @Inject(method = "renderFrame", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V", shift = At.Shift.AFTER))
    private void metallum$recordSourcePresent(boolean advanceGameTime, CallbackInfo ci) {
        VanillaGameplay.sourceFramePresented((Minecraft) (Object) this);
    }
}
