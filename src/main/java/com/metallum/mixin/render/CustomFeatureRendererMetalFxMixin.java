package com.metallum.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.metal.render.MetalEntityMotionCapture;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Activates the exact owner only for the custom-geometry callback that emits this submit's vertices. */
@Mixin(CustomFeatureRenderer.class)
public abstract class CustomFeatureRendererMetalFxMixin {
    @Inject(method = "buildGroup", at = @At("HEAD"))
    private void metallum$observeCustomGeometry(
            final net.minecraft.client.renderer.feature.FeatureFrameContext context,
            final List<CustomFeatureRenderer.Submit> submits,
            final CallbackInfo ci
    ) {
        int unowned = 0;
        if (submits != null) {
            for (CustomFeatureRenderer.Submit submit : submits) {
                if (submit == null || !MetalEntityMotionCapture.hasModelSubmitOwner(submit.pose())) {
                    unowned++;
                }
            }
        }
        MetalFxManager.observeModdedRenderer(unowned);
    }

    @WrapOperation(
            method = "buildGroup",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;render(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V"
            )
    )
    private void metallum$bracketCustomGeometry(
            final SubmitNodeCollector.CustomGeometryRenderer renderer,
            final PoseStack.Pose pose,
            final VertexConsumer buffer,
            final Operation<Void> original
    ) {
        MetalEntityMotionCapture.beginModelBuild(pose);
        try {
            original.call(renderer, pose, buffer);
        } finally {
            MetalEntityMotionCapture.endModelBuild();
        }
    }
}
