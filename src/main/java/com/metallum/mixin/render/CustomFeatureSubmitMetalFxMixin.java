package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records the entity owner of painting and other custom staged geometry. */
@Mixin(CustomFeatureRenderer.Submit.class)
public abstract class CustomFeatureSubmitMetalFxMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void metallum$captureOwner(
            final PoseStack.Pose pose,
            final RenderType renderType,
            final CustomGeometryRenderer customGeometryRenderer,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.captureModelSubmit(this, pose);
    }
}
