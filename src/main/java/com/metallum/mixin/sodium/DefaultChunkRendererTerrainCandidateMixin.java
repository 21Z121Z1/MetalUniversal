package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.TerrainCandidateRegistry;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.SharedQuadIndexBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Refreshes the shared opaque index identity before terrain rendering. */
@Mixin(DefaultChunkRenderer.class)
public abstract class DefaultChunkRendererTerrainCandidateMixin {
    @Shadow @Final
    private SharedQuadIndexBuffer sharedIndexBuffer;

    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void metallum$sharedIndexAtRender(
            final ChunkRenderMatrices matrices,
            final ChunkRenderListIterable renderLists,
            final TerrainRenderPass renderPass,
            final CameraTransform camera,
            final FogParameters fogParameters,
            final boolean noShadow,
            final GpuSampler sampler,
            final GpuBufferSlice uniformBuffer,
            final GpuBuffer matrixBuffer,
            final CallbackInfo ci
    ) {
        if (!TerrainCandidateRegistry.enabled()) {
            return;
        }
        TerrainCandidateRegistry.onOpaqueSharedIndexBuffer(sharedIndexBuffer.getBufferObject());
    }
}
