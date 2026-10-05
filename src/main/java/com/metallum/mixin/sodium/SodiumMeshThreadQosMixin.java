package com.metallum.mixin.sodium;

import com.metallum.client.platform.AppleThreadQos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder$WorkerRunnable", remap = false)
public abstract class SodiumMeshThreadQosMixin {
    @Inject(method = "run", at = @At("HEAD"))
    private void metallum$meshQos(CallbackInfo ci) {
        AppleThreadQos.apply("mesh");
    }
}
