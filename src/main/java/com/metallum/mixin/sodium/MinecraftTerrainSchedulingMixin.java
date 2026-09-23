package com.metallum.mixin.sodium;

import com.metallum.client.terrain.TerrainSchedulingController;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the real client render-frame interval for the opt-in controller. */
@Mixin(Minecraft.class)
public abstract class MinecraftTerrainSchedulingMixin {
    @Inject(method = "renderFrame", at = @At("HEAD"), remap = false)
    private void metallum$beginTerrainCpuFrame(final boolean renderLevel, final CallbackInfo ci) {
        TerrainSchedulingController controller = TerrainSchedulingController.runtime();
        if (controller.observesFrames()) {
            controller.beginCpuFrame(System.nanoTime());
        }
    }

    @Inject(method = "renderFrame", at = @At("RETURN"), remap = false)
    private void metallum$endTerrainCpuFrame(final boolean renderLevel, final CallbackInfo ci) {
        TerrainSchedulingController controller = TerrainSchedulingController.runtime();
        if (controller.observesFrames()) {
            controller.endCpuFrame(System.nanoTime());
        }
    }
}
