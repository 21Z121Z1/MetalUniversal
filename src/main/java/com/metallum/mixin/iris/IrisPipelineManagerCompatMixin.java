package com.metallum.mixin.iris;

import com.metallum.client.metal.render.MetalIrisCompat;
import net.irisshaders.iris.pipeline.PipelineManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code PipelineManager.destroyPipeline} unbinds all sixteen texture units
 * through {@code GlStateManager._activeTexture(GL_TEXTURE0 + i)} /
 * {@code _bindTexture(0)}. Those are not shimmed — and unlike the query
 * primitives, {@code _activeTexture} really reaches
 * {@code GL33C.glActiveTexture} whenever the requested unit differs from its
 * cached value. The cache starts at 0, so {@code i == 0} is silently absorbed
 * and every iteration from {@code i == 1} is a live GL call with no context.
 *
 * <p>This has not fired yet only because the loop iterates over
 * {@code pipelinesPerDimension}, which is empty on the first world load. Any
 * subsequent teardown — leaving a world, changing dimension, F3+R, turning
 * shaders off — runs it with at least one entry.
 *
 * <p>Cancelling is safe: the whole point of the call is to leave GL's texture
 * units in a known state, and on the Metal backend there are none. Bindings
 * live in {@code MetalRenderPass} and are re-established per pass.
 */
@Mixin(value = PipelineManager.class, remap = false)
public abstract class IrisPipelineManagerCompatMixin {
    @Inject(method = "resetTextureState", at = @At("HEAD"), cancellable = true)
    private void metallum$skipGlTextureUnitReset(final CallbackInfo ci) {
        if (MetalIrisCompat.holdIrisDormant()) {
            ci.cancel();
        }
    }
}
