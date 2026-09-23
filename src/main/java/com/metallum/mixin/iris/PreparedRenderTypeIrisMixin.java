package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalPipelineOverrides;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Routes Mojang 26.3 prepared draws through the active Iris synthetic PSO. */
@Mixin(PreparedRenderType.class)
public abstract class PreparedRenderTypeIrisMixin {
    @Redirect(
            method = "draw(Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline("
                            + "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)"
                            + "Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"
            )
    )
    private CompiledRenderPipeline metallum$compileCorePipeline(final RenderPipeline source) {
        return RenderSystem.getCompiledPipeline(IrisMetalPipelineOverrides.pipelineForCore(source));
    }

}