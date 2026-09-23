package com.metallum.e2e.mixin;

import com.metallum.e2e.VanillaGameplay;
import com.mojang.renderpearl.api.device.GpuSurface;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Loaded only by the opt-in gameplay harness, never the shipping mod. */
@Mixin(Minecraft.class)
abstract class GameplayFrameMixin {
    @Shadow private GpuSurface windowSurface;
    @Inject(method = "renderFrame", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V", shift = At.Shift.AFTER))
    private void metallum$recordSourcePresent(boolean advanceGameTime, CallbackInfo ci) {
        VanillaGameplay.sourceFramePresented((Minecraft) (Object) this, this.windowSurface.currentConfiguration().orElse(null));
    }
}
