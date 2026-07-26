package com.metallum.mixin.iris;

import com.metallum.client.metal.render.MetalIrisCompat;
import net.irisshaders.iris.gl.IrisRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code IrisRenderSystem.initRenderer} probes {@code GL.getCapabilities()}
 * to pick a DSA strategy — there is no GL context on the Metal backend.
 */
@Mixin(value = IrisRenderSystem.class, remap = false)
public abstract class IrisRenderSystemCompatMixin {
    @Inject(method = "initRenderer", at = @At("HEAD"), cancellable = true)
    private static void metallum$skipGlCapabilityProbe(final CallbackInfo ci) {
        if (MetalIrisCompat.holdIrisDormant()) {
            ci.cancel();
        }
    }

    /**
     * Called from {@code SamplerLimits.<init>} while
     * {@code IrisRenderSystem.<clinit>} is running; the body reads
     * {@code GL.getCapabilities()}. Method injections still apply mid-clinit,
     * so this is the one seam where the capability probe can be neutralized.
     */
    @Inject(method = "supportsSSBO", at = @At("HEAD"), cancellable = true)
    private static void metallum$noGlSsboCaps(final CallbackInfoReturnable<Boolean> cir) {
        if (MetalIrisCompat.holdIrisDormant()) {
            cir.setReturnValue(false);
        }
    }

    /**
     * The remaining capability probes, all of which read
     * {@code GL.getCapabilities()}. {@code ShaderPack}'s constructor reaches
     * them through {@code FeatureFlags.isUsable} while resolving a pack's
     * declared feature flags, so they are on the pack-loading path, not just
     * the renderer-init path.
     *
     * <p>All report unsupported for B2-1: the semantic layer implements the
     * gbuffer terrain program and nothing else, so a pack must not take a code
     * path that assumes compute, image load/store, per-buffer blending or
     * tessellation is available. A pack that <i>requires</i> one of these is
     * rejected by Iris with its normal "unsupported feature" message, which is
     * the correct outcome rather than a broken render.</p>
     */
    @Inject(
            method = {"supportsImageLoadStore", "supportsBufferBlending", "supportsCompute", "supportsTesselation"},
            at = @At("HEAD"),
            cancellable = true
    )
    private static void metallum$noGlFeatureCaps(final CallbackInfoReturnable<Boolean> cir) {
        if (MetalIrisCompat.holdIrisDormant()) {
            cir.setReturnValue(false);
        }
    }

    /**
     * {@code StandardMacros} enumerates GL extensions with
     * {@code getStringi(GL_EXTENSIONS, i)}. {@link GlStateManagerCompatMixin}
     * already reports {@code GL_NUM_EXTENSIONS == 0}, so the loop never runs;
     * this is a defensive stub so that any other caller gets an empty name
     * rather than a raw {@code glGetStringi} on a device with no GL context.
     */
    @Inject(method = "getStringi", at = @At("HEAD"), cancellable = true)
    private static void metallum$noGlExtensionStrings(
            final int name, final int index, final CallbackInfoReturnable<String> cir
    ) {
        if (MetalIrisCompat.holdIrisDormant()) {
            cir.setReturnValue("");
        }
    }
}
