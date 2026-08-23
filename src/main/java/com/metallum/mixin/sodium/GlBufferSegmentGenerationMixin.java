package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.TerrainSceneSnapshot;
import com.metallum.client.metal.render.TerrainCandidateRegistry;
import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferArena;
import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferSegment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives each Sodium arena segment its own allocation generation.  It is
 * deliberately not a process-wide counter: a reused segment changes its own
 * stamp, while the default-off path does not perform any increments.
 */
@Mixin(GlBufferSegment.class)
public abstract class GlBufferSegmentGenerationMixin implements GlBufferSegmentGeneration {
    @Shadow
    private boolean free;

    @Unique
    private long metallum$generation;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void metallum$initGeneration(
            final GlBufferArena arena,
            final long offset,
            final long length,
            final CallbackInfo callbackInfo
    ) {
        metallum$generation = 1L;
    }

    @Inject(method = "setFree", at = @At("HEAD"), remap = false)
    private void metallum$freeGeneration(final boolean value, final CallbackInfo callbackInfo) {
        if ((TerrainSceneSnapshot.DRAW_METADATA_ENABLED || TerrainCandidateRegistry.enabled()) && free != value) {
            metallum$generation++;
        }
    }

    @Inject(method = "setOffset", at = @At("HEAD"), remap = false)
    private void metallum$offsetGeneration(final long value, final CallbackInfo callbackInfo) {
        if ((TerrainSceneSnapshot.DRAW_METADATA_ENABLED || TerrainCandidateRegistry.enabled())
                && ((GlBufferSegment) (Object) this).getOffset() != value) {
            metallum$generation++;
        }
    }

    @Inject(method = "setLength", at = @At("HEAD"), remap = false)
    private void metallum$lengthGeneration(final long value, final CallbackInfo callbackInfo) {
        if ((TerrainSceneSnapshot.DRAW_METADATA_ENABLED || TerrainCandidateRegistry.enabled())
                && ((GlBufferSegment) (Object) this).getLength() != value) {
            metallum$generation++;
        }
    }

    @Override
    public long metallum$generation() {
        return metallum$generation;
    }
}
