package com.metallum.mixin.iris;

import com.metallum.Metallum;
import com.metallum.client.metal.render.IrisMetalPackRejectedException;
import com.metallum.client.metal.render.MetalIrisCompat;
import com.metallum.client.metal.render.MetalWorldRenderingPipeline;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Redirects Iris's pipeline factory to the Metal semantic pipeline.
 *
 * <p>{@code Iris.createPipeline} would otherwise construct an
 * {@code IrisRenderingPipeline}, whose constructor builds GL programs,
 * framebuffers and samplers — none of which exist on the Metal backend. With
 * the semantic layer active we answer with
 * {@link MetalWorldRenderingPipeline} instead, so a real pack drives sodium
 * terrain through the Metal backend.</p>
 *
 * <p>Failure to build the semantic pipeline rejects pack activation. The
 * factory must not return {@code VanillaRenderingPipeline} for an active pack:
 * that would report a successful selection while silently changing semantics.
 * Iris can keep the previous valid generation or surface the rejection through
 * its normal reload error path.</p>
 */
@Mixin(value = Iris.class, remap = false)
public abstract class IrisPipelineFactoryMixin {
    /**
     * Iris uses this concrete-pipeline identity as the common gate for shadow,
     * hand, extended immediate vertices, and several captured-render-state
     * hooks. The Metal semantic pipeline owns the same pack lifecycle and must
     * therefore be visible through that generic gate.
     */
    @Inject(method = "isPackInUseQuick", at = @At("HEAD"), cancellable = true)
    private static void metallum$recognizeSemanticPipeline(
            final CallbackInfoReturnable<Boolean> cir
    ) {
        if (Iris.getPipelineManager().getPipelineNullable() instanceof MetalWorldRenderingPipeline) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "createPipeline", at = @At("HEAD"), cancellable = true)
    private static void metallum$createSemanticPipeline(
            final NamespacedId dimensionId, final CallbackInfoReturnable<WorldRenderingPipeline> cir
    ) {
        if (!MetalIrisCompat.semanticLayerEnabled()) {
            return;
        }
        Optional<ShaderPack> pack = Iris.getCurrentPack();
        if (pack.isEmpty()) {
            // No pack selected: Iris's own path already returns the vanilla
            // pipeline here, which is Metal-safe (its one GL call is cancelled).
            return;
        }
        try {
            cir.setReturnValue(new MetalWorldRenderingPipeline(pack.get().getProgramSet(dimensionId)));
        } catch (IrisMetalPackRejectedException rejection) {
            Metallum.LOGGER.error(
                    "[metallum-iris] rejected the active pack for dimension {}: {}"
                            + "; no shaders-off pipeline was substituted",
                    dimensionId,
                    rejection.getMessage()
            );
            throw rejection;
        }
    }
}
