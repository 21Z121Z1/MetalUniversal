package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.MetalTerrainIcbScope;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Narrows ICB admission to Sodium chunk-render submissions.
 *
 * <p>If Sodium changes the render method shape, this required mixin
 * intentionally fails during development rather than
 * silently widening ICB admission to unrelated render passes.</p>
 *
 * <p>The method-level wrapper also unwinds the thread-local scope when native
 * validation or a renderer failure throws; a paired RETURN injection would
 * leak terrain admission into the next render submission.</p>
 */
@Mixin(DefaultChunkRenderer.class)
public abstract class DefaultChunkRendererTerrainIcbScopeMixin {
    @WrapMethod(method = "render", require = 1)
    private void metallum$renderInTerrainIcbScope(
            final ChunkRenderMatrices matrices,
            final ChunkRenderListIterable renderLists,
            final TerrainRenderPass renderPass,
            final CameraTransform cameraTransform,
            final FogParameters fogParameters,
            final boolean renderBlockEntities,
            final GpuSampler sampler,
            final GpuBufferSlice uniformBuffer,
            final GpuBuffer vertexBuffer,
            final Operation<Void> original
    ) {
        MetalTerrainIcbScope.enter();
        try {
            original.call(
                    matrices,
                    renderLists,
                    renderPass,
                    cameraTransform,
                    fogParameters,
                    renderBlockEntities,
                    sampler,
                    uniformBuffer,
                    vertexBuffer
            );
        } finally {
            MetalTerrainIcbScope.exit();
        }
    }
}
