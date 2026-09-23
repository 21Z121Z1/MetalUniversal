package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFramePacing;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow private long latestInputTime;

    @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
    private void metallum$applyUserCadence(CallbackInfoReturnable<Integer> result) {
        var window = minecraft.getWindow();
        result.setReturnValue(MetalFramePacing.limit(result.getReturnValue(), window.isFocused(),
                window.isIconified(), Math.max(0L, Util.getMillis() - latestInputTime),
                FabricLoader.getInstance().isModLoaded("dynamic_fps")));
    }
}
