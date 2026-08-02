package com.metallum.mixin.iris;

import com.metallum.client.validation.IrisMetalValidationClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Validation-only driver for physical Iris-on-Metal lifecycle runs. */
@Mixin(Minecraft.class)
abstract class IrisMetalLifecycleValidationMixin {
    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void metallum$beforeIrisLifecycleFrame(
            final boolean renderLevel,
            final CallbackInfo ci
    ) {
        IrisMetalValidationClient.beforeFrame(renderLevel);
    }

    @Inject(method = "renderFrame", at = @At("RETURN"))
    private void metallum$afterIrisLifecycleFrame(
            final boolean renderLevel,
            final CallbackInfo ci
    ) {
        IrisMetalValidationClient.afterFrame(renderLevel);
    }
}
