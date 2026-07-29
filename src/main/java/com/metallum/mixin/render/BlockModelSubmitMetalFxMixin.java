package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Records the entity or block-entity owner of one staged block-model pose. */
@Mixin(BlockModelFeatureRenderer.Submit.class)
public abstract class BlockModelSubmitMetalFxMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void metallum$captureOwner(
            final PoseStack.Pose pose,
            final RenderType renderType,
            final List<?> modelParts,
            final int[] tintLayers,
            final int lightCoords,
            final int overlayCoords,
            final int tintColor,
            final PoseStack.Pose sheetedDecalPose,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.captureModelSubmit(this, pose);
    }
}
