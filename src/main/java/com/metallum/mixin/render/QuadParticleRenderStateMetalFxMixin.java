package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFxManager;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
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
        MetalFxManager.observeParticleMotion();
    }
}
