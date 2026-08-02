package com.metallum.mixin.render;

import com.metallum.client.validation.BackendFrameComparisonClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies the opt-in backend comparison hook even when the selected backend is Vulkan. */
@Mixin(Minecraft.class)
abstract class BackendFrameComparisonMixin {
    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void metallum$beforeFrame(final boolean renderLevel, final CallbackInfo ci) {
        BackendFrameComparisonClient.beforeFrame(renderLevel);
    }

    @Inject(method = "renderFrame", at = @At("RETURN"))
    private void metallum$afterFrame(final boolean renderLevel, final CallbackInfo ci) {
        BackendFrameComparisonClient.afterFrame(
                renderLevel,
                ((Minecraft) (Object) this).gameRenderer
        );
    }
}
