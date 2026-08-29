package com.metallum.client.metal.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.metallum.mixin.sodium.SectionRenderDataStorageAccessor;
import com.metallum.mixin.sodium.TerrainDrawMetadataBatch;
import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferSegment;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;

import java.util.List;

/**
 * Captures sidecar records at Sodium's section/facing command producer.
 * Nothing in this class is allocated or entered when the feature is off.
 */
public final class TerrainDrawMetadataCapture {
    private static final ThreadLocal<FillContext> CURRENT = new ThreadLocal<>();

    private TerrainDrawMetadataCapture() {
    }

    public static boolean enabled() {
        return TerrainSceneSnapshot.drawMetadataRequired();
    }

    public static void beginFill(
            final MultiDrawBatch batch,
            final RenderRegion region,
            final SectionRenderDataStorage storage,
            final TerrainRenderPass renderPass
    ) {
        if (!enabled() || !(batch instanceof TerrainDrawMetadataBatch metadataBatch)) {
            return;
        }
        metadataBatch.metallum$setTerrainDrawMetadata(new TerrainDrawMetadataStore());
        CURRENT.set(new FillContext(batch, region, storage, renderPass));
    }

    public static void endFill() {
        if (enabled()) {
            CURRENT.remove();
        }
    }

    /** Records the section pointer/index pair before Sodium enters a draw helper. */
    public static long noteDataPointer(
            final SectionRenderDataStorage storage,
            final int localIndex,
            final Operation<Long> original
    ) {
        long pointer = original.call(storage, localIndex);
        FillContext fill = CURRENT.get();
        if (enabled() && fill != null && fill.storage == storage) {
            fill.section = new SectionContext(localIndex, pointer);
        }
        return pointer;
    }

    public static void beginHelper(
            final MultiDrawBatch batch,
            final long dataPointer,
            final int visibleFaces,
            final boolean localIndex
    ) {
        FillContext fill = CURRENT.get();
        if (!enabled() || fill == null || fill.batch != batch || fill.section == null
                || fill.section.dataPointer != dataPointer) {
            return;
        }
        TerrainDrawMetadataStore store = ((TerrainDrawMetadataBatch) batch).metallum$terrainDrawMetadata();
        if (store == null) {
            return;
        }
        SectionGeneration generation;
        try {
            generation = sectionGeneration(fill.storage, fill.section.localIndex,
                    dataPointer, localIndex);
        } catch (RuntimeException ignored) {
            // Metadata is an optional admission aid.  A retired Sodium mesh
            // must leave the original draw path untouched.
            store.invalidate();
            fill.helper = null;
            return;
        }
        if (generation == null) {
            store.invalidate();
            fill.helper = null;
            return;
        }
        fill.helper = new HelperContext(
                fill,
                localIndex,
                generation,
                visibleFaces,
                localIndex ? List.of() : sharedFaceGroups(dataPointer, visibleFaces)
        );
    }

    public static void endHelper() {
        FillContext fill = CURRENT.get();
        if (fill == null || fill.helper == null) {
            return;
        }
        boolean complete = fill.helper.localIndex
                ? fill.helper.nextPutCall == ModelQuadFacing.COUNT
                : fill.helper.nextGroup == fill.helper.faceGroups.size();
        if (!complete) {
            fill.helper.store().invalidate();
        }
        fill.helper = null;
    }

    /** Called around every real {@code MultiDrawBatch.put} in Sodium's helpers. */
    public static void recordPut(
            final MultiDrawBatch batch,
            final int ordinal,
            final int indexCount,
            final int baseVertex,
            final long firstIndex
    ) {
        FillContext fill = CURRENT.get();
        if (!enabled() || fill == null || fill.batch != batch || fill.helper == null) {
            return;
        }
        HelperContext helper = fill.helper;
        int facingMask;
        if (helper.localIndex) {
            int facing = helper.nextPutCall++;
            if (facing >= ModelQuadFacing.COUNT) {
                helper.store().invalidate();
                return;
            }
            facingMask = TerrainDrawMetadataGrouping.localPutFacingMask(
                    helper.localMask, facing, ModelQuadFacing.COUNT
            );
            // Sodium wrote this invisible put into a slot that is overwritten
            // by the next visible face; it did not advance batch.size.
            if (facingMask == 0) {
                return;
            }
        } else {
            if (helper.nextGroup >= helper.faceGroups.size()) {
                helper.store().invalidate();
                return;
            }
            facingMask = helper.faceGroups.get(helper.nextGroup++);
        }
        try {
            TerrainDrawMetadata metadata = helper.metadata(
                    ordinal,
                    new IrisMetalIndirectCommandStream.IndexedDraw(
                            indexCount, 1, (int) firstIndex, baseVertex, 0
                    ),
                    facingMask
            );
            helper.store().append(metadata);
        } catch (RuntimeException ignored) {
            helper.store().invalidate();
        }
    }

    /** Mirrors Sodium's shared-index run grouping, before any put occurs. */
    private static List<Integer> sharedFaceGroups(final long dataPointer, final int visibleFaces) {
        int[] vertexCounts = new int[ModelQuadFacing.COUNT];
        int[] facings = new int[ModelQuadFacing.COUNT];
        long facingList = SectionRenderDataUnsafe.getFacingList(dataPointer);
        for (int face = 0; face < ModelQuadFacing.COUNT; face++) {
            vertexCounts[face] = (int) SectionRenderDataUnsafe.getVertexCount(dataPointer, face);
            facings[face] = (int) ((facingList >>> (face * 8)) & 255L);
        }
        return TerrainDrawMetadataGrouping.sharedFacingGroups(vertexCounts, facings, visibleFaces);
    }

    private static SectionGeneration sectionGeneration(
            final SectionRenderDataStorage storage,
            final int localIndex,
            final long dataPointer,
            final boolean localIndexMode
    ) {
        if (!(storage instanceof SectionRenderDataStorageAccessor accessor)) {
            return null;
        }
        GlBufferSegment[] vertices = accessor.metallum$getVertexAllocations();
        if (vertices == null || localIndex < 0 || localIndex >= vertices.length) {
            return null;
        }
        GlBufferSegment[] elements = accessor.metallum$getElementAllocations();
        GlBufferSegment vertex = vertices[localIndex];
        GlBufferSegment index = localIndexMode
                ? elements == null || localIndex >= elements.length ? null : elements[localIndex]
                : accessor.metallum$getSharedIndexAllocation();
        if (vertex == null || index == null) {
            return null;
        }
        return new SectionGeneration(
                new TerrainDrawMetadata.ContentGeneration(
                        TerrainDrawMetadata.AllocationStamp.of(vertex),
                        TerrainDrawMetadata.AllocationStamp.of(index),
                        dataPointer,
                        SectionRenderDataUnsafe.getBaseElement(dataPointer),
                        SectionRenderDataUnsafe.getBaseVertex(dataPointer)
                )
        );
    }

    private record SectionGeneration(TerrainDrawMetadata.ContentGeneration content) {
    }

    private static final class FillContext {
        private final MultiDrawBatch batch;
        private final RenderRegion region;
        private final SectionRenderDataStorage storage;
        private final TerrainRenderPass renderPass;
        private SectionContext section;
        private HelperContext helper;

        private FillContext(
                final MultiDrawBatch batch,
                final RenderRegion region,
                final SectionRenderDataStorage storage,
                final TerrainRenderPass renderPass
        ) {
            this.batch = batch;
            this.region = region;
            this.storage = storage;
            this.renderPass = renderPass;
        }
    }

    private record SectionContext(int localIndex, long dataPointer) {
    }

    private static final class HelperContext {
        private final FillContext fill;
        private final boolean localIndex;
        private final SectionGeneration generation;
        private final int localMask;
        private final List<Integer> faceGroups;
        private int nextGroup;
        private int nextPutCall;

        private HelperContext(
                final FillContext fill,
                final boolean localIndex,
                final SectionGeneration generation,
                final int localMask,
                final List<Integer> faceGroups
        ) {
            this.fill = fill;
            this.localIndex = localIndex;
            this.generation = generation;
            this.localMask = localMask;
            this.faceGroups = faceGroups;
        }

        private TerrainDrawMetadataStore store() {
            return ((TerrainDrawMetadataBatch) fill.batch).metallum$terrainDrawMetadata();
        }

        private TerrainDrawMetadata metadata(
                final int ordinal,
                final IrisMetalIndirectCommandStream.IndexedDraw arguments,
                final int facingMask
        ) {
            int localIndexValue = fill.section.localIndex;
            int sectionX = fill.region.getChunkX() + LocalSectionIndex.unpackX(localIndexValue);
            int sectionY = fill.region.getChunkY() + LocalSectionIndex.unpackY(localIndexValue);
            int sectionZ = fill.region.getChunkZ() + LocalSectionIndex.unpackZ(localIndexValue);
            double originX = sectionX * 16.0;
            double originY = sectionY * 16.0;
            double originZ = sectionZ * 16.0;
            TerrainDrawMetadata.Aabb worldAabb = new TerrainDrawMetadata.Aabb(
                    originX, originY, originZ, originX + 16.0, originY + 16.0, originZ + 16.0
            );
            return new TerrainDrawMetadata(
                    ordinal,
                    arguments,
                    new TerrainDrawMetadata.SectionIdentity(
                            fill.region.getX(), fill.region.getY(), fill.region.getZ(),
                            localIndexValue, sectionX, sectionY, sectionZ
                    ),
                    generation.content(),
                    worldAabb,
                    facingMask,
                    fill.renderPass.isTranslucent(),
                    localIndex
            );
        }
    }
}
