package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import com.metallum.client.metal.render.MetalFxManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FlameFeatureRenderer.Submit.class)
public abstract class FlameFeatureSubmitMetalFxMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void metallum$observeSharedFlameDraw(
            final PoseStack.Pose pose,
            final EntityRenderState entityRenderState,
            final Quaternionf rotation,
            final CallbackInfo ci
    ) {
        MetalFxManager.observeUnresolvedSharedAuxiliaryMotion();
        MetalEntityMotionCapture.rejectCurrentExactAuxiliary("flame-shared-staged-draw");
    }
}
