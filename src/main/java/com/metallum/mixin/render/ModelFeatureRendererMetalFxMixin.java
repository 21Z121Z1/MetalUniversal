package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelFeatureRenderer.class)
public abstract class ModelFeatureRendererMetalFxMixin {
    @Inject(method = "prepareModel", at = @At("HEAD"))
    private void metallum$beginMotionModel(
            final ModelFeatureRenderer.Submit<?> submit,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.beginModelBuild(submit);
    }

    @Inject(method = "prepareModel", at = @At("RETURN"))
    private void metallum$endMotionModel(
            final ModelFeatureRenderer.Submit<?> submit,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.endModelBuild();
    }
}
