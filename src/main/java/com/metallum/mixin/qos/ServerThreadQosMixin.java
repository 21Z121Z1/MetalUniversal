package com.metallum.mixin.qos;

import com.metallum.client.metal.MacThreadQos;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class ServerThreadQosMixin {
    @Inject(method = "runServer", at = @At("HEAD"))
    private void metallum$applyServerQos(CallbackInfo ci) {
        MacThreadQos.apply("server");
    }
}
