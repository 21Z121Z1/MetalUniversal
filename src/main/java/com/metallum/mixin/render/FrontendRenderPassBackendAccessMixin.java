package com.metallum.mixin.render;

import com.metallum.client.metal.render.RenderPearlBackendAccess;
import com.metallum.client.metal.render.MetalFxReactivePass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes Minecraft 26.3's RenderPearl backend pass through a Metallum-owned interface. */
@Mixin(FrontendRenderPass.class)
public abstract class FrontendRenderPassBackendAccessMixin implements RenderPearlBackendAccess {
    @Shadow
    @Final
    private RenderPassBackend backend;

    @ModifyVariable(method = "setPipeline", at = @At("HEAD"), argsOnly = true)
    private CompiledRenderPipeline metallum$matchCutoutMrt(final CompiledRenderPipeline pipeline) {
        return MetalFxReactivePass.adaptPipeline(this.backend, pipeline);
    }

    @Override
    public RenderPassBackend metallum$getBackend() {
        return this.backend;
    }
}
