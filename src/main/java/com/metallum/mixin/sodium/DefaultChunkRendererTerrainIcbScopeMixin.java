package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.MetalTerrainIcbScope;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Narrows the experimental ICB path to Sodium chunk-render submissions.
 *
 * <p>The pilot remains default-off. If Sodium changes the render method shape,
 * this required mixin intentionally fails during development rather than
 * silently widening ICB admission to unrelated render passes.</p>
 */
@Mixin(DefaultChunkRenderer.class)
public abstract class DefaultChunkRendererTerrainIcbScopeMixin {
    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void metallum$enterTerrainIcbScope(final CallbackInfo ci) {
        MetalTerrainIcbScope.enter();
    }

    @Inject(method = "render", at = @At("RETURN"), remap = false)
    private void metallum$exitTerrainIcbScope(final CallbackInfo ci) {
        MetalTerrainIcbScope.exit();
    }
}
