package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.TerrainCandidateRegistry;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures value-only camera/matrix state before Sodium's cull preparation. */
@Mixin(SodiumWorldRenderer.class)
public abstract class SodiumWorldRendererTerrainCandidateMixin {
    @Inject(method = "setupTerrain", at = @At("HEAD"), remap = false)
    private void metallum$candidateFrame(
            final Camera camera,
            final Viewport viewport,
            final FogParameters fogParameters,
            final boolean spectator,
            final boolean updateChunks,
            final Matrix4f cullingMatrix,
            final CallbackInfo ci
    ) {
        if (!TerrainCandidateRegistry.enabled()) {
            return;
        }
        var position = camera.position();
        TerrainCandidateRegistry.captureFrame(
                position.x(), position.y(), position.z(), cullingMatrix
        );
    }
}
