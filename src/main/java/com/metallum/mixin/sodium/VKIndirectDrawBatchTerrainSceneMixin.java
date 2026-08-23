package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.TerrainSceneSnapshot;
import com.metallum.client.metal.render.TerrainSubmissionScope;
import com.metallum.client.metal.render.TerrainIcbOwner;
import com.metallum.client.metal.render.TerrainIcbProducer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.VKIndirectDrawBatch;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Copies the packed command records at Sodium's real indirect producer. */
@Mixin(VKIndirectDrawBatch.class)
public abstract class VKIndirectDrawBatchTerrainSceneMixin implements TerrainIcbProducer {
    @Shadow
    @Final
    private long pCommands;

    @Unique
    private TerrainIcbOwner metallum$terrainIcbOwner;

    @Override
    public TerrainIcbOwner metallum$terrainIcbOwner() {
        if (this.metallum$terrainIcbOwner == null && TerrainSceneSnapshot.ICB_ENABLED) {
            this.metallum$terrainIcbOwner = new TerrainIcbOwner();
        }
        return this.metallum$terrainIcbOwner;
    }

    @Override
    public void metallum$closeTerrainIcbOwner() {
        if (this.metallum$terrainIcbOwner != null) {
            this.metallum$terrainIcbOwner.close();
            this.metallum$terrainIcbOwner = null;
        }
    }

    @Inject(method = "delete", at = @At("HEAD"))
    private void metallum$closeTerrainIcbOwnerOnDelete(final CallbackInfo ci) {
        this.metallum$closeTerrainIcbOwner();
    }

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
            TerrainSubmissionScope.capture(
                    pass, this, this.pCommands, drawCount, commandSlice
            );
        }
        original.call(pass, commandSlice, drawCount);
    }
}
