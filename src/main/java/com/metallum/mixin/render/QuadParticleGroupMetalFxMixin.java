package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalParticleBatchMotion;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(QuadParticleGroup.class)
public abstract class QuadParticleGroupMetalFxMixin {
    @Redirect(
            method = "extractRenderState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/particle/SingleQuadParticle;extract(Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;Lnet/minecraft/client/Camera;F)V"
            )
    )
    private void metallum$captureParticleIdentity(
            final SingleQuadParticle particle,
            final QuadParticleRenderState state,
            final Camera camera,
            final float partialTickTime
    ) {
        boolean opened = MetalParticleBatchMotion.beginParticleExtract(particle, state);
        try {
            particle.extract(state, camera, partialTickTime);
        } finally {
            MetalParticleBatchMotion.endParticleExtract(opened);
        }
    }
}
