package com.metallum.mixin.iris;

import com.metallum.Metallum;
import com.metallum.client.metal.render.IrisMetalPackLifecycle;
import com.metallum.client.metal.render.IrisMetalVertexSerializerBootstrap;
import com.metallum.client.metal.render.MetalIrisCompat;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pbr.texture.PBRTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds Iris dormant on the Metal backend.
 *
 * <p>{@code Iris.onRenderSystemInit} starts with
 * {@code GL.getCapabilities()}, so Metal cannot execute the method wholesale.
 * The CPU-only defaults and vertex serializers in its remaining body are
 * preserved here before the method is cancelled.</p>
 */
@Mixin(value = Iris.class, remap = false)
public abstract class IrisBootstrapCompatMixin {
    private static boolean metallum$pbrDefaultsInitialized;

    /**
     * The method body is GL from its first statement ({@code GL.getCapabilities},
     * {@code glMaxShaderCompilerThreads}, {@code PBRTextureManager.init}), so it
     * is cancelled wholesale. Its <b>last</b> statement is
     * {@code loadShaderpack()}, though — and that is the only call site that
     * runs at startup. With the semantic layer active we therefore have to
     * perform it ourselves; gating {@code loadShaderpack} alone accomplishes
     * nothing because nothing ever reaches it.
     *
     * <p>A pack that fails to load must not take the client's renderer init
     * down with it: Iris's own {@code currentPack} simply stays empty, which
     * {@link IrisPipelineFactoryMixin} reads as "no pack" and serves the
     * vanilla pipeline.</p>
     */
    @Inject(method = "onRenderSystemInit", at = @At("HEAD"), cancellable = true)
    private static void metallum$skipGlRendererInit(final CallbackInfo ci) {
        if (!MetalIrisCompat.holdIrisDormant()) {
            return;
        }
        try {
            // Iris initializes these defaults on every backend before pack
            // selection, and TextureManager.close() unconditionally closes
            // them. Preserve that lifecycle even when shaders and the Metal
            // semantic layer are both disabled.
            if (!metallum$pbrDefaultsInitialized) {
                PBRTextureManager.INSTANCE.init();
                metallum$pbrDefaultsInitialized = true;
            }
            boolean semanticEnabled = MetalIrisCompat.semanticLayerEnabled();
            if (semanticEnabled) {
                IrisMetalVertexSerializerBootstrap.ensureRegistered();
                if (IrisMetalPackLifecycle.shouldLoadConfiguredPack(
                        semanticEnabled, Iris.getIrisConfig().areShadersEnabled()
                )) {
                    Iris.loadShaderpack();
                }
            }
        } catch (Throwable t) {
            if (IrisMetalPackLifecycle.strictModeRequested()
                    && IrisMetalPackLifecycle.shouldLoadConfiguredPack(
                    MetalIrisCompat.semanticLayerEnabled(),
                    Iris.getIrisConfig().areShadersEnabled()
            )) {
                throw new IllegalStateException(
                        "Iris Metal strict pack admission failed during bootstrap", t
                );
            }
            Metallum.LOGGER.error(
                    "[metallum-iris] Metal-safe Iris bootstrap failed; continuing without a pack", t
            );
        }
        ci.cancel();
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
     * With the semantic layer and shaders active this must NOT be cancelled:
     * Iris parses the configured pack so
     * {@code IrisMetalPipelineOverrides} can translate its
     * {@code gbuffers_terrain} programs. Pack loading itself is CPU-side
     * (zip/properties/preprocessor); the only GL it reaches is
     * {@code StandardMacros}, which {@link GlStateManagerCompatMixin} and
     * {@link IrisRenderSystemCompatMixin} answer with pinned constants.
     *
     * <p>When shaders are disabled, entering this method only calls Iris's
     * private {@code setShadersDisabled()}, mutating global pack state even
     * though no pack or semantic pipeline exists. Keep that transition
     * dormant so requesting the Metal semantic layer cannot perturb vanilla
     * rendering. A later enable/reload passes this gate and loads normally.</p>
     */
    @Inject(method = "loadShaderpack", at = @At("HEAD"), cancellable = true)
    private static void metallum$keepPackUnloaded(final CallbackInfo ci) {
        if (!MetalIrisCompat.holdIrisDormant()) {
            return;
        }
        boolean semanticEnabled = MetalIrisCompat.semanticLayerEnabled();
        boolean shadersEnabled = semanticEnabled
                && Iris.getIrisConfig().areShadersEnabled();
        if (!IrisMetalPackLifecycle.shouldLoadConfiguredPack(semanticEnabled, shadersEnabled)
                && !IrisMetalPackLifecycle.consumeDisabledReloadTransition(
                semanticEnabled, shadersEnabled
        )) {
            ci.cancel();
        }
    }
}
