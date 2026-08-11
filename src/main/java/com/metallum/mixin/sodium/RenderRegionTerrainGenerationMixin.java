package com.metallum.mixin.sodium;

import com.metallum.client.terrain.TerrainMeshGeneration;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionFlags;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Caches the fail-closed generation state of one Sodium render region. */
@Mixin(RenderRegion.class)
public abstract class RenderRegionTerrainGenerationMixin
        implements TerrainMeshGeneration.RegionAccess {
    @Shadow
    @Final
    private RenderSection[] sections;

    @Shadow
    @Final
    private byte[] sectionFlags;

    @Unique
    private long metallum$terrainMutationRevision;

    @Unique
    private long metallum$checkedTerrainRevision = Long.MIN_VALUE;

    @Unique
    private long metallum$checkedTerrainEpoch = Long.MIN_VALUE;

    @Unique
    private boolean metallum$checkedTerrainCurrent;

    @Inject(method = "addSection", at = @At("RETURN"), remap = false, require = 1)
    private void metallum$sectionAdded(
            final RenderSection section,
            final CallbackInfo ci
    ) {
        this.metallum$terrainMutationRevision++;
    }

    @Inject(method = "removeSection", at = @At("RETURN"), remap = false, require = 1)
    private void metallum$sectionRemoved(
            final RenderSection section,
            final CallbackInfo ci
    ) {
        this.metallum$terrainMutationRevision++;
    }

    @Inject(method = "setSectionRenderState", at = @At("RETURN"), remap = false, require = 1)
    private void metallum$sectionStateSet(
            final int index,
            final BuiltSectionInfo info,
            final CallbackInfo ci
    ) {
        this.metallum$terrainMutationRevision++;
    }

    @Inject(method = "clearSectionRenderState", at = @At("RETURN"), remap = false, require = 1)
    private void metallum$sectionStateCleared(
            final int index,
            final CallbackInfo ci
    ) {
        this.metallum$terrainMutationRevision++;
    }

    @Inject(method = "delete", at = @At("RETURN"), remap = false, require = 1)
    private void metallum$regionDeleted(final CallbackInfo ci) {
        // RenderRegion.delete() clears the section array and all per-section
        // render state. Invalidate the cached answer even if a stale render
        // list reaches the renderer before Sodium removes the region.
        this.metallum$terrainMutationRevision++;
    }

    @Override
    public boolean metallum$isTerrainGenerationCurrent(
            final TerrainMeshGeneration.Stamp expected
    ) {
        long revision = this.metallum$terrainMutationRevision;
        if (this.metallum$checkedTerrainEpoch == expected.epoch()
                && this.metallum$checkedTerrainRevision == revision) {
            return this.metallum$checkedTerrainCurrent;
        }

        boolean current = true;
        for (int index = 0; index < this.sections.length; index++) {
            if ((this.sectionFlags[index] & RenderSectionFlags.MASK_HAS_BLOCK_GEOMETRY) == 0) {
                continue;
            }
            RenderSection section = this.sections[index];
            if (!(section instanceof TerrainMeshGeneration.SectionAccess access)
                    || access.metallum$terrainGeneration() == null
                    || access.metallum$terrainGeneration().epoch() != expected.epoch()) {
                current = false;
                break;
            }
        }

        this.metallum$checkedTerrainCurrent = current;
        this.metallum$checkedTerrainEpoch = expected.epoch();
        this.metallum$checkedTerrainRevision = revision;
        return current;
    }
}
