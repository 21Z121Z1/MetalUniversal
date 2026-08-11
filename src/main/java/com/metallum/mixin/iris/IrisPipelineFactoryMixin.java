package com.metallum.mixin.iris;

import com.metallum.client.metal.render.MetalActive;
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

/** Selects the backend-owned world pipeline without constructing Iris GL objects. */
@Mixin(value = Iris.class, remap = false)
public abstract class IrisPipelineFactoryMixin {
    @Inject(method = "isPackInUseQuick", at = @At("HEAD"), cancellable = true)
    private static void metallum$recognizeMetalPipeline(
            final CallbackInfoReturnable<Boolean> cir
    ) {
        if (MetalActive.isMetalActive()
                && Iris.getPipelineManager().getPipelineNullable() instanceof MetalWorldRenderingPipeline) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "createPipeline", at = @At("HEAD"), cancellable = true)
    private static void metallum$createMetalPipeline(
            final NamespacedId dimensionId,
            final CallbackInfoReturnable<WorldRenderingPipeline> cir
    ) {
        if (!MetalActive.isMetalActive()) {
            return;
        }
        Optional<ShaderPack> pack = Iris.getCurrentPack();
        if (pack.isEmpty()) {
            return;
        }
        MetalWorldRenderingPipeline candidate = new MetalWorldRenderingPipeline(
                pack.orElseThrow().getProgramSet(dimensionId)
        );
        try {
            candidate.prepareForPublication();
            cir.setReturnValue(candidate);
        } catch (RuntimeException | Error failure) {
            // PipelineManager publishes the factory result only after this
            // method returns. A rejected candidate is therefore never active,
            // but it still owns CPU/GPU state and its receipt writer.
            candidate.discardUnpublished(failure);
            throw failure;
        }
    }
}
