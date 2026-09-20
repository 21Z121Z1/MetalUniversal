package com.metallum.mixin.terrain;

import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops a compile before the expensive SectionCompiler call when its captured content generation
 * is already obsolete. Publication is still checked independently because invalidation may happen
 * while compilation or staging is in flight.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
abstract class CompileTaskTerrainGenerationMixin {
    @Inject(method = "doTask", at = @At("HEAD"), cancellable = true)
    private void metallum$enterGenerationGuard(
            final SectionBufferBuilderPack buffers,
            final CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult> cir
    ) {
        SectionRenderDispatcher.RenderSection.SectionTask task =
                (SectionRenderDispatcher.RenderSection.SectionTask)(Object)this;
        if (!VanillaTerrainGenerationRuntime.enterTask(task)) {
            cir.setReturnValue(SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult.CANCELLED);
        }
    }

    @Inject(method = "doTask", at = @At("RETURN"))
    private void metallum$leaveGenerationGuard(
            final SectionBufferBuilderPack buffers,
            final CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult> cir
    ) {
        VanillaTerrainGenerationRuntime.exitTask(
                (SectionRenderDispatcher.RenderSection.SectionTask)(Object)this
        );
    }
}
