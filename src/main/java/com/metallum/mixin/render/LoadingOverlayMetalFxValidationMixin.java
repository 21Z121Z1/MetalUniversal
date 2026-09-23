package com.metallum.mixin.render;

import com.metallum.Metallum;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Validation-only visibility into the 26.3 initial resource reload. */
@Mixin(LoadingOverlay.class)
abstract class LoadingOverlayMetalFxValidationMixin {
    @Shadow @Final private ReloadInstance reload;

    private boolean metallum$lastDone;
    private int metallum$lastProgressPercent = -1;

    @Inject(method = "tick", at = @At("HEAD"))
    private void metallum$observeReload(CallbackInfo ci) {
        if (!Boolean.getBoolean("metallum.validation.enabled")) {
            return;
        }
        boolean done = reload.isDone();
        int progressPercent = Math.round(reload.getActualProgress() * 100.0F);
        if (done != metallum$lastDone || progressPercent != metallum$lastProgressPercent) {
            metallum$lastDone = done;
            metallum$lastProgressPercent = progressPercent;
            Metallum.LOGGER.info(
                    "Validation resource reload: progress={}%, done={}",
                    progressPercent,
                    done
            );
        }
    }
}