package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.TerrainCandidateRegistry;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Publishes only Sodium's producer-owned BuiltSectionInfo transitions. */
@Mixin(RenderSection.class)
public abstract class RenderSectionTerrainCandidateMixin {
    @Inject(method = "setInfo", at = @At("RETURN"), remap = false)
    private void metallum$sectionInfo(
            final BuiltSectionInfo info,
            final CallbackInfoReturnable<Integer> cir
    ) {
        TerrainCandidateRegistry.onSectionInfo((RenderSection) (Object) this, info);
    }

    @Inject(method = "delete", at = @At("HEAD"), remap = false)
    private void metallum$sectionDeleted(final CallbackInfo ci) {
        TerrainCandidateRegistry.onSectionRemoved((RenderSection) (Object) this);
    }
}
