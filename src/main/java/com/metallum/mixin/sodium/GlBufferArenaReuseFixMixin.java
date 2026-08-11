package com.metallum.mixin.sodium;

import com.metallum.Metallum;
import com.mojang.blaze3d.buffers.GpuBuffer;
import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferArena;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * M4-only fix for Sodium's process-wide arena backing pool.
 *
 * <p>Sodium migrates live ranges with {@code copyToBuffer()}, then immediately
 * places the old arena backing in a static pool. That is safe only when the
 * backend retains the resource and its contents for every in-flight consumer.
 * Metal 4 uses raw GPU addresses and reusable command-buffer slots, so the old
 * backing must not become writable by a later arena before the M4 queue has
 * completed the old draws.</p>
 *
 * <p>{@link GpuBuffer#close()} is the backend lifetime boundary here. On the
 * Metallum Metal backend it queues the native MTLBuffer for deferred retirement
 * through the same completion-ordered destruction queue used by the renderer.
 * This keeps the fix narrow: Metal 3 and non-Metal backends keep Sodium's
 * normal reuse path, while M4 allocates a fresh backing and retires the old one
 * only after submitted work can no longer reference it.</p>
 */
@Mixin(GlBufferArena.class)
public abstract class GlBufferArenaReuseFixMixin {
    @Unique
    private static final AtomicBoolean METALLUM$LOGGED = new AtomicBoolean();

    @Redirect(
            method = "transferSegments",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/gpu/arena/GlBufferArena;"
                            + "releaseBufferForReuse(Lcom/mojang/blaze3d/buffers/GpuBuffer;)V"
            ),
            remap = false
    )
    private static void metallum$retireArenaBacking(final GpuBuffer buffer) {
        if (METALLUM$LOGGED.compareAndSet(false, true)) {
            Metallum.LOGGER.info(
                    "[metallum] M4 Sodium arena lifetime fix active; "
                            + "retiring old backings through deferred destruction"
            );
        }
        buffer.close();
    }
}
