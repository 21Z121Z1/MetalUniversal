package com.metallum.mixin.sodium;

import com.metallum.client.terrain.TerrainMeshGeneration;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Carries the worker-side terrain ABI snapshot into Sodium's build result. */
@Mixin(ChunkBuildOutput.class)
public abstract class TerrainChunkBuildOutputGenerationMixin
        implements TerrainMeshGeneration.OutputAccess {
    /**
     * Also stamps Sodium's render-thread-created empty-section result, which
     * bypasses {@code ChunkBuilderMeshingTask}. A real worker overwrites this
     * with the snapshot captured at execute HEAD before publishing its result.
     */
    @Unique
    private TerrainMeshGeneration.Stamp metallum$terrainGeneration =
            TerrainMeshGeneration.current();

    @Override
    public TerrainMeshGeneration.Stamp metallum$terrainGeneration() {
        return this.metallum$terrainGeneration;
    }

    @Override
    public void metallum$setTerrainGeneration(final TerrainMeshGeneration.Stamp stamp) {
        this.metallum$terrainGeneration = stamp;
    }
}
