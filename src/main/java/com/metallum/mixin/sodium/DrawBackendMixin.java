package com.metallum.mixin.sodium;

import com.mojang.blaze3d.systems.RenderSystem;
import net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DrawBackend.class)
public class DrawBackendMixin {
    @Inject(method = "chooseBackend", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$chooseMetalBackend(CallbackInfoReturnable<DrawBackend> cir) {
        if (RenderSystem.getDevice().getDeviceInfo().backendName().equals("Metal")) {
            // Sodium's VK_MULTIDRAW representation is a compact, interleaved
            // { firstIndex, indexCount, baseVertex } command array.  The Metal
            // render pass consumes exactly that normalized form and can route
            // qualifying terrain batches through ICB without first uploading a
            // Vulkan-style indirect-command ring buffer.
            cir.setReturnValue(DrawBackend.VK_MULTIDRAW);
        }
    }
}
