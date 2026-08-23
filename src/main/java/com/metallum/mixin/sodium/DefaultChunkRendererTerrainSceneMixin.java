package com.metallum.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.metal.render.TerrainSceneSnapshot;
import com.metallum.client.metal.render.TerrainSubmissionScope;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Opens the snapshot transaction only at Sodium's terrain batch producer. */
@Mixin(DefaultChunkRenderer.class)
public abstract class DefaultChunkRendererTerrainSceneMixin {
    @WrapOperation(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/gpu/device/batch/MultiDrawBatch;draw("
                            + "Lnet/caffeinemc/mods/sodium/client/gpu/device/context/DrawContext;)V"
            ),
            remap = false
    )
    private void metallum$terrainBatchScope(
            final MultiDrawBatch batch,
            final DrawContext drawContext,
            final Operation<Void> original
    ) {
        if (!TerrainSceneSnapshot.captureEnabled()) {
            original.call(batch, drawContext);
            return;
        }
        try (TerrainSubmissionScope ignored = TerrainSubmissionScope.begin()) {
            original.call(batch, drawContext);
        }
    }
}
