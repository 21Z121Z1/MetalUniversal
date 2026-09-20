package com.metallum.mixin.terrain;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.metallum.client.terrain.VanillaTerrainWorkTelemetry;
import com.metallum.client.terrain.VanillaTerrainWorkTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
abstract class LevelRendererTerrainDrawMixin {
    @Inject(method = {"prepareChunkRendersIndirect", "prepareChunkRenders"}, at = @At("RETURN"))
    private void metallum$attachPublishedTerrainGenerations(
            final Matrix4fc modelViewMatrix,
            final boolean respectTranslucentOrder,
            final CallbackInfoReturnable<ChunkSectionsToRender> cir
    ) {
        ChunkSectionsToRender batch = cir.getReturnValue();
        if (batch == null) {
            return;
        }

        LevelRenderer renderer = (LevelRenderer)(Object)this;
        SectionRenderDispatcher dispatcher = renderer.sectionRenderDispatcher();
        Map<Object, List<VanillaTerrainWorkTracker.DrawToken>> candidates = new IdentityHashMap<>();

        for (SectionRenderDispatcher.RenderSection section : renderer.visibleSections()) {
            SectionMesh mesh = section.getSectionMesh();
            VanillaTerrainWorkTracker.DrawToken token =
                    VanillaTerrainWorkTelemetry.drawTokenForMesh(mesh);
            if (token == null) {
                continue;
            }
            for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                if (mesh.getSectionDraw(layer) == null) {
                    continue;
                }
                // Mirror vanilla's physical slice admission. If the final buffer slice cannot be
                // resolved, this section is not an authority candidate for the prepared batch.
                if (dispatcher.getRenderSectionSlice(mesh, layer) == null) {
                    continue;
                }
                candidates.computeIfAbsent(layer, ignored -> new ArrayList<>()).add(token);
            }
        }

        VanillaTerrainWorkTelemetry.attachDrawBatch(
                batch,
                VanillaTerrainWorkTelemetry.nextFrameIndex(),
                candidates
        );
    }
}
