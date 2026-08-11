package com.metallum.mixin.sodium;

import com.metallum.client.terrain.TerrainMeshGeneration;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Carries the worker-side terrain generation into Sodium's build result. */
@Mixin(ChunkBuildOutput.class)
public abstract class TerrainChunkBuildOutputGenerationMixin
        implements TerrainMeshGeneration.OutputAccess {
    @Unique
    private long metallum$terrainGeneration = TerrainMeshGeneration.UNSTAMPED;

    @Override
    public long metallum$terrainGeneration() {
        return this.metallum$terrainGeneration;
    }

    @Override
    public void metallum$setTerrainGeneration(final long generation) {
        this.metallum$terrainGeneration = generation;
    }
}
