package com.metallum.mixin.sodium;

import com.metallum.client.terrain.TerrainMeshGeneration;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes a fail-closed region check without depending on Sodium accessors. */
@Mixin(RenderRegion.class)
public abstract class RenderRegionTerrainGenerationMixin
        implements TerrainMeshGeneration.RegionAccess {
    @Shadow
    @Final
    private RenderSection[] sections;

    @Override
    public boolean metallum$isTerrainGenerationCurrent(final long generation) {
        for (RenderSection section : this.sections) {
            if (section != null
                    && section.isBuilt()
                    && !TerrainMeshGeneration.isSectionCurrent(section, generation)) {
                return false;
            }
        }
        return true;
    }
}
