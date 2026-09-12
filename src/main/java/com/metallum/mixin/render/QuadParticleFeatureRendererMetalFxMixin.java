package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalParticleBatchMotion;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(QuadParticleFeatureRenderer.class)
public abstract class QuadParticleFeatureRendererMetalFxMixin {
    @Inject(method = "prepareGroup", at = @At("HEAD"))
    private void metallum$beginParticleFeatureGroup(
            final FeatureFrameContext context,
            final List<QuadParticleFeatureRenderer.Submit> submits,
            final boolean strictlyOrdered,
            final CallbackInfo ci
    ) {
        MetalParticleBatchMotion.beginFeatureGroup(submits);
    }

    @Inject(method = "prepareGroup", at = @At("RETURN"))
    private void metallum$endParticleFeatureGroup(
            final FeatureFrameContext context,
            final List<QuadParticleFeatureRenderer.Submit> submits,
            final boolean strictlyOrdered,
            final CallbackInfo ci
    ) {
        MetalParticleBatchMotion.endFeatureGroup();
    }
}
