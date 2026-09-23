package com.metallum.mixin.sodium;

import com.mojang.blaze3d.systems.RenderSystem;
import com.metallum.Metallum;
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
            var features = RenderSystem.getDevice().getDeviceInfo().features();
            if (features.multiDrawDirectInterleaved()) {
                // 26.3's Metal/RenderPearl device exposes the same interleaved
                // multi-draw contract used by Sodium's Vulkan backend. The
                // older VK_INDIRECT path requires drawIndirect, which this
                // device correctly reports as unsupported.
                cir.setReturnValue(DrawBackend.VK_MULTIDRAW);
            } else {
                Metallum.LOGGER.error(
                        "Metal device lacks the 26.3 multiDrawDirectInterleaved capability; "
                                + "refusing Sodium terrain backend selection"
                );
                throw new IllegalStateException(
                        "MetalUniversal requires multiDrawDirectInterleaved for Sodium 26.3 terrain"
                );
            }
        }
    }
}
