package com.metallum.mixin.sodium;

import com.metallum.client.terrain.TerrainRuntimeSignals;
import com.metallum.client.terrain.TerrainSchedulingController;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Opens and closes one controller sample around Sodium's terrain update loop. */
@Mixin(SodiumWorldRenderer.class)
public abstract class SodiumWorldRendererTerrainSchedulingMixin {
    @Inject(method = "setupTerrain", at = @At("HEAD"), remap = false)
    private void metallum$beginTerrainFrame(
            final Camera camera,
            final Viewport viewport,
            final FogParameters fogParameters,
            final boolean spectator,
            final boolean updateChunks,
            final Matrix4f cullingMatrix,
            final CallbackInfo ci
    ) {
        TerrainSchedulingController controller = TerrainSchedulingController.runtime();
        if (!controller.observesFrames()) {
            return;
        }
        Vector3fc forward = camera.forwardVector();
        var position = camera.position();
        controller.beginFrame(
                System.nanoTime(),
                position.x(),
                position.y(),
                position.z(),
                forward.x(),
                forward.y(),
                forward.z(),
                TerrainRuntimeSignals.sample(controller)
        );
    }

    @Inject(method = "setupTerrain", at = @At("RETURN"), remap = false)
    private void metallum$endTerrainFrame(
            final Camera camera,
            final Viewport viewport,
            final FogParameters fogParameters,
            final boolean spectator,
            final boolean updateChunks,
            final Matrix4f cullingMatrix,
            final CallbackInfo ci
    ) {
        TerrainSchedulingController controller = TerrainSchedulingController.runtime();
        if (controller.observesFrames()) {
            controller.endFrame(System.nanoTime());
        }
    }
}
