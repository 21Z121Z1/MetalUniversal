package com.metallum.mixin.sodium;

import net.caffeinemc.mods.sodium.client.world.biome.LevelBiomeSlice;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Short-circuits LevelBiomeSlice uniform-neighbour calculation when the entire
 * captured 12x12x12 biome slice already resolves to one canonical Biome.
 */
@Mixin(value = LevelBiomeSlice.class, remap = false)
public abstract class LevelBiomeUniformFastPathMixin {
    @Shadow @Final private Holder<Biome>[] biomes;
    @Shadow @Final private boolean[] uniform;

    @Inject(method = "calculateUniform", at = @At("HEAD"), cancellable = true)
    private void metallum$fillUniformSingleBiomeSlice(CallbackInfo ci) {
        Holder<Biome> firstHolder = this.biomes[0];
        if (firstHolder == null) {
            return;
        }

        Biome first = firstHolder.value();
        for (int i = 1; i < this.biomes.length; i++) {
            Holder<Biome> holder = this.biomes[i];
            if (holder == null || holder.value() != first) {
                return;
            }
        }

        Arrays.fill(this.uniform, true);
        ci.cancel();
    }
}
