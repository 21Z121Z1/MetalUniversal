package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.MetalGpuBuffer;
import com.mojang.blaze3d.buffers.GpuBuffer;
import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferArena;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps Sodium's terrain arena lifetime inside the Metal renderer.
 *
 * <p>Sodium's resize path puts the old arena buffer in a process-wide static
 * free list.  That list has no GPU completion or Metal allocation-generation
 * authority.  A Metal arena backing instead enters the existing deferred
 * {@link MetalGpuBuffer#close()} path; after the submit fence retires it, the
 * renderer-owned MetalDevice pool may hand its native backing to a later
 * {@code createBuffer} call.  Non-Metal buffers continue through Sodium's
 * original free-list implementation.</p>
 */
@Mixin(GlBufferArena.class)
public abstract class GlBufferArenaMetalLifetimeMixin {
    @Inject(method = "releaseBufferForReuse", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$retireMetalArenaBacking(
            final GpuBuffer buffer,
            final CallbackInfo ci
    ) {
        if (!(buffer instanceof MetalGpuBuffer metalBuffer)) {
            return;
        }

        // close() invalidates the old MetalAllocationIdentity for contract
        // lookups and defers native retirement until the GPU has completed the
        // copy from the old arena.  The fallback path remains untouched.
        metalBuffer.close();
        ci.cancel();
    }
}
