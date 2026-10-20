package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalParticleBatchMotion;
import com.metallum.client.metal.render.MetalFxManager;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.oit.OitStage;
import org.jspecify.annotations.Nullable;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(QuadParticleFeatureRenderer.class)
public abstract class QuadParticleFeatureRendererMetalFxMixin {
    @WrapOperation(method = "drawLayers", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/RenderPass;drawIndexed(IIIII)V"))
    private static void metallum$particleSourceDraw(final RenderPass pass, final int indexCount,
            final int instanceCount, final int firstIndex, final int baseVertex, final int firstInstance,
            final Operation<Void> original, @Local final StagedVertexBuffer.ExecuteInfo info,
            @Local(argsOnly = true) final @Nullable OitStage stage) {
        original.call(pass, indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
        MetalFxManager.drawStagedMotion(info, stage, pass);
    }

    @Inject(method = "prepareGroup", at = @At("HEAD"))
    private void metallum$beginParticleFeatureGroup(
            final FeatureFrameContext context,
            final List<QuadParticleFeatureRenderer.Submit> submits,
            final boolean strictlyOrdered,
            final CallbackInfo ci
    ) {
        MetalParticleBatchMotion.beginFeatureGroup(submits);
    }

    @Inject(method = "prepareGroup", at = @At("RETURN"))
    private void metallum$endParticleFeatureGroup(
            final FeatureFrameContext context,
            final List<QuadParticleFeatureRenderer.Submit> submits,
            final boolean strictlyOrdered,
            final CallbackInfo ci
    ) {
        MetalParticleBatchMotion.endFeatureGroup();
    }
}
