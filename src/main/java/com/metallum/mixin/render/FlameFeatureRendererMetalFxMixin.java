package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Owns Minecraft 26.2's single Flame shared builder as one exact staged-motion batch. */
@Mixin(FlameFeatureRenderer.class)
public abstract class FlameFeatureRendererMetalFxMixin {
    @Inject(method = "buildGroup", at = @At("HEAD"))
    private void metallum$beginSharedFlameMotion(
            final FeatureFrameContext context,
            final List<FlameFeatureRenderer.Submit> submits,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.beginSharedFlameBuild(submits);
    }

    @Inject(method = "buildGroup", at = @At("RETURN"))
    private void metallum$endSharedFlameMotion(
            final FeatureFrameContext context,
            final List<FlameFeatureRenderer.Submit> submits,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.endModelBuild();
    }
}
