package com.metallum.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.metal.render.MetalEntityMotionCapture;
import com.metallum.client.metal.render.MetalEntityObjectPose;
import com.metallum.client.metal.render.MetalMotionHooks;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EndCrystalRenderer;
import net.minecraft.client.renderer.entity.EnderDragonRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Separates the crystal model root from the post-pop beam root. */
@Mixin(EndCrystalRenderer.class)
public abstract class EndCrystalRendererMetalFxMixin {
    @WrapOperation(
            method = MetalMotionHooks.END_CRYSTAL_SUBMIT_NAME
                    + MetalMotionHooks.END_CRYSTAL_SUBMIT_DESCRIPTOR,
            at = @At(
                    value = "INVOKE",
                    target = MetalMotionHooks.SUBMIT_MODEL_TARGET
            )
    )
    private <S> void metallum$model(
            final SubmitNodeCollector collector,
            final Model<? super S> model,
            final S state,
            final PoseStack poseStack,
            final Identifier texture,
            final int lightCoords,
            final int overlayCoords,
            final int outlineColor,
            final ModelFeatureRenderer.CrumblingOverlay crumblingOverlay,
            final Operation<Void> original
    ) {
        MetalEntityMotionCapture.beginEntityPart(MetalEntityObjectPose.EntityPart.END_CRYSTAL_MODEL);
        try {
            original.call(
                    collector,
                    model,
                    state,
                    poseStack,
                    texture,
                    lightCoords,
                    overlayCoords,
                    outlineColor,
                    crumblingOverlay
            );
        } finally {
            MetalEntityMotionCapture.endEntityPart();
        }
    }

    @WrapOperation(
            method = MetalMotionHooks.END_CRYSTAL_SUBMIT_NAME
                    + MetalMotionHooks.END_CRYSTAL_SUBMIT_DESCRIPTOR,
            at = @At(
                    value = "INVOKE",
                    target = MetalMotionHooks.CRYSTAL_BEAMS_TARGET
            )
    )
    private void metallum$beam(
            final float deltaX,
            final float deltaY,
            final float deltaZ,
            final float timeInTicks,
            final PoseStack poseStack,
            final SubmitNodeCollector collector,
            final int lightCoords,
            final Operation<Void> original
    ) {
        MetalEntityMotionCapture.beginEntityPart(MetalEntityObjectPose.EntityPart.END_CRYSTAL_BEAM);
        try {
            original.call(
                    deltaX,
                    deltaY,
                    deltaZ,
                    timeInTicks,
                    poseStack,
                    collector,
                    lightCoords
            );
        } finally {
            MetalEntityMotionCapture.endEntityPart();
        }
    }
}
