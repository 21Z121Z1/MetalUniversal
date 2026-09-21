package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Carries the submitting entity owner through hand-written custom geometry.
 *
 * Minecraft 26.2 constructs CustomFeatureRenderer.Submit with poseStack.last().copy(), so this
 * Pose is unique to the submit and is available again at CustomGeometryRenderer.render. Using it as
 * the carrier avoids depending on compiler locals or grouping order.
 */
@Mixin(CustomFeatureRenderer.Submit.class)
public abstract class CustomFeatureSubmitMetalFxMixin {
    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void metallum$captureEntityOwner(
            final PoseStack.Pose pose,
            final RenderType renderType,
            final SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.captureModelSubmit(pose);
    }
}
