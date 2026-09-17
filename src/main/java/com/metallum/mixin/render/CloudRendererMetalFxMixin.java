package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFxManager;
import net.minecraft.client.renderer.CloudRenderer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records a cloud source draw only at the actual indexed draw boundary. */
@Mixin(CloudRenderer.class)
public abstract class CloudRendererMetalFxMixin {
    @Inject(
        method = "render(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/api/commands/RenderPass;drawIndexed(IIIII)V"
            )
    )
    private void metallum$observeCloudDraw(
            final RenderPass renderPass,
            final RenderPipeline renderPipeline,
            final CallbackInfo ci
    ) {
        MetalFxManager.observeReactiveParticlesWeather(1);
    }
}
