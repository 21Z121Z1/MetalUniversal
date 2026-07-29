package com.metallum.mixin.render;

import com.llamalad7.mixinextras.sugar.Local;
import com.metallum.client.metal.render.MetalEntityMotionCapture;
import com.metallum.client.metal.render.MetalMotionHooks;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Brackets each block-model submit before its staged vertex builder is requested. */
@Mixin(BlockModelFeatureRenderer.class)
public abstract class BlockModelFeatureRendererMetalFxMixin {
    @Inject(
            method = MetalMotionHooks.BUILD_GROUP_METHOD + MetalMotionHooks.BUILD_GROUP_DESCRIPTOR,
            at = @At(value = "INVOKE", target = MetalMotionHooks.BLOCK_MODEL_GET_VERTEX_BUILDER_TARGET)
    )
    private void metallum$beginMotionBlock(
            final FeatureFrameContext context,
            final List<BlockModelFeatureRenderer.Submit> submits,
            final CallbackInfo ci,
            @Local final BlockModelFeatureRenderer.Submit submit
    ) {
        MetalEntityMotionCapture.beginModelBuildForPose(submit.pose());
    }

    @Inject(method = MetalMotionHooks.BUILD_GROUP_METHOD + MetalMotionHooks.BUILD_GROUP_DESCRIPTOR,
            at = @At("RETURN"))
    private void metallum$endMotionBlock(
            final FeatureFrameContext context,
            final List<BlockModelFeatureRenderer.Submit> submits,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.endModelBuild();
    }
}
