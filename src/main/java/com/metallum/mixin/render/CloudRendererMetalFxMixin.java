package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFxManager;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records a cloud source draw only at the actual indexed draw boundary. */
@Mixin(CloudRenderer.class)
public abstract class CloudRendererMetalFxMixin {
    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderPass;drawIndexed(IIIII)V"
            )
    )
    private void metallum$observeCloudDraw(
            final int color,
            final CloudStatus cloudStatus,
            final float bottomY,
            final int range,
            final Vec3 cameraPosition,
            final long gameTime,
            final float partialTicks,
            final CallbackInfo ci
    ) {
        MetalFxManager.observeReactiveParticlesWeather(1);
    }
}
