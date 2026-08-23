package com.metallum.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.metallum.client.metal.render.TerrainSceneSnapshot;
import com.metallum.client.metal.render.TerrainSubmissionScope;
import com.metallum.client.metal.render.TerrainDrawMetadataCapture;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Opens the snapshot transaction only at Sodium's terrain batch producer. */
@Mixin(DefaultChunkRenderer.class)
public abstract class DefaultChunkRendererTerrainSceneMixin {
    @WrapMethod(method = "fillCommandBuffer", remap = false)
    private static void metallum$terrainDrawMetadataFill(
            final MultiDrawBatch batch,
            final RenderRegion region,
            final SectionRenderDataStorage storage,
            final ChunkRenderList renderList,
            final CameraTransform camera,
            final TerrainRenderPass renderPass,
            final boolean useBlockFaceCulling,
            final boolean isTranslucent,
            final Operation<Void> original
    ) {
        TerrainDrawMetadataCapture.beginFill(batch, region, storage, renderPass);
        try {
            original.call(
                    batch, region, storage, renderList, camera, renderPass,
                    useBlockFaceCulling, isTranslucent
            );
        } finally {
            TerrainDrawMetadataCapture.endFill();
        }
    }

    @WrapOperation(
            method = "fillCommandBuffer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/data/SectionRenderDataStorage;"
                            + "getDataPointer(I)J"
            ),
            remap = false
    )
    private static long metallum$terrainDrawMetadataSection(
            final SectionRenderDataStorage storage,
            final int localIndex,
            final Operation<Long> original
    ) {
        return TerrainDrawMetadataCapture.noteDataPointer(storage, localIndex, original);
    }

    @WrapMethod(method = "addLocalIndexedDrawCommands", remap = false)
    private static void metallum$terrainDrawMetadataLocal(
            final MultiDrawBatch batch,
            final long dataPointer,
            final int visibleFaces,
            final Operation<Void> original
    ) {
        TerrainDrawMetadataCapture.beginHelper(batch, dataPointer, visibleFaces, true);
        try {
            original.call(batch, dataPointer, visibleFaces);
        } finally {
            TerrainDrawMetadataCapture.endHelper();
        }
    }

    @WrapMethod(method = "addSharedIndexedDrawCommands", remap = false)
    private static void metallum$terrainDrawMetadataShared(
            final MultiDrawBatch batch,
            final long dataPointer,
            final int visibleFaces,
            final Operation<Void> original
    ) {
        TerrainDrawMetadataCapture.beginHelper(batch, dataPointer, visibleFaces, false);
        try {
            original.call(batch, dataPointer, visibleFaces);
        } finally {
            TerrainDrawMetadataCapture.endHelper();
        }
    }

    @WrapOperation(
            method = {"addLocalIndexedDrawCommands", "addSharedIndexedDrawCommands"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/gpu/device/batch/MultiDrawBatch;"
                            + "put(IIIJ)V"
            ),
            remap = false
    )
    private static void metallum$terrainDrawMetadataPut(
            final MultiDrawBatch batch,
            final int ordinal,
            final int indexCount,
            final int baseVertex,
            final long firstIndex,
            final Operation<Void> original
    ) {
        TerrainDrawMetadataCapture.recordPut(batch, ordinal, indexCount, baseVertex, firstIndex);
        original.call(batch, ordinal, indexCount, baseVertex, firstIndex);
    }

    @WrapOperation(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/gpu/device/batch/MultiDrawBatch;draw("
                            + "Lnet/caffeinemc/mods/sodium/client/gpu/device/context/DrawContext;)V"
            ),
            remap = false
    )
    private void metallum$terrainBatchScope(
            final MultiDrawBatch batch,
            final DrawContext drawContext,
            final Operation<Void> original
    ) {
        if (!TerrainSceneSnapshot.captureEnabled()) {
            original.call(batch, drawContext);
            return;
        }
        try (TerrainSubmissionScope ignored = TerrainSubmissionScope.begin()) {
            original.call(batch, drawContext);
        }
    }
}
