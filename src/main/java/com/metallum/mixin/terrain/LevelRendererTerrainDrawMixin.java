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

        // Vanilla's extractSectionDrawGroups() owns this exact lock while deriving batch
        // membership from visibleSections. Re-take the same lock for the evidence snapshot so
        // buffer-slice allocation cannot change between our per-section membership checks.
        dispatcher.lock();
        try {
            for (SectionRenderDispatcher.RenderSection section : renderer.visibleSections()) {
                SectionMesh mesh = section.getSectionMesh();
                VanillaTerrainWorkTracker.DrawToken token =
                        VanillaTerrainWorkTelemetry.drawTokenForMesh(mesh);
                if (token == null) {
                    continue;
                }
                for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                    SectionMesh.SectionDraw draw = mesh.getSectionDraw(layer);
                    SectionRenderDispatcher.RenderSectionBufferSlice slice =
                            dispatcher.getRenderSectionSlice(mesh, layer);
                    // Keep this predicate byte-for-byte semantic with vanilla 26.3
                    // extractSectionDrawGroups(): custom-index draws are not submitted when their
                    // index slice is unavailable. Over-admitting here would fabricate
                    // FIRST_VALID_DRAW after a layer that never contained this section.
                    if (slice == null
                            || draw == null
                            || (draw.hasCustomIndexBuffer() && slice.indexBuffer() == null)) {
                        continue;
                    }
                    candidates.computeIfAbsent(layer, ignored -> new ArrayList<>()).add(token);
                }
            }
        } finally {
            dispatcher.unlock();
        }

        VanillaTerrainWorkTelemetry.attachDrawBatch(
                batch,
                VanillaTerrainWorkTelemetry.nextFrameIndex(),
                candidates
        );
    }
}
