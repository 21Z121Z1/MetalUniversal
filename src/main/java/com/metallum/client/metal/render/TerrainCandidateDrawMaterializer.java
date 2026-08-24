package com.metallum.client.metal.render;

import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;

import java.util.ArrayList;
import java.util.List;

/**
 * Replays only Sodium's indexed-command arithmetic against mesh data already
 * published in {@code SectionRenderDataStorage}.  This class intentionally
 * has no access to sorted render lists, CPU visible lists, or draw sidecars.
 */
final class TerrainCandidateDrawMaterializer {
    private static final long UINT32_MAX = 0xffff_ffffL;

    private TerrainCandidateDrawMaterializer() {
    }

    /**
     * Returns null for malformed/retired arithmetic.  An empty list is a
     * valid mesh-ready candidate with no non-empty geometry.
     */
    static List<TerrainCandidateSnapshot.IndexedDrawRecord> materialize(
            final TerrainCandidateSnapshot.SectionIdentity section,
            final TerrainCandidateSnapshot.TerrainPass pass,
            final boolean localIndex,
            final long dataPointer,
            final long baseElement,
            final long baseVertex,
            final long[] vertexCounts,
            final int[] facings,
            final TerrainCandidateSnapshot.AllocationIdentity vertexAllocation,
            final TerrainCandidateSnapshot.AllocationIdentity indexAllocation
    ) {
        if (section == null || pass == null || dataPointer <= 0L
                || !validUInt32(baseElement) || !validUInt32(baseVertex)
                || vertexCounts == null || vertexCounts.length != ModelQuadFacing.COUNT
                || facings == null || facings.length != ModelQuadFacing.COUNT
                || vertexAllocation == null || indexAllocation == null
                || !validAllocationOffset(vertexAllocation.offset())
                || !validAllocationOffset(indexAllocation.offset())
                || vertexAllocation.offset() != baseVertex
                || indexAllocation.offset() != baseElement) {
            return null;
        }

        final int[] groupingCounts = new int[ModelQuadFacing.COUNT];
        for (int face = 0; face < ModelQuadFacing.COUNT; face++) {
            long vertexCount = vertexCounts[face];
            if (!validUInt32(vertexCount) || (vertexCount & 3L) != 0L) {
                return null;
            }
            int facing = facings[face];
            if (facing < 0 || facing >= ModelQuadFacing.COUNT) {
                return null;
            }
            groupingCounts[face] = (int) vertexCount;
        }

        return localIndex
                ? local(section, pass, dataPointer, baseElement, baseVertex,
                vertexCounts, vertexAllocation, indexAllocation)
                : shared(section, pass, dataPointer, baseElement, baseVertex,
                vertexCounts, facings, groupingCounts, vertexAllocation, indexAllocation);
    }

    private static List<TerrainCandidateSnapshot.IndexedDrawRecord> local(
            final TerrainCandidateSnapshot.SectionIdentity section,
            final TerrainCandidateSnapshot.TerrainPass pass,
            final long dataPointer,
            final long initialBaseElement,
            final long initialBaseVertex,
            final long[] vertexCounts,
            final TerrainCandidateSnapshot.AllocationIdentity vertexAllocation,
            final TerrainCandidateSnapshot.AllocationIdentity indexAllocation
    ) {
        long baseElement = initialBaseElement;
        long baseVertex = initialBaseVertex;
        ArrayList<TerrainCandidateSnapshot.IndexedDrawRecord> records = new ArrayList<>();

        for (int face = 0; face < ModelQuadFacing.COUNT; face++) {
            long vertexCount = vertexCounts[face];
            long indexCount = indexCount(vertexCount);
            if (indexCount < 0L || indexCount > Integer.MAX_VALUE
                    || baseElement > Integer.MAX_VALUE
                    || !validUInt32(baseElement) || !validUInt32(baseVertex)) {
                return null;
            }
            if (vertexCount != 0L) {
                records.add(record(
                        records.size(), section, pass, dataPointer,
                        (int) baseElement, signedUInt32(baseVertex),
                        1 << face, vertexAllocation, indexAllocation,
                        (int) indexCount
                ));
            }
            try {
                baseVertex = Math.addExact(baseVertex, vertexCount);
                baseElement = Math.addExact(baseElement, indexCount);
            } catch (ArithmeticException exception) {
                return null;
            }
            if (!validUInt32(baseElement) || !validUInt32(baseVertex)) {
                return null;
            }
        }
        return List.copyOf(records);
    }

    private static List<TerrainCandidateSnapshot.IndexedDrawRecord> shared(
            final TerrainCandidateSnapshot.SectionIdentity section,
            final TerrainCandidateSnapshot.TerrainPass pass,
            final long dataPointer,
            final long baseElement,
            final long initialBaseVertex,
            final long[] vertexCounts,
            final int[] facings,
            final int[] groupingCounts,
            final TerrainCandidateSnapshot.AllocationIdentity vertexAllocation,
            final TerrainCandidateSnapshot.AllocationIdentity indexAllocation
    ) {
        List<Integer> groups = TerrainDrawMetadataGrouping.sharedGeometryGroups(groupingCounts, facings);
        long baseVertex = initialBaseVertex;
        ArrayList<TerrainCandidateSnapshot.IndexedDrawRecord> records = new ArrayList<>(groups.size());
        for (int groupMask : groups) {
            long groupVertexCount = 0L;
            for (int face = 0; face < ModelQuadFacing.COUNT; face++) {
                if ((groupMask & (1 << face)) != 0) {
                    long vertexCount = vertexCounts[face];
                    if (vertexCount == 0L) {
                        return null;
                    }
                    try {
                        groupVertexCount = Math.addExact(groupVertexCount, vertexCount);
                    } catch (ArithmeticException exception) {
                        return null;
                    }
                }
            }
            long indexCount = indexCount(groupVertexCount);
            if (groupVertexCount == 0L || indexCount < 0L || indexCount > Integer.MAX_VALUE
                    || baseElement > Integer.MAX_VALUE || !validUInt32(baseVertex)) {
                return null;
            }
            records.add(record(
                    records.size(), section, pass, dataPointer,
                    (int) baseElement, signedUInt32(baseVertex), groupMask,
                    vertexAllocation, indexAllocation, (int) indexCount
            ));
            try {
                baseVertex = Math.addExact(baseVertex, groupVertexCount);
            } catch (ArithmeticException exception) {
                return null;
            }
            if (!validUInt32(baseVertex)) {
                return null;
            }
        }
        return List.copyOf(records);
    }

    private static TerrainCandidateSnapshot.IndexedDrawRecord record(
            final int ordinal,
            final TerrainCandidateSnapshot.SectionIdentity section,
            final TerrainCandidateSnapshot.TerrainPass pass,
            final long dataPointer,
            final int firstIndex,
            final int baseVertex,
            final int facingMask,
            final TerrainCandidateSnapshot.AllocationIdentity vertexAllocation,
            final TerrainCandidateSnapshot.AllocationIdentity indexAllocation,
            final int indexCount
    ) {
        return new TerrainCandidateSnapshot.IndexedDrawRecord(
                ordinal,
                section,
                pass,
                new IrisMetalIndirectCommandStream.IndexedDraw(
                        indexCount, 1, firstIndex, baseVertex, 0
                ),
                facingMask,
                dataPointer,
                vertexAllocation,
                indexAllocation
        );
    }

    private static long indexCount(final long vertexCount) {
        if (!validUInt32(vertexCount)) {
            return -1L;
        }
        try {
            return Math.multiplyExact(vertexCount >>> 2, 6L);
        } catch (ArithmeticException exception) {
            return -1L;
        }
    }

    private static boolean validAllocationOffset(final long offset) {
        return validUInt32(offset);
    }

    private static boolean validUInt32(final long value) {
        return value >= 0L && value <= UINT32_MAX;
    }

    private static int signedUInt32(final long value) {
        return (int) value;
    }
}
