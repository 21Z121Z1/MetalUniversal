package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFxManager;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Counts real feature submissions while the dispatcher executes its translucent phase. */
@Mixin(RenderTypeFeatureRenderer.class)
public abstract class RenderTypeFeatureRendererMetalFxMixin {
    @Inject(method = "executeGroup", at = @At("HEAD"))
    private void metallum$observeTranslucentFeatureGroup(
            final FeatureFrameContext context,
            final int groupIndex,
            final List<? extends SubmitNode> submits,
            final boolean strictlyOrdered,
            final CallbackInfo ci
    ) {
        MetalFxManager.observeTransparencyActivity(submits == null ? 0 : submits.size());
    }
}
