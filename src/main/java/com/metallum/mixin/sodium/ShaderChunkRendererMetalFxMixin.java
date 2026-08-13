package com.metallum.mixin.sodium;

import com.metallum.Metallum;
import com.metallum.client.metal.render.IrisMetalCutoutCoverageRuntime;
import com.metallum.client.metal.render.MetalCutoutReactivePipeline;
import com.metallum.client.metal.render.IrisMetalPipelineOverrides;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ShaderChunkRenderer.class)
public abstract class ShaderChunkRendererMetalFxMixin {
    @Unique
    private static final AtomicBoolean metallum$irisCoverageLogged = new AtomicBoolean();

    @Shadow @Final protected VertexFormat vertexFormat;

    @Inject(method = "begin", at = @At("HEAD"), remap = false)
    private void metallum$beginCutoutReactivePass(
            final TerrainRenderPass pass,
            final net.caffeinemc.mods.sodium.client.util.FogParameters parameters,
            final com.mojang.blaze3d.textures.GpuSampler terrainSampler,
            final CallbackInfo ci
    ) {
        MetalCutoutReactivePipeline.beginTerrainPass(pass);
    }

    @Inject(method = "begin", at = @At("RETURN"), remap = false)
    private void metallum$beginIrisTerrainPass(
            final TerrainRenderPass pass,
            final net.caffeinemc.mods.sodium.client.util.FogParameters parameters,
            final com.mojang.blaze3d.textures.GpuSampler terrainSampler,
            final CallbackInfo ci
    ) {
        IrisMetalPipelineOverrides.beginTerrainPass(pass);
        IrisMetalCutoutCoverageRuntime.beginTerrainPass(pass);
    }

    @Inject(method = "compileProgram", at = @At("HEAD"), cancellable = true, remap = false)
    private void metallum$compileCutoutReactivePipeline(
            final TerrainRenderPass pass,
            final CallbackInfoReturnable<RenderPipeline> cir
    ) {
        boolean irisSemanticActive = IrisMetalPipelineOverrides.isActiveForTerrainOwnership();
        if (MetalCutoutReactivePipeline.shouldSubstitute(irisSemanticActive)) {
            cir.setReturnValue(MetalCutoutReactivePipeline.forVertexFormat(this.vertexFormat));
        } else if (MetalCutoutReactivePipeline.isActiveCutoutPass()
                && irisSemanticActive
                && metallum$irisCoverageLogged.compareAndSet(false, true)) {
            Metallum.LOGGER.info(
                    "Iris owns the CUTOUT terrain pipeline; MetalFX uses the generation-owned compact R8 coverage attachment");
        }
    }

    @Inject(method = "end", at = @At("RETURN"), remap = false)
    private void metallum$endCutoutReactivePass(
            final TerrainRenderPass pass,
            final CallbackInfo ci
    ) {
        // Flush before clearing either terrain discriminator. The copy is a
        // no-op unless MetalFX currently owns a matching reactive input.
        IrisMetalCutoutCoverageRuntime.flushToMetalFx();
        IrisMetalCutoutCoverageRuntime.endTerrainPass();
        IrisMetalPipelineOverrides.endTerrainPass();
        MetalCutoutReactivePipeline.endTerrainPass();
    }
}
