package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Binds ordinary baked block-model submits to the entity that produced them.
 * Minecraft 26.2 creates this record inside SubmitNodeCollection.submitBlockModel while
 * EntityRenderDispatcher.submit still owns the corresponding entity motion sample.
 */
@Mixin(BlockModelFeatureRenderer.Submit.class)
public abstract class BlockModelFeatureSubmitMetalFxMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void metallum$captureEntityOwner(
            final PoseStack.Pose pose,
            final RenderType renderType,
            final List<BlockStateModelPart> modelParts,
            final int[] tintLayers,
            final int lightCoords,
            final int overlayCoords,
            final int tintColor,
            final PoseStack.@Nullable Pose sheetedDecalPose,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.captureModelSubmit(this);
    }
}
