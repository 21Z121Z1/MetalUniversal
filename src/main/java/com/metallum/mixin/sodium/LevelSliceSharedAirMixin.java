package com.metallum.mixin.sodium;

import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSection;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Reuses one immutable AIR-filled block-state array for empty neighbour
 * sections instead of clearing 4096 entries for each empty slot on every
 * LevelSlice copy.
 *
 * <p>Non-empty slots are restored to the LevelSlice-owned arrays before
 * Sodium performs its normal unpack. The shared array is never handed to an
 * unpack path that may write block states.
 */
@Mixin(value = LevelSlice.class, remap = false)
public abstract class LevelSliceSharedAirMixin {
    @Unique
    private static final BlockState[] metallum$sharedAir = metallum$createSharedAir();

    @Shadow @Final
    private BlockState[][] blockArrays;

    @Unique
    private BlockState[][] metallum$ownedBlockArrays;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void metallum$rememberOwnedArrays(ClientLevel level, CallbackInfo ci) {
        this.metallum$ownedBlockArrays = this.blockArrays.clone();
    }

    @Inject(method = "copySectionData", at = @At("HEAD"))
    private void metallum$selectBlockArray(ChunkRenderContext context, int sectionIndex, CallbackInfo ci) {
        ClonedChunkSection section = context.getSections()[sectionIndex];
        if (section != null && section.getBlockData() == null) {
            this.blockArrays[sectionIndex] = metallum$sharedAir;
        } else {
            this.blockArrays[sectionIndex] = this.metallum$ownedBlockArrays[sectionIndex];
        }
    }

    @Redirect(
            method = "unpackBlockData",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Arrays;fill([Ljava/lang/Object;Ljava/lang/Object;)V"
            )
    )
    private void metallum$skipSharedAirClear(Object[] array, Object value) {
        if (array != metallum$sharedAir) {
            Arrays.fill(array, value);
        }
    }

    @Unique
    private static BlockState[] metallum$createSharedAir() {
        BlockState[] states = new BlockState[16 * 16 * 16];
        Arrays.fill(states, Blocks.AIR.defaultBlockState());
        return states;
    }
}
