package com.metallum.mixin.terrain;

import com.metallum.client.terrain.VanillaTerrainWorkTelemetry;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({ChunkSectionsToRender.DrawIndirect.class, ChunkSectionsToRender.DrawSeparate.class})
abstract class ChunkSectionsDrawTerrainWorkMixin {
    @Unique
    private ChunkSectionLayer metallum$terrainLayer;

    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true)
    private ChunkSectionLayer metallum$captureLayer(final ChunkSectionLayer layer) {
        this.metallum$terrainLayer = layer;
        return layer;
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void metallum$recordCompletedLayerSubmission(final CallbackInfo ci) {
        ChunkSectionLayer layer = this.metallum$terrainLayer;
        this.metallum$terrainLayer = null;
        if (layer != null) {
            com.metallum.client.metal.render.FrameEvidenceRuntime.producer("vanilla-terrain-layer-return");
            // RETURN is reached only after every vanilla indirect/separate draw call for this layer
            // has returned normally. This is the submission-authority boundary, not preparation.
            VanillaTerrainWorkTelemetry.layerRendered(this, layer);
        }
    }
}
