package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFxManager;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PreparedRenderType.class)
public abstract class PreparedRenderTypeMetalFxMixin {
    @Inject(
            method = "drawFromBuffer(Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
            at = @At("RETURN")
    )
    private void metallum$drawObjectMotion(
            final StagedVertexBuffer.ExecuteInfo executeInfo,
            final RenderPass renderPass,
            final CallbackInfo ci
    ) {
        MetalFxManager.drawStagedMotion(executeInfo, null, renderPass);
    }

    @Inject(method = "drawFromBufferOit", at = @At("RETURN"))
    private void metallum$drawOitMotion(final StagedVertexBuffer.ExecuteInfo executeInfo,
                                      final OitStage stage, final RenderPass renderPass, final CallbackInfo ci) {
        MetalFxManager.drawStagedMotion(executeInfo, stage, renderPass);
    }
}
