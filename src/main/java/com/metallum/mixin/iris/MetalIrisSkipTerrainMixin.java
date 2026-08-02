package com.metallum.mixin.iris;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.metallum.client.metal.render.MetalWorldRenderingPipeline;
import net.irisshaders.iris.Iris;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import com.mojang.blaze3d.textures.GpuSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Extends Iris's skipAllRendering terrain gate to its native Metal pipeline. */
@Mixin(LevelRenderer.class)
abstract class MetalIrisSkipTerrainMixin {
    @WrapWithCondition(
            method = {"lambda$addMainPass$0", "lambda$addMainPass$1"},
            require = 1,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V"
            )
    )
    private boolean metallum$renderTerrainForMetalIris(
            final ChunkSectionsToRender sections,
            final ChunkSectionLayerGroup layer,
            final GpuSampler sampler
    ) {
        return !(Iris.getPipelineManager().getPipelineNullable()
                instanceof MetalWorldRenderingPipeline pipeline)
                || !pipeline.shouldSkipAllRendering();
    }
}
