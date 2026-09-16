package com.metallum.client.metal.render;

import net.caffeinemc.mods.sodium.client.gpu.arena.BufferSegment;

import java.util.Objects;

/**
 * The producer-owned identity attached to one indexed Sodium terrain draw.
 *
 * <p>All fields are values from the same terrain-command production section
 * loop. In particular, {@link AllocationStamp#allocation()} is the optional
 * Sodium mesh allocation identity, not draw authority. Minecraft 26.3's
 * vanilla RenderPearl/MDI path can operate without constructing these stamps.</p>
 */
record TerrainDrawMetadata(
        int ordinal,
        IrisMetalIndirectCommandStream.IndexedDraw arguments,
        TerrainDrawMetadata.SectionIdentity section,
        TerrainDrawMetadata.ContentGeneration contentGeneration,
        TerrainDrawMetadata.Aabb worldAabb,
        int facingMask,
        boolean translucent,
        boolean localIndex
) {
    record SectionIdentity(
            int regionX,
            int regionY,
            int regionZ,
            int localIndex,
            int sectionX,
            int sectionY,
            int sectionZ
    ) {
        SectionIdentity {
            if (localIndex < 0) {
                throw new IllegalArgumentException("Sodium section local index must be non-negative");
            }
        }
    }

    record Aabb(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        Aabb {
            if (!(minX <= maxX && minY <= maxY && minZ <= maxZ)) {
                throw new IllegalArgumentException("Terrain draw AABB must have ordered bounds");
            }
        }
    }

    /** A value-plus-identity stamp for one optional Sodium arena range. */
    record AllocationStamp(Object allocation, long offset, long length, long generation) {
        AllocationStamp(final Object allocation, final long offset, final long length) {
            this(allocation, offset, length, 0L);
        }

        static AllocationStamp of(final BufferSegment allocation) {
            if (allocation == null) {
                return new AllocationStamp(null, -1L, -1L, -1L);
            }
            long generation = TerrainSegmentIdentity.generation(allocation);
            return new AllocationStamp(allocation, allocation.getOffset(), allocation.getLength(), generation);
        }

        boolean live() {
            if (allocation == null) {
                return false;
            }
            if (allocation instanceof BufferSegment segment) {
                long liveGeneration = TerrainSegmentIdentity.generation(segment);
                if (TerrainSegmentIdentity.isFree(segment) || liveGeneration < 0L) {
                    return false;
                }
                return liveGeneration == generation
                        && segment.getOffset() == offset
                        && segment.getLength() == length;
            }
            // Host contract fixtures use opaque allocation identities.
            return true;
        }
    }

    /** Sodium's mesh allocation identity and the current native mesh record. */
    record ContentGeneration(
            AllocationStamp vertexAllocation,
            AllocationStamp indexAllocation,
            long dataPointer,
            long baseElement,
            long baseVertex
    ) {
        ContentGeneration {
            Objects.requireNonNull(vertexAllocation, "vertexAllocation");
            Objects.requireNonNull(indexAllocation, "indexAllocation");
        }

        boolean live() {
            return vertexAllocation.live() && indexAllocation.live();
        }
    }

    TerrainDrawMetadata {
        if (ordinal < 0 || facingMask == 0) {
            throw new IllegalArgumentException("Terrain draw metadata has invalid ordinal or facing mask");
        }
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(contentGeneration, "contentGeneration");
        Objects.requireNonNull(worldAabb, "worldAabb");
    }
}
