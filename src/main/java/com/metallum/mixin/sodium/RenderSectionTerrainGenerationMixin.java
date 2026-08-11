package com.metallum.mixin.sodium;

import com.metallum.client.terrain.TerrainMeshGeneration;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Stores the ABI identity of the mesh currently published for one section. */
@Mixin(RenderSection.class)
public abstract class RenderSectionTerrainGenerationMixin
        implements TerrainMeshGeneration.SectionAccess {
    @Unique
    private TerrainMeshGeneration.Stamp metallum$terrainGeneration =
            TerrainMeshGeneration.UNSTAMPED;

    @Override
    public TerrainMeshGeneration.Stamp metallum$terrainGeneration() {
        return this.metallum$terrainGeneration;
    }

    @Override
    public void metallum$setTerrainGeneration(final TerrainMeshGeneration.Stamp stamp) {
        this.metallum$terrainGeneration = stamp;
    }
}
