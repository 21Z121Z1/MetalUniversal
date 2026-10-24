package com.metallum.mixin.qos;

import com.metallum.client.metal.MacThreadQos;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class RenderThreadQosMixin {
    @Inject(method = "run", at = @At("HEAD"))
    private void metallum$applyRenderQos(CallbackInfo ci) {
        MacThreadQos.apply("render");
    }
}
