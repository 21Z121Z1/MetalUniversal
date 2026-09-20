package com.metallum.mixin.terrain;

import com.metallum.client.terrain.VanillaTerrainWorkTelemetry;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
abstract class LevelExtractorTerrainWorkMixin {
    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void metallum$recordSectionDirty(
            final int sectionX,
            final int sectionY,
            final int sectionZ,
            final boolean playerChanged,
            final CallbackInfo ci
    ) {
        // The central dirty mutation receives geometry, lighting and packet-driven invalidations.
        // Until a narrower source is proven, advance both revisions rather than under-version work.
        VanillaTerrainWorkTelemetry.markDirty(SectionPos.asLong(sectionX, sectionY, sectionZ));
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void metallum$recordWorldTransition(final ClientLevel level, final CallbackInfo ci) {
        VanillaTerrainWorkTelemetry.onLevelChanged(level);
    }

    @Inject(method = "allChanged", at = @At("HEAD"))
    private void metallum$recordFullGeometryInvalidation(final CallbackInfo ci) {
        // allChanged is the vanilla full compiled-geometry invalidation boundary. Advancing this
        // generation more often than resource reload is conservative and prevents cross-invalidation
        // work identity reuse.
        VanillaTerrainWorkTelemetry.onFullGeometryInvalidation();
    }
}
