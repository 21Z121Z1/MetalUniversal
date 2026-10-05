package com.metallum.mixin.qos;

import com.metallum.client.platform.AppleThreadQos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.util.Util$2")
public abstract class WorkerThreadQosMixin {
    @Inject(method = "onStart", at = @At("HEAD"))
    private void metallum$workerQos(CallbackInfo ci) {
        AppleThreadQos.apply("worker");
    }
}
