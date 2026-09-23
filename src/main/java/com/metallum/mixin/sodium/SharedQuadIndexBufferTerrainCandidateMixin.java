package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.TerrainCandidateRegistry;
import net.caffeinemc.mods.sodium.client.render.chunk.SharedQuadIndexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Publishes the opaque pass's renderer-owned shared index replacement. */
@Mixin(SharedQuadIndexBuffer.class)
public abstract class SharedQuadIndexBufferTerrainCandidateMixin {
    @Inject(method = "grow", at = @At("RETURN"), remap = false)
    private void metallum$sharedIndexGrown(final int maxPrimitives, final CallbackInfo ci) {
        TerrainCandidateRegistry.onOpaqueSharedIndexBuffer(
                ((SharedQuadIndexBuffer) (Object) this).getBufferObject()
        );
    }

    @Inject(method = "delete", at = @At("HEAD"), remap = false)
    private void metallum$sharedIndexDeleted(final CallbackInfo ci) {
        TerrainCandidateRegistry.onOpaqueSharedIndexBuffer(null);
    }
}
