package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFxManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures block-entity lifetime at extraction and brackets the exact renderer invocation. */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMetalFxMixin {
    @Inject(method = "tryExtractRenderState", at = @At("RETURN"))
    private <E extends BlockEntity, S extends BlockEntityRenderState> void metallum$captureBlockEntityMotion(
            final E blockEntity,
            final float partialTicks,
            final ModelFeatureRenderer.CrumblingOverlay breakProgress,
            final boolean isGloballyRendered,
            final CallbackInfoReturnable<S> cir
    ) {
        S state = cir.getReturnValue();
        if (state != null) {
            MetalFxManager.captureBlockEntityMotion(blockEntity, state);
        }
    }

    @Redirect(
            method = "submit",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;submit(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"
            )
    )
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void metallum$submitBlockEntity(
            final BlockEntityRenderer renderer,
            final BlockEntityRenderState state,
            final PoseStack poseStack,
            final SubmitNodeCollector submitNodeCollector,
            final CameraRenderState camera
    ) {
        MetalFxManager.beginBlockEntitySubmission(state);
        try {
            renderer.submit(state, poseStack, submitNodeCollector, camera);
        } finally {
            // The outer dispatcher still owns Minecraft's CrashReport catch. This finally only restores
            // Metallum's lexical owner, including nested or exceptional renderer submissions.
            MetalFxManager.endBlockEntitySubmission();
        }
    }
}
