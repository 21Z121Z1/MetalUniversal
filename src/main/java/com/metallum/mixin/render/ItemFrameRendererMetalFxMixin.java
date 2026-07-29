package com.metallum.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.metal.render.MetalEntityMotionCapture;
import com.metallum.client.metal.render.MetalEntityObjectPose;
import com.metallum.client.metal.render.MetalMotionHooks;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MapRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.entity.ItemFrameRenderer;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Selects the item-frame base/content sample around vanilla's real submit calls. */
@Mixin(ItemFrameRenderer.class)
public abstract class ItemFrameRendererMetalFxMixin {
    @WrapOperation(
            method = MetalMotionHooks.ITEM_FRAME_SUBMIT_NAME + MetalMotionHooks.ITEM_FRAME_SUBMIT_DESCRIPTOR,
            at = @At(
                    value = "INVOKE",
                    target = MetalMotionHooks.BLOCK_MODEL_SUBMIT_WITH_Z_OFFSET_TARGET
            )
    )
    private void metallum$baseModel(
            final BlockModelRenderState frameModel,
            final PoseStack poseStack,
            final SubmitNodeCollector collector,
            final int lightCoords,
            final int overlayCoords,
            final int outlineColor,
            final Operation<Void> original
    ) {
        MetalEntityMotionCapture.beginEntityPart(MetalEntityObjectPose.EntityPart.ITEM_FRAME_BASE);
        try {
            original.call(frameModel, poseStack, collector, lightCoords, overlayCoords, outlineColor);
        } finally {
            MetalEntityMotionCapture.endEntityPart();
        }
    }

    @WrapOperation(
            method = MetalMotionHooks.ITEM_FRAME_SUBMIT_NAME + MetalMotionHooks.ITEM_FRAME_SUBMIT_DESCRIPTOR,
            at = @At(
                    value = "INVOKE",
                    target = MetalMotionHooks.ITEM_SUBMIT_TARGET
            )
    )
    private void metallum$itemContent(
            final ItemStackRenderState item,
            final PoseStack poseStack,
            final SubmitNodeCollector collector,
            final int lightCoords,
            final int overlayCoords,
            final int outlineColor,
            final Operation<Void> original
    ) {
        MetalEntityMotionCapture.beginEntityPart(MetalEntityObjectPose.EntityPart.ITEM_FRAME_CONTENT);
        try {
            original.call(item, poseStack, collector, lightCoords, overlayCoords, outlineColor);
        } finally {
            MetalEntityMotionCapture.endEntityPart();
        }
    }

    @WrapOperation(
            method = MetalMotionHooks.ITEM_FRAME_SUBMIT_NAME + MetalMotionHooks.ITEM_FRAME_SUBMIT_DESCRIPTOR,
            at = @At(
                    value = "INVOKE",
                    target = MetalMotionHooks.MAP_RENDER_TARGET
            )
    )
    private void metallum$mapContent(
            final MapRenderer mapRenderer,
            final MapRenderState mapRenderState,
            final PoseStack poseStack,
            final SubmitNodeCollector collector,
            final boolean showOnlyFrame,
            final int lightCoords,
            final Operation<Void> original
    ) {
        MetalEntityMotionCapture.beginEntityPart(MetalEntityObjectPose.EntityPart.ITEM_FRAME_CONTENT);
        try {
            original.call(mapRenderer, mapRenderState, poseStack, collector, showOnlyFrame, lightCoords);
        } finally {
            MetalEntityMotionCapture.endEntityPart();
        }
    }
}
