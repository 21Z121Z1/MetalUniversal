package com.metallum.mixin.iris;

import com.metallum.client.metal.render.MetalIrisCompat;
import net.irisshaders.iris.Iris;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds Iris dormant on the Metal backend.
 *
 * <p>{@code Iris.onRenderSystemInit} calls {@code GL.getCapabilities()} and
 * registers pack machinery that assumes a GL context; {@code loadShaderpack}
 * would hand the pipeline factory a real pack whose programs compile through
 * {@code glShaderSource}. Cancelling both keeps {@code currentPack} empty so
 * {@code PipelineManager} serves Iris's own {@code VanillaRenderingPipeline}
 * (Metal-safe once its clip-control call is cancelled too, see
 * {@link IrisVanillaPipelineCompatMixin}).</p>
 */
@Mixin(value = Iris.class, remap = false)
public abstract class IrisBootstrapCompatMixin {
    @Inject(method = "onRenderSystemInit", at = @At("HEAD"), cancellable = true)
    private static void metallum$skipGlRendererInit(final CallbackInfo ci) {
        if (MetalIrisCompat.holdIrisDormant()) {
            ci.cancel();
        }
    }

    /**
     * {@code duringRenderSystemInit} -> {@code setDebug} touches
     * {@code IrisRenderSystem} statics, whose {@code <clinit>} reads GL
     * sampler limits — class initialization cannot be cancelled, so the
     * triggering call must be.
     */
    @Inject(method = "duringRenderSystemInit", at = @At("HEAD"), cancellable = true)
    private static void metallum$skipDebugStateInit(final CallbackInfo ci) {
        if (MetalIrisCompat.holdIrisDormant()) {
            ci.cancel();
        }
    }

    /**
     * With the semantic layer active this must NOT be cancelled: the whole
     * point of B2-1 is that Iris parses a real pack, so
     * {@code IrisMetalPipelineOverrides} can translate its
     * {@code gbuffers_terrain} programs. Pack loading itself is CPU-side
     * (zip/properties/preprocessor); the only GL it reaches is
     * {@code StandardMacros}, which {@link GlStateManagerCompatMixin} and
     * {@link IrisRenderSystemCompatMixin} answer with pinned constants.
     */
    @Inject(method = "loadShaderpack", at = @At("HEAD"), cancellable = true)
    private static void metallum$keepPackUnloaded(final CallbackInfo ci) {
        if (MetalIrisCompat.holdIrisDormant() && !MetalIrisCompat.semanticLayerEnabled()) {
            ci.cancel();
        }
    }
}
