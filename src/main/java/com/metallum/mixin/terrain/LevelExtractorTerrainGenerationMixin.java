package com.metallum.mixin.terrain;

import com.metallum.client.terrain.VanillaTerrainGenerationRuntime;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Real vanilla content mutation points for the opt-in T1b publication guard. */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorTerrainGenerationMixin {
    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void metallum$invalidateGenerationOnDirty(
            final int sectionX,
            final int sectionY,
            final int sectionZ,
            final boolean playerChanged,
            final CallbackInfo ci
    ) {
        VanillaTerrainGenerationRuntime.markDirty(SectionPos.asLong(sectionX, sectionY, sectionZ));
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void metallum$invalidateGenerationOnWorldChange(
            final ClientLevel level,
            final CallbackInfo ci
    ) {
        VanillaTerrainGenerationRuntime.onLevelChanged(level);
    }

    @Inject(method = "allChanged", at = @At("HEAD"))
    private void metallum$invalidateGenerationOnFullGeometryChange(final CallbackInfo ci) {
        VanillaTerrainGenerationRuntime.onFullGeometryInvalidation();
    }
}
