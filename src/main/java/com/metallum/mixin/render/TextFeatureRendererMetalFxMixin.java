package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(TextFeatureRenderer.class)
public abstract class TextFeatureRendererMetalFxMixin {
    @ModifyVariable(method = "buildGroup", at = @At("HEAD"), argsOnly = true)
    private List<TextFeatureRenderer.Submit> metallum$activateMotionOwner(
            final List<TextFeatureRenderer.Submit> submits
    ) {
        return MetalEntityMotionCapture.activateBuildSampleOnAccess(submits);
    }

    @Inject(method = "buildGroup", at = @At("RETURN"))
    private void metallum$clearMotionOwner(
            final FeatureFrameContext context,
            final List<TextFeatureRenderer.Submit> submits,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.endModelBuild();
    }
}
