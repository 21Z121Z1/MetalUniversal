package com.metallum.mixin.iris;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.metallum.client.metal.render.MetalWorldRenderingPipeline;
import net.irisshaders.iris.Iris;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Extends Iris's skipAllRendering terrain gate to its native Metal pipeline. */
@Mixin(LevelRenderer.class)
abstract class MetalIrisSkipTerrainMixin {
    @WrapWithCondition(
            method = {"executeSolid", "executeClassicTransparency"},
            require = 1,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V"
            )
    )
    private boolean metallum$renderTerrainForMetalIris(
            final ChunkSectionsToRender sections,
            final ChunkSectionLayerGroup layer,
            final RenderPass renderPass,
            final GpuSampler sampler,
            final GpuTextureView atlas,
            final boolean renderWireframeTerrain
    ) {
        return !(Iris.getPipelineManager().getPipelineNullable()
                instanceof MetalWorldRenderingPipeline pipeline)
                || !pipeline.shouldSkipAllRendering();
    }
}
