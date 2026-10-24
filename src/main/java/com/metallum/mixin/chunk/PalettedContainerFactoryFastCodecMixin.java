package com.metallum.mixin.chunk;

import com.metallum.client.chunk.ChunkPaletteFastCodec;
import com.metallum.client.chunk.ChunkPipelineOptions;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PalettedContainerFactory.class)
public abstract class PalettedContainerFactoryFastCodecMixin {
    @Inject(method = "create", at = @At("RETURN"), cancellable = true)
    private static void metallum$wrapSectionCodecs(
            RegistryAccess registries,
            CallbackInfoReturnable<PalettedContainerFactory> cir
    ) {
        if (!ChunkPipelineOptions.paletteCodecEnabled()) return;

        PalettedContainerFactory original = cir.getReturnValue();
        Registry<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);

        cir.setReturnValue(new PalettedContainerFactory(
                original.blockStatesStrategy(),
                original.defaultBlockState(),
                ChunkPaletteFastCodec.wrap(
                        original.blockStatesContainerCodec(),
                        tag -> ChunkPipelineOptions.paletteParseMode() == ChunkPipelineOptions.Mode.OFF
                                ? null
                                : ChunkPaletteFastCodec.decodeBlockStates(tag, original.blockStatesStrategy()),
                        container -> ChunkPipelineOptions.paletteSerializeMode() == ChunkPipelineOptions.Mode.OFF
                                ? null
                                : ChunkPaletteFastCodec.encodeBlockStates(container, original.blockStatesStrategy()),
                        "block-states"
                ),
                original.biomeStrategy(),
                original.defaultBiome(),
                ChunkPaletteFastCodec.wrap(
                        original.biomeContainerCodec(),
                        tag -> ChunkPipelineOptions.paletteParseMode() == ChunkPipelineOptions.Mode.OFF
                                ? null
                                : ChunkPaletteFastCodec.decodeBiomes(tag, original.biomeStrategy(), biomes),
                        container -> ChunkPipelineOptions.paletteSerializeMode() == ChunkPipelineOptions.Mode.OFF
                                ? null
                                : ChunkPaletteFastCodec.encodeBiomes(container, original.biomeStrategy()),
                        "biomes"
                )
        ));
    }
}
