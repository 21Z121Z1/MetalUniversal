package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.TerrainCandidateRegistry;
import net.caffeinemc.mods.sodium.client.gpu.arena.BufferSegment;
import net.caffeinemc.mods.sodium.client.gpu.arena.RegionAllocatorHandle;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Revalidates optional Sodium terrain candidates at its storage mutation
 * boundary. Minecraft 26.3 vanilla MDI remains the canonical submission path;
 * this mixin only maintains additional Sodium-derived metadata when Sodium is
 * present.
 */
@Mixin(SectionRenderDataStorage.class)
public abstract class SectionRenderDataStorageTerrainCandidateMixin {
    @Inject(method = "setVertexData", at = @At("RETURN"), remap = false)
    private void metallum$vertexUploaded(
            final int localIndex,
            final BufferSegment allocation,
            final int[] vertexCounts,
            final CallbackInfo ci
    ) {
        TerrainCandidateRegistry.onStorageMutation((SectionRenderDataStorage) (Object) this, localIndex);
    }

    @Inject(method = "setIndexData", at = @At("RETURN"), remap = false)
    private void metallum$indexUploaded(
            final int localIndex,
            final BufferSegment allocation,
            final CallbackInfo ci
    ) {
        TerrainCandidateRegistry.onStorageMutation((SectionRenderDataStorage) (Object) this, localIndex);
    }

    @Inject(method = "setSharedIndexUsage", at = @At("RETURN"), remap = false)
    private void metallum$sharedUsageChanged(
            final int localIndex,
            final int count,
            final CallbackInfoReturnable<Boolean> cir
    ) {
        TerrainCandidateRegistry.onStorageMutation((SectionRenderDataStorage) (Object) this, localIndex);
    }

    @Inject(method = "updateSharedIndexData", at = @At("RETURN"), remap = false)
    private void metallum$sharedIndexUploaded(
            final RegionAllocatorHandle arena,
            final CallbackInfoReturnable<Boolean> cir
    ) {
        TerrainCandidateRegistry.onStorageMutation((SectionRenderDataStorage) (Object) this, -1);
    }

    @Inject(method = "removeVertexData", at = @At("RETURN"), remap = false)
    private void metallum$vertexFreed(final int localIndex, final CallbackInfo ci) {
        TerrainCandidateRegistry.onStorageMutation((SectionRenderDataStorage) (Object) this, localIndex);
    }

    @Inject(method = "removeIndexData", at = @At("RETURN"), remap = false)
    private void metallum$indexFreed(final int localIndex, final CallbackInfo ci) {
        TerrainCandidateRegistry.onStorageMutation((SectionRenderDataStorage) (Object) this, localIndex);
    }

    @Inject(method = "removeData(I)V", at = @At("RETURN"), remap = false)
    private void metallum$dataFreed(final int localIndex, final CallbackInfo ci) {
        TerrainCandidateRegistry.onStorageMutation((SectionRenderDataStorage) (Object) this, localIndex);
    }

    @Inject(method = "onBufferResized", at = @At("RETURN"), remap = false)
    private void metallum$vertexBufferResized(final CallbackInfo ci) {
        TerrainCandidateRegistry.onStorageMutation((SectionRenderDataStorage) (Object) this, -1);
    }

    @Inject(method = "onIndexBufferResized", at = @At("RETURN"), remap = false)
    private void metallum$indexBufferResized(final CallbackInfo ci) {
        TerrainCandidateRegistry.onStorageMutation((SectionRenderDataStorage) (Object) this, -1);
    }

    @Inject(method = "delete", at = @At("HEAD"), remap = false)
    private void metallum$storageDeleted(final CallbackInfo ci) {
        TerrainCandidateRegistry.onStorageDeleted((SectionRenderDataStorage) (Object) this);
    }
}
