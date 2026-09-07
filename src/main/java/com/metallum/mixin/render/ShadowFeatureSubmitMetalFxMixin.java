package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ShadowFeatureRenderer.Submit.class)
public abstract class ShadowFeatureSubmitMetalFxMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void metallum$observeSharedShadowDraw(
            final Matrix4fc pose,
            final float radius,
            final List<EntityRenderState.ShadowPiece> pieces,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.rejectCurrentExactAuxiliary("shadow-shared-staged-draw");
    }
}
