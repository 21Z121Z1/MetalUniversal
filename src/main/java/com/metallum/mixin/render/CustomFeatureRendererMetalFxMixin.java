package com.metallum.mixin.render;

import com.llamalad7.mixinextras.sugar.Local;
import com.metallum.client.metal.render.MetalEntityMotionCapture;
import com.metallum.client.metal.render.MetalMotionHooks;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Brackets custom geometry before its exact staged draw is allocated. */
@Mixin(CustomFeatureRenderer.class)
public abstract class CustomFeatureRendererMetalFxMixin {
    @Inject(
            method = MetalMotionHooks.BUILD_GROUP_METHOD + MetalMotionHooks.BUILD_GROUP_DESCRIPTOR,
            at = @At(value = "INVOKE", target = MetalMotionHooks.CUSTOM_GET_VERTEX_BUILDER_TARGET)
    )
    private void metallum$beginMotionCustom(
            final FeatureFrameContext context,
            final List<CustomFeatureRenderer.Submit> submits,
            final CallbackInfo ci,
            @Local final CustomFeatureRenderer.Submit submit
    ) {
        MetalEntityMotionCapture.beginModelBuildForPose(submit.pose());
    }

    @Inject(method = MetalMotionHooks.BUILD_GROUP_METHOD + MetalMotionHooks.BUILD_GROUP_DESCRIPTOR,
            at = @At("RETURN"))
    private void metallum$endMotionCustom(
            final FeatureFrameContext context,
            final List<CustomFeatureRenderer.Submit> submits,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.endModelBuild();
    }
}
