package com.metallum.mixin.sodium;

import com.metallum.client.platform.AppleThreadQos;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class SodiumCullThreadQosMixin {
    @ModifyVariable(method = "makeAsyncCullThread", at = @At("HEAD"), argsOnly = true)
    private static Runnable metallum$wrapCullRunnable(Runnable runnable) {
        return () -> {
            AppleThreadQos.apply("cull");
            runnable.run();
        };
    }
}
