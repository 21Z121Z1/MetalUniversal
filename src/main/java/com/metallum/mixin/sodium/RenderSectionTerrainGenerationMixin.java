package com.metallum.mixin.sodium;

import com.metallum.client.terrain.TerrainMeshGeneration;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateTypes;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rejects stale mesh results and records the generation of accepted sections. */
@Mixin(RenderSection.class)
public abstract class RenderSectionTerrainGenerationMixin
        implements TerrainMeshGeneration.SectionAccess {
    @Shadow
    public abstract void setPendingUpdate(int updateType, long since);

    @Unique
    private long metallum$terrainGeneration = TerrainMeshGeneration.UNSTAMPED;

    @Unique
    private long metallum$acceptedTerrainGeneration = TerrainMeshGeneration.UNSTAMPED;

    @Inject(method = "addBuildOutput", at = @At("HEAD"), cancellable = true, remap = false)
    private void metallum$rejectStaleMesh(
            final BuilderTaskOutput output,
            final CallbackInfoReturnable<Boolean> cir
    ) {
        if (!(output instanceof ChunkBuildOutput build)) {
            return;
        }
        if (TerrainMeshGeneration.contractActive()
                && (!(build instanceof TerrainMeshGeneration.OutputAccess access)
                || !TerrainMeshGeneration.isCurrent(access.metallum$terrainGeneration()))) {
            // Sodium has already detached the result from its worker job before
            // addBuildOutput is called. Release the native mesh and leave the
            // section explicitly rebuildable instead of presenting it through
            // the new vertex ABI.
            build.destroy();
            this.setPendingUpdate(ChunkUpdateTypes.REBUILD, System.nanoTime());
            cir.setReturnValue(false);
            return;
        }
        this.metallum$acceptedTerrainGeneration =
                build instanceof TerrainMeshGeneration.OutputAccess access
                        ? access.metallum$terrainGeneration()
                        : TerrainMeshGeneration.UNSTAMPED;
    }

    @Inject(method = "setInfo", at = @At("RETURN"), remap = false)
    private void metallum$recordAcceptedMesh(
            final BuiltSectionInfo info,
            final CallbackInfo ci
    ) {
        this.metallum$terrainGeneration = this.metallum$acceptedTerrainGeneration;
    }

    @Override
    public long metallum$terrainGeneration() {
        return this.metallum$terrainGeneration;
    }

    @Override
    public void metallum$setTerrainGeneration(final long generation) {
        this.metallum$terrainGeneration = generation;
    }
}
