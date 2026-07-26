package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelFeatureRenderer.Submit.class)
public abstract class ModelFeatureSubmitMetalFxMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void metallum$captureEntityOwner(
            final RenderType renderType,
            final PoseStack.Pose pose,
            final Model<?> model,
            final Object state,
            final int lightCoords,
            final int overlayCoords,
            final int tintedColor,
            final TextureAtlasSprite sprite,
            final PoseStack.Pose sheetedDecalPose,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.captureModelSubmit(this);
    }
}
