package com.metallum.mixin.sodium;

import com.metallum.client.terrain.TerrainSchedulingController;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/** Counts results at Sodium's actual region upload owner. */
@Mixin(RenderRegionManager.class)
public abstract class RenderRegionManagerTerrainSchedulingMixin {
    @Inject(method = "uploadResults", at = @At("HEAD"), remap = false)
    private void metallum$recordUploadResults(
            final Collection<BuilderTaskOutput> outputs,
            final UniformBufferManager uniformBufferManager,
            final CallbackInfo ci
    ) {
        TerrainSchedulingController controller = TerrainSchedulingController.runtime();
        if (controller.observesFrames()) {
            controller.recordUploadResults(outputs.size());
        }
    }
}
