package com.metallum.mixin.iris;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes Mojang's protected CommandEncoder backend to the Metal Iris bridge. */
@Mixin(targets = "com.mojang.blaze3d.systems.CommandEncoder")
public interface CommandEncoderBackendAccessor {
    @Accessor("backend")
    CommandEncoderBackend metallum$getBackend();
}
