package com.metallum.mixin.sodium;

import com.metallum.client.terrain.TerrainSchedulingController;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.LimitedResourceBudget;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adjusts only Sodium's existing duration arguments and priority predicate. */
@Mixin(RenderSectionManager.class)
public abstract class RenderSectionManagerTerrainSchedulingMixin {
    @Shadow @Final private ChunkBuilder builder;
    @Shadow @Final private int renderDistance;
    @Shadow private int thisFrameBlockingTasks;
    @Shadow private int nextFrameBlockingTasks;
    @Shadow private int deferredTasks;

    @Inject(method = "updateChunks", at = @At("HEAD"), remap = false)
    private void metallum$beginChunkScheduling(
            final Viewport viewport,
            final boolean tick,
            final CallbackInfo ci
    ) {
        TerrainSchedulingController controller = TerrainSchedulingController.runtime();
        if (!controller.observesFrames()) {
            return;
        }
        controller.updateBacklog(
                builder.getScheduledJobCount(),
                builder.getBusyThreadCount(),
                builder.getTotalThreadCount()
        );
        controller.beginBuildStage(System.nanoTime());
    }

    @Inject(method = "updateChunks", at = @At("RETURN"), remap = false)
    private void metallum$endChunkScheduling(
            final Viewport viewport,
            final boolean tick,
            final CallbackInfo ci
    ) {
        TerrainSchedulingController controller = TerrainSchedulingController.runtime();
        if (controller.observesFrames()) {
            controller.endBuildStage(
                    System.nanoTime(),
                    thisFrameBlockingTasks + nextFrameBlockingTasks + deferredTasks
            );
        }
    }

    @Inject(method = "processChunkBuilds", at = @At("HEAD"), remap = false)
    private void metallum$beginChunkUpload(
            final Viewport viewport,
            final UniformBufferManager uniformBufferManager,
            final CallbackInfo ci
    ) {
        TerrainSchedulingController controller = TerrainSchedulingController.runtime();
        if (controller.observesFrames()) {
            controller.beginUploadStage(System.nanoTime());
        }
    }

    @Inject(method = "processChunkBuilds", at = @At("RETURN"), remap = false)
    private void metallum$endChunkUpload(
            final Viewport viewport,
            final UniformBufferManager uniformBufferManager,
            final CallbackInfo ci
    ) {
        TerrainSchedulingController controller = TerrainSchedulingController.runtime();
        if (controller.observesFrames()) {
            controller.endUploadStage(System.nanoTime());
        }
    }

    @ModifyArg(
            method = "updateChunks",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkJobCollector;<init>(JLjava/util/function/Consumer;)V"
            ),
            index = 0,
            remap = false
    )
    private long metallum$buildSubmissionBudget(final long sodiumBudget) {
        return TerrainSchedulingController.runtime().overrideBuildBudget(sodiumBudget);
    }

    @ModifyArg(
            method = "updateChunks",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/estimation/LimitedResourceBudget;<init>(JJ)V"
            ),
            index = 0,
            remap = false
    )
    private long metallum$uploadWorkBudget(final long sodiumBudget) {
        return TerrainSchedulingController.runtime().overrideUploadBudget(sodiumBudget);
    }

    @Inject(method = "shouldPrioritizeTask", at = @At("RETURN"), cancellable = true, remap = false)
    private void metallum$prioritizeForwardSections(
            final RenderSection section,
            final float originalDistanceSquared,
            final CallbackInfoReturnable<Boolean> cir
    ) {
        if (cir.getReturnValueZ()) {
            return;
        }
        TerrainSchedulingController controller = TerrainSchedulingController.runtime();
        if (controller.shouldBoostForward(
                section.getCenterX(),
                section.getCenterY(),
                section.getCenterZ(),
                originalDistanceSquared,
                renderDistance
        )) {
            cir.setReturnValue(true);
        }
    }
}
