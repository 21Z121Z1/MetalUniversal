package com.metallum.client.metal.render;

import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferSegment;
import com.metallum.mixin.sodium.GlBufferSegmentAccessor;

import java.util.Objects;

/**
 * The producer-owned identity attached to one indexed Sodium terrain draw.
 *
 * <p>All fields are values from the same {@code fillCommandBuffer} section
 * loop.  In particular, {@link AllocationStamp#allocation()} is a Sodium
 * mesh allocation object, not a process-wide sequence number or an object
 * hash.  Keeping the allocation reference in the immutable record lets the
 * snapshot detect a replaced mesh even when the allocator reuses offsets.</p>
 */
record TerrainDrawMetadata(
        int ordinal,
        IrisMetalIndirectCommandStream.IndexedDraw arguments,
        TerrainDrawMetadata.SectionIdentity section,
        TerrainDrawMetadata.ContentGeneration contentGeneration,
        TerrainDrawMetadata.Aabb worldAabb,
        TerrainDrawMetadata.Aabb cameraAabb,
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

    /** A value-plus-identity stamp for one Sodium arena range. */
    record AllocationStamp(Object allocation, long offset, long length) {
        static AllocationStamp of(final GlBufferSegment allocation) {
            return allocation == null
                    ? new AllocationStamp(null, -1L, -1L)
                    : new AllocationStamp(allocation, allocation.getOffset(), allocation.getLength());
        }

        boolean live() {
            if (allocation == null) {
                return false;
            }
            if (allocation instanceof GlBufferSegment segment
                    && segment instanceof GlBufferSegmentAccessor accessor) {
                return !accessor.metallum$isFree();
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
        Objects.requireNonNull(cameraAabb, "cameraAabb");
    }
}
