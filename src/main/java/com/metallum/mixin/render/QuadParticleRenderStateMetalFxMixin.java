package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalParticleBatchMotion;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rejects frame interpolation only when a quad-particle group is actually submitted.
 *
 * QuadParticleRenderState stores current extracted position/rotation/scale but no previous pose.
 * Its submit method invokes submitQuadParticleGroup only when particleCount > 0, so this hook does
 * not suppress interpolation merely because ParticleEngine.extract ran with an empty group.
 */
@Mixin(QuadParticleRenderState.class)
public abstract class QuadParticleRenderStateMetalFxMixin {
    @Shadow
    private int particleCount;

    @Inject(method = "add", at = @At("HEAD"))
    private void metallum$captureParticleAdd(
            final SingleQuadParticle.Layer layer,
            final float x,
            final float y,
            final float z,
            final float xRot,
            final float yRot,
            final float zRot,
            final float wRot,
            final float scale,
            final float u0,
            final float u1,
            final float v0,
            final float v1,
            final int color,
            final int lightCoords,
            final CallbackInfo ci
    ) {
        MetalParticleBatchMotion.recordParticleAdd((QuadParticleRenderState) (Object) this, layer);
    }

    @Inject(
            method = "submit",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitQuadParticleGroup(Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;)V"
            )
    )
    private void metallum$observeSubmittedParticles(
            final SubmitNodeCollector submitNodeCollector,
            final CameraRenderState camera,
            final CallbackInfo ci
    ) {
        MetalParticleBatchMotion.observeSubmittedState(
                (QuadParticleRenderState) (Object) this,
                particleCount
        );
    }
}
