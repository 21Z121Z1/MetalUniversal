package com.metallum.mixin.terrain;

import com.metallum.client.terrain.VanillaTerrainSliceCache;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CompiledSectionMesh.class)
abstract class CompiledSectionMeshSliceCacheMixin implements VanillaTerrainSliceCache.Owner {
    @Unique
    private final VanillaTerrainSliceCache metallum$slices =
            new VanillaTerrainSliceCache(ChunkSectionLayer.values().length);

    @Override
    public VanillaTerrainSliceCache metallum$terrainSliceCache() {
        return metallum$slices;
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void metallum$releaseSlices(CallbackInfo ci) {
        metallum$slices.invalidate();
    }
}
