package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.TerrainCandidateRegistry;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Connects Sodium's stable region/section lifecycle to the candidate registry. */
@Mixin(RenderRegion.class)
public abstract class RenderRegionTerrainCandidateMixin {
    @Inject(method = "createStorage", at = @At("RETURN"), remap = false)
    private void metallum$registerStorage(
            final TerrainRenderPass renderPass,
            final CallbackInfoReturnable<SectionRenderDataStorage> cir
    ) {
        if (!TerrainCandidateRegistry.enabled()) {
            return;
        }
        SectionRenderDataStorage storage = cir.getReturnValue();
        if (storage instanceof SectionRenderDataStorageOwner owner) {
            RenderRegion region = (RenderRegion) (Object) this;
            owner.metallum$setOwner(
                    region.getX(), region.getY(), region.getZ(), renderPass.isTranslucent()
            );
        }
    }

    @Inject(method = "addSection", at = @At("RETURN"), remap = false)
    private void metallum$sectionAdded(final RenderSection section, final CallbackInfo ci) {
        TerrainCandidateRegistry.onSectionAdded(section);
    }

    @Inject(method = "removeSection", at = @At("HEAD"), remap = false)
    private void metallum$sectionRemoved(final RenderSection section, final CallbackInfo ci) {
        TerrainCandidateRegistry.onSectionRemoved(section);
    }
}
