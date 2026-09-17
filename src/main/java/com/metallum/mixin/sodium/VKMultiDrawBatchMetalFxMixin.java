package com.metallum.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.VKMultiDrawBatch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.IntBuffer;

/**
 * Splits Sodium's interleaved multi-draw command list at the RenderPearl
 * device limit. 26.3 exposes this limit independently from the indirect-draw
 * limit, so one Sodium batch is not guaranteed to fit in one API call.
 */
@Mixin(VKMultiDrawBatch.class)
public abstract class VKMultiDrawBatchMetalFxMixin {
    @WrapOperation(
            method = "draw(Lnet/caffeinemc/mods/sodium/client/gpu/device/context/DrawContext;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/api/commands/RenderPass;multiDrawIndexed("
                            + "Ljava/nio/IntBuffer;III)V"
            ),
            remap = false
    )
    private void metallum$splitAtDeviceLimit(
            final RenderPass pass,
            final IntBuffer commands,
            final int stride,
            final int firstInstance,
            final int drawCount,
            final Operation<Void> original
    ) {
        int maxDrawCount = RenderSystem.getDevice().getDeviceInfo().limits()
                .maxMultiDrawDirectInterleavedDrawCount();
        if (maxDrawCount <= 0) {
            throw new IllegalStateException(
                    "MetalUniversal requires a positive maxMultiDrawDirectInterleavedDrawCount"
            );
        }
        if (drawCount <= maxDrawCount) {
            original.call(pass, commands, stride, firstInstance, drawCount);
            return;
        }

        // RenderPearl's interleaved indexed record is three ints wide. Use a
        // duplicate so Sodium's native command buffer position is untouched.
        int recordWidth = 3;
        int commandPosition = commands.position();
        int remaining = drawCount;
        int firstDraw = 0;
        while (remaining > 0) {
            int chunkCount = Math.min(remaining, maxDrawCount);
            IntBuffer chunk = commands.duplicate();
            int offset = commandPosition + firstDraw * recordWidth;
            chunk.position(offset);
            chunk.limit(offset + chunkCount * recordWidth);
            original.call(pass, chunk.slice(), stride, firstInstance, chunkCount);
            firstDraw += chunkCount;
            remaining -= chunkCount;
        }
    }
}