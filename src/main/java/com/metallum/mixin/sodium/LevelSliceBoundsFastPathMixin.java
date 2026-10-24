package com.metallum.mixin.sodium;

import com.metallum.client.sodium.SodiumSliceBoundsPolicy;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reuses cached inclusive slice bounds for LevelSlice.getBlockState rather than
 * reloading six BoundingBox coordinates on the mesher's hottest lookup.
 */
@Mixin(value = LevelSlice.class, remap = false)
public abstract class LevelSliceBoundsFastPathMixin {
    @Shadow private BoundingBox volume;

    @Unique private int metallum$minX;
    @Unique private int metallum$maxX;
    @Unique private int metallum$minY;
    @Unique private int metallum$maxY;
    @Unique private int metallum$minZ;
    @Unique private int metallum$maxZ;

    @Inject(method = "copyData", at = @At("TAIL"))
    private void metallum$captureVolumeBounds(ChunkRenderContext context, CallbackInfo ci) {
        BoundingBox current = this.volume;
        this.metallum$minX = current.minX();
        this.metallum$maxX = current.maxX();
        this.metallum$minY = current.minY();
        this.metallum$maxY = current.maxY();
        this.metallum$minZ = current.minZ();
        this.metallum$maxZ = current.maxZ();
    }

    @Redirect(
            method = "getBlockState(III)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;isInside(III)Z",
                    remap = true
            )
    )
    private boolean metallum$fastBlockStateBounds(BoundingBox ignored, int x, int y, int z) {
        return SodiumSliceBoundsPolicy.containsInclusive(
                this.metallum$minX, this.metallum$maxX,
                this.metallum$minY, this.metallum$maxY,
                this.metallum$minZ, this.metallum$maxZ,
                x, y, z
        );
    }
}
