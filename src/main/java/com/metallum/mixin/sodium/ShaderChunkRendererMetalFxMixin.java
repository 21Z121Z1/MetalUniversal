package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.IrisMetalPipelineOverrides;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderChunkRenderer.class)
public abstract class ShaderChunkRendererMetalFxMixin {
    @Inject(method = "begin", at = @At("RETURN"), remap = false)
    private void metallum$beginIrisTerrainPass(
            final TerrainRenderPass pass,
            final net.caffeinemc.mods.sodium.client.util.FogParameters parameters,
            final com.mojang.renderpearl.api.textures.GpuSampler terrainSampler,
            final OitStage oitStage,
            final CallbackInfo ci
    ) {
        IrisMetalPipelineOverrides.beginTerrainPass(pass);
    }

    @Inject(method = "end", at = @At("RETURN"), remap = false)
    private void metallum$endCutoutReactivePass(
            final TerrainRenderPass pass,
            final CallbackInfo ci
    ) {
        IrisMetalPipelineOverrides.endTerrainPass();
    }
}
