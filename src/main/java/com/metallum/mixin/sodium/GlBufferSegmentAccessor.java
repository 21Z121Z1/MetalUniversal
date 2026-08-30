package com.metallum.mixin.sodium;

import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferSegment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads Sodium's authoritative arena-segment retirement bit for liveness. */
@Mixin(GlBufferSegment.class)
public interface GlBufferSegmentAccessor {
    @Accessor("free")
    boolean metallum$isFree();
}
