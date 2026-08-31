package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisOpenGlPassTrace;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Provides the semantic gbuffers/terrain context for Sodium's OpenGL RenderPass. */
@Mixin(value = ShaderChunkRenderer.class, remap = false)
public abstract class IrisOpenGlTerrainPassTraceMixin {
    @Shadow protected RenderPipeline activeProgram;

    @Inject(method = "begin", at = @At("RETURN"))
    private void metallum$beginTerrain(
            final TerrainRenderPass pass,
            final FogParameters fog,
            final GpuSampler sampler,
            final CallbackInfo callbackInfo
    ) {
        IrisOpenGlPassTrace.beginTerrain(pass, this.activeProgram);
    }

    @Inject(method = "end", at = @At("HEAD"))
    private void metallum$endTerrain(
            final TerrainRenderPass pass,
            final CallbackInfo callbackInfo
    ) {
        IrisOpenGlPassTrace.endTerrain();
    }
}
