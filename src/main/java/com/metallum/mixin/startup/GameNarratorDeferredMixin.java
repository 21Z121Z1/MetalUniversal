package com.metallum.mixin.startup;

import com.metallum.client.startup.DeferredNarrator;
import com.mojang.text2speech.Narrator;
import net.minecraft.client.GameNarrator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Defers the platform narrator backend until its first real use. */
@Mixin(GameNarrator.class)
public abstract class GameNarratorDeferredMixin {
    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/text2speech/Narrator;getNarrator()Lcom/mojang/text2speech/Narrator;"
            )
    )
    private static Narrator metallum$deferNarratorCreation() {
        return new DeferredNarrator();
    }
}
