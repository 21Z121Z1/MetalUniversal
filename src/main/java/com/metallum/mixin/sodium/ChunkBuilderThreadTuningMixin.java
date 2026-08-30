package com.metallum.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Opt-in experimental override for Sodium's chunk-build worker count.
 *
 * <p>Sodium deliberately exposes this as a renderer-reload option because the
 * best value depends on CPU topology and on how much frame-time headroom the
 * renderer has. MetalUniversal keeps Sodium's choice unless the experimental
 * system property is explicitly set, which lets the Apple-Silicon benchmark
 * search the neighborhood without changing shipping defaults.</p>
 */
@Mixin(ChunkBuilder.class)
public abstract class ChunkBuilderThreadTuningMixin {
    private static final String THREADS_PROPERTY = "metallum.opt.terrainChunkBuilderThreads";

    @Inject(method = "getThreadCount", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$overrideChunkBuilderThreads(final CallbackInfoReturnable<Integer> cir) {
        final String raw = System.getProperty(THREADS_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return;
        }

        final int requested;
        try {
            requested = Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return;
        }
        if (requested <= 0) {
            return;
        }

        final int available = Math.max(1, Runtime.getRuntime().availableProcessors());
        cir.setReturnValue(Math.min(requested, available));
    }
}
