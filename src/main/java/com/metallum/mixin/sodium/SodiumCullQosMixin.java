package com.metallum.mixin.sodium;

import com.metallum.client.metal.MacThreadQos;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class SodiumCullQosMixin {
    @ModifyVariable(method = "makeAsyncCullThread", at = @At("HEAD"), argsOnly = true)
    private static Runnable metallum$wrapCullQos(Runnable runnable) {
        return () -> {
            MacThreadQos.apply("cull");
            runnable.run();
        };
    }
}
