package com.metallum.mixin.terrain;

import com.metallum.client.terrain.VanillaTerrainGenerationRuntime;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Stops a compile before the expensive SectionCompiler call when its captured content generation
 * is already obsolete. Publication is still checked independently because invalidation may happen
 * while compilation or staging is in flight.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
abstract class CompileTaskTerrainGenerationMixin {
    @WrapMethod(method = "doTask")
    private SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult metallum$guardTaskLifetime(
            final SectionBufferBuilderPack buffers,
            final Operation<SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult> original
    ) {
        SectionRenderDispatcher.RenderSection.SectionTask task =
                (SectionRenderDispatcher.RenderSection.SectionTask)(Object)this;
        if (!VanillaTerrainGenerationRuntime.enterTask(task)) {
            return SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult.CANCELLED;
        }
        try {
            return original.call(buffers);
        } finally {
            // Vanilla can requeue an NPE or delay a crash; neither retains this worker's token.
            VanillaTerrainGenerationRuntime.exitTask(task);
        }
    }
}
