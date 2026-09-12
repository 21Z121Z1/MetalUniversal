package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(NameTagFeatureRenderer.class)
public abstract class NameTagFeatureRendererMetalFxMixin {
    @ModifyVariable(method = "buildGroup", at = @At("HEAD"), argsOnly = true)
    private List<NameTagFeatureRenderer.Submit> metallum$activateMotionOwner(
            final List<NameTagFeatureRenderer.Submit> submits
    ) {
        return MetalEntityMotionCapture.activateBuildSampleOnAccess(submits);
    }

    @Inject(method = "buildGroup", at = @At("RETURN"))
    private void metallum$clearMotionOwner(
            final FeatureFrameContext context,
            final List<NameTagFeatureRenderer.Submit> submits,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.endModelBuild();
    }
}
