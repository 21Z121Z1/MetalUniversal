package com.metallum.mixin.iris;

import com.metallum.Metallum;
import com.metallum.client.metal.render.IrisMetalPackLifecycle;
import com.metallum.client.metal.render.IrisMetalVertexSerializerBootstrap;
import com.metallum.client.metal.render.MetalActive;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pbr.texture.PBRTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces Iris's OpenGL renderer bootstrap with its Metal-safe CPU lifecycle. */
@Mixin(value = Iris.class, remap = false)
public abstract class IrisBootstrapCompatMixin {
    private static boolean metallum$pbrDefaultsInitialized;

    @Inject(method = "onRenderSystemInit", at = @At("HEAD"), cancellable = true)
    private static void metallum$bootstrapWithoutGl(final CallbackInfo ci) {
        if (!MetalActive.isMetalActive()) {
            return;
        }

        try {
            if (!metallum$pbrDefaultsInitialized) {
                PBRTextureManager.INSTANCE.init();
                metallum$pbrDefaultsInitialized = true;
            }
            IrisMetalVertexSerializerBootstrap.ensureRegistered();
            if (IrisMetalPackLifecycle.shouldLoadConfiguredPack(
                    true, Iris.getIrisConfig().areShadersEnabled()
            )) {
                Iris.loadShaderpack();
            }
        } catch (Throwable throwable) {
            Metallum.LOGGER.error(
                    "[MetalUniversal/Iris] Metal-safe Iris bootstrap failed",
                    throwable
            );
            if (IrisMetalPackLifecycle.strictModeRequested()) {
                throw throwable instanceof RuntimeException runtime
                        ? runtime
                        : new IllegalStateException("Metal Iris bootstrap failed", throwable);
            }
        }
        ci.cancel();
    }

    @Inject(method = "duringRenderSystemInit", at = @At("HEAD"), cancellable = true)
    private static void metallum$skipGlDebugBootstrap(final CallbackInfo ci) {
        if (MetalActive.isMetalActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "loadShaderpack", at = @At("HEAD"), cancellable = true)
    private static void metallum$skipDisabledPackLoad(final CallbackInfo ci) {
        if (!MetalActive.isMetalActive()) {
            return;
        }
        boolean shadersEnabled = Iris.getIrisConfig().areShadersEnabled();
        if (!IrisMetalPackLifecycle.shouldLoadConfiguredPack(true, shadersEnabled)
                && !IrisMetalPackLifecycle.consumeDisabledReloadTransition(true, shadersEnabled)) {
            ci.cancel();
        }
    }
}
