package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.TerrainCandidateRegistry;
import com.metallum.client.metal.render.TerrainSceneSnapshot;
import net.caffeinemc.mods.sodium.client.gpu.arena.BufferArena;
import net.caffeinemc.mods.sodium.client.gpu.arena.BufferSegment;
import net.caffeinemc.mods.sodium.client.gpu.arena.RegionAllocatorHandle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives each Sodium arena segment an allocation generation without making the
 * Metal renderer depend on Sodium's allocator as draw authority.
 *
 * <p>Sodium 0.9.2 / Minecraft 26.3 replaced {@code GlBufferSegment}'s explicit
 * {@code free} flag with {@link BufferSegment}'s nullable owner. Exact-fit
 * allocations therefore transition through {@code setOwner}, while retirement
 * transitions through {@code setFree}. Both transitions are part of the ABA
 * identity, as are offset/length changes during defragmentation.</p>
 */
@Mixin(BufferSegment.class)
public abstract class GlBufferSegmentGenerationMixin {
    @Shadow
    private RegionAllocatorHandle owner;

    @Shadow
    private int ownerIndex;

    @Unique
    private long metallum$generation;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void metallum$initGeneration(
            final BufferArena arena,
            final RegionAllocatorHandle owner,
            final int ownerIndex,
            final long offset,
            final long length,
            final CallbackInfo callbackInfo
    ) {
        metallum$generation = 1L;
    }

    @Inject(method = "setFree", at = @At("HEAD"), remap = false)
    private void metallum$freeGeneration(final CallbackInfo callbackInfo) {
        if (metallum$tracksIdentity() && owner != null) {
            metallum$generation++;
        }
    }

    @Inject(method = "setOwner", at = @At("HEAD"), remap = false)
    private void metallum$ownerGeneration(
            final RegionAllocatorHandle nextOwner,
            final int nextOwnerIndex,
            final CallbackInfo callbackInfo
    ) {
        if (metallum$tracksIdentity() && (owner != nextOwner || ownerIndex != nextOwnerIndex)) {
            metallum$generation++;
        }
    }

    @Inject(method = "setOffset", at = @At("HEAD"), remap = false)
    private void metallum$offsetGeneration(final long value, final CallbackInfo callbackInfo) {
        if (metallum$tracksIdentity() && ((BufferSegment) (Object) this).getOffset() != value) {
            metallum$generation++;
        }
    }

    @Inject(method = "setLength", at = @At("HEAD"), remap = false)
    private void metallum$lengthGeneration(final long value, final CallbackInfo callbackInfo) {
        if (metallum$tracksIdentity() && ((BufferSegment) (Object) this).getLength() != value) {
            metallum$generation++;
        }
    }

    @Unique
    private static boolean metallum$tracksIdentity() {
        return TerrainSceneSnapshot.DRAW_METADATA_ENABLED || TerrainCandidateRegistry.enabled();
    }

    /** Reflected by TerrainSegmentIdentity; mixin interfaces never leak into core code. */
    public boolean metallum$isFree() {
        return owner == null;
    }

    /** Reflected by TerrainSegmentIdentity; see {@link #metallum$isFree()}. */
    public long metallum$generation() {
        return metallum$generation;
    }
}
