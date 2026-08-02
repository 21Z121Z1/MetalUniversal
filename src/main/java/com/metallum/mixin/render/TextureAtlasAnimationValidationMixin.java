package com.metallum.mixin.render;

import com.metallum.Metallum;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps animated atlas contents on their uploaded first frame during exact
 * backend comparisons. Texture animation starts before a level exists, so
 * freezing the later world simulation can otherwise preserve a different
 * water animation phase in two isolated launches.
 */
@Mixin(TextureAtlas.class)
abstract class TextureAtlasAnimationValidationMixin {
    private static final boolean FREEZE_ATLAS_ANIMATION =
            Boolean.getBoolean("metallum.backend.compare.freeze-atlas-animation");
    @Unique
    private static boolean metallum$announced;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void metallum$freezeAtlasAnimationForComparison(final CallbackInfo callbackInfo) {
        if (FREEZE_ATLAS_ANIMATION) {
            if (!metallum$announced) {
                metallum$announced = true;
                Metallum.LOGGER.info(
                        "[metallum-backend-compare] animated texture atlases fixed at uploaded first frame"
                );
            }
            callbackInfo.cancel();
        }
    }
}
