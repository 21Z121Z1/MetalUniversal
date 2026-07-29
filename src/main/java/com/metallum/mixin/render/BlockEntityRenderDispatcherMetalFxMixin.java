package com.metallum.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.metal.render.MetalEntityMotionCapture;
import com.metallum.client.metal.render.MetalFxManager;
import com.metallum.client.metal.render.MetalMotionHooks;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Brackets only the real block-entity renderer submission call. */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMetalFxMixin {
    @WrapOperation(
            method = "submit",
            at = @At(value = "INVOKE", target = MetalMotionHooks.BLOCK_ENTITY_SUBMIT_TARGET)
    )
    private <S extends BlockEntityRenderState> void metallum$captureBlockEntitySubmission(
            final BlockEntityRenderer<?, S> renderer,
            final S state,
            final PoseStack poseStack,
            final SubmitNodeCollector collector,
            final CameraRenderState cameraState,
            final Operation<Void> original
    ) {
        MetalFxManager.captureBlockEntityMotion(state);
        MetalEntityMotionCapture.beginBlockEntitySubmission(state);
        try {
            original.call(renderer, state, poseStack, collector, cameraState);
        } finally {
            MetalEntityMotionCapture.endBlockEntitySubmission();
        }
    }
}
