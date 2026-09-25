package com.metallum.mixin.terrain;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.metallum.client.terrain.VanillaTerrainGenerationRuntime;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;

/** Reject obsolete work before compilation and release worker-local ownership on every exit. */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
abstract class CompileTaskTerrainGenerationMixin {
    @WrapMethod(method = "doTask")
    private SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult metallum$guardCompile(
            final SectionBufferBuilderPack buffers,
            final Operation<SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult> original
    ) {
        return VanillaTerrainGenerationRuntime.runTask(
                (SectionRenderDispatcher.RenderSection.SectionTask)(Object)this,
                SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult.CANCELLED,
                () -> original.call(buffers)
        );
    }
}
