package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Owns Minecraft 26.2's single shared shadow builder as one exact staged-motion batch. */
@Mixin(ShadowFeatureRenderer.class)
public abstract class ShadowFeatureRendererMetalFxMixin {
    @Inject(method = "buildGroup", at = @At("HEAD"))
    private void metallum$beginSharedShadowMotion(
            final FeatureFrameContext context,
            final List<ShadowFeatureRenderer.Submit> submits,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.beginSharedShadowBuild(submits);
    }

    @Inject(method = "buildGroup", at = @At("RETURN"))
    private void metallum$endSharedShadowMotion(
            final FeatureFrameContext context,
            final List<ShadowFeatureRenderer.Submit> submits,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.endModelBuild();
    }
}
