package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Activates the exact entity sample for each BlockModelFeatureRenderer.Submit before
 * buildGroup calls getVertexBuilder. The source method is a single enhanced-for pass,
 * so list element access is the stable per-submit boundary without relying on compiler locals.
 */
@Mixin(BlockModelFeatureRenderer.class)
public abstract class BlockModelFeatureRendererMetalFxMixin {
    @ModifyVariable(method = "buildGroup", at = @At("HEAD"), argsOnly = true)
    private List<BlockModelFeatureRenderer.Submit> metallum$activateMotionOwner(
            final List<BlockModelFeatureRenderer.Submit> submits
    ) {
        return MetalEntityMotionCapture.activateBuildSampleOnAccess(submits);
    }

    @Inject(method = "buildGroup", at = @At("RETURN"))
    private void metallum$clearMotionOwner(
            final FeatureFrameContext context,
            final List<BlockModelFeatureRenderer.Submit> submits,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.endModelBuild();
    }
}
