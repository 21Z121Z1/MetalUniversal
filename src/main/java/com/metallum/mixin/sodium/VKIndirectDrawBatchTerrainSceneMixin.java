package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.TerrainSceneSnapshot;
import com.metallum.client.metal.render.TerrainSubmissionScope;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.VKIndirectDrawBatch;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Copies the packed command records at Sodium's real indirect producer. */
@Mixin(VKIndirectDrawBatch.class)
public abstract class VKIndirectDrawBatchTerrainSceneMixin {
    @Shadow
    @Final
    private long pCommands;

    @WrapOperation(
            method = "draw(Lnet/caffeinemc/mods/sodium/client/gpu/device/context/DrawContext;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderPass;drawIndexedIndirect("
                            + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;I)V"
            ),
            remap = false
    )
    private void metallum$captureTerrainCommands(
            final RenderPass pass,
            final GpuBufferSlice commandSlice,
            final int drawCount,
            final Operation<Void> original
    ) {
        if (TerrainSceneSnapshot.captureEnabled()) {
            TerrainSubmissionScope.capture(pass, this.pCommands, drawCount, commandSlice);
        }
        original.call(pass, commandSlice, drawCount);
    }
}
