package com.metallum.client.metal.render;

import com.metallum.mixin.sodium.GlBufferSegmentAccessor;
import com.metallum.mixin.sodium.GlBufferSegmentGeneration;
import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferSegment;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.List;
import java.util.Objects;

/**
 * Immutable producer-side input for a future terrain visibility decision.
 *
 * <p>This deliberately contains no visible-list or draw-list state.  A
 * snapshot is made from Sodium's mesh-ready section registry before Sodium's
 * cull task is allowed to publish its visible lists.</p>
 */
public final class TerrainCandidateSnapshot {
    public enum TerrainPass {
        OPAQUE,
        TRANSLUCENT
    }

    public record CameraPosition(double x, double y, double z) {
    }

    /** A deep-copied, value-only visibility transform. */
    public record VisibilityTransform(
            float m00, float m01, float m02, float m03,
            float m10, float m11, float m12, float m13,
            float m20, float m21, float m22, float m23,
            float m30, float m31, float m32, float m33
    ) {
        static VisibilityTransform copyOf(final Matrix4fc matrix) {
            Objects.requireNonNull(matrix, "matrix");
            return new VisibilityTransform(
                    matrix.m00(), matrix.m01(), matrix.m02(), matrix.m03(),
                    matrix.m10(), matrix.m11(), matrix.m12(), matrix.m13(),
                    matrix.m20(), matrix.m21(), matrix.m22(), matrix.m23(),
                    matrix.m30(), matrix.m31(), matrix.m32(), matrix.m33()
            );
        }

        /** Returns a new mutable matrix; the snapshot never exposes one. */
        public Matrix4f toMatrix() {
            return new Matrix4f().set(
                    m00, m01, m02, m03,
                    m10, m11, m12, m13,
                    m20, m21, m22, m23,
                    m30, m31, m32, m33
            );
        }
    }

    public record SectionIdentity(
            int regionX,
            int regionY,
            int regionZ,
            int localIndex,
            int sectionX,
            int sectionY,
            int sectionZ
    ) {
        public SectionIdentity {
            if (localIndex < 0) {
                throw new IllegalArgumentException("Sodium section local index must be non-negative");
            }
        }
    }

    public record Aabb(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public Aabb {
            if (!(minX <= maxX && minY <= maxY && minZ <= maxZ)) {
                throw new IllegalArgumentException("Terrain candidate AABB must have ordered bounds");
            }
        }
    }

    /**
     * Allocation identity copied from Sodium's live arena segment.  The
     * allocation object is retained as the producer-owned identity; offset,
     * length and the per-segment generation reject allocator ABA reuse.  The
     * optional Metal backing identity is required for renderer-owned shared
     * index buffers, whose backing can be swapped without changing the Java
     * object.
     */
    public record AllocationIdentity(
            Object allocation,
            long offset,
            long length,
            long generation,
            MetalAllocationIdentity backingIdentity
    ) {
        public AllocationIdentity(
                final Object allocation,
                final long offset,
                final long length,
                final long generation
        ) {
            this(allocation, offset, length, generation, null);
        }

        public AllocationIdentity {
            Objects.requireNonNull(allocation, "allocation");
            if (offset < 0L || length <= 0L || generation < 0L) {
                throw new IllegalArgumentException("Invalid terrain allocation identity");
            }
            if (backingIdentity != null && generation != backingIdentity.generation()) {
                throw new IllegalArgumentException("Allocation generation must match its Metal backing identity");
            }
        }

        /**
         * Re-reads Sodium's retirement bit and generation instead of trusting
         * the immutable snapshot value.  Unknown allocation objects are not
         * admitted: contract fixtures may still construct them, but a real
         * candidate can only contain an arena segment or renderer-owned Metal
         * shared-index buffer.
         */
        boolean live() {
            if (allocation instanceof GlBufferSegment segment) {
                if (!(segment instanceof GlBufferSegmentAccessor accessor)
                        || accessor.metallum$isFree()
                        || !(segment instanceof GlBufferSegmentGeneration stamped)) {
                    return false;
                }
                try {
                    return segment.getOffset() == offset
                            && segment.getLength() == length
                            && stamped.metallum$generation() == generation;
                } catch (RuntimeException exception) {
                    return false;
                }
            }
            if (allocation instanceof MetalGpuBuffer metalBuffer && backingIdentity != null) {
                try {
                    if (metalBuffer.isClosed() || metalBuffer.size() != length) {
                        return false;
                    }
                    MetalAllocationIdentity live = metalBuffer.allocationIdentity();
                    return live.equals(backingIdentity) && live.generation() == generation;
                } catch (RuntimeException exception) {
                    return false;
                }
            }
            return false;
        }
    }

    public record Candidate(
            SectionIdentity section,
            Aabb worldAabb,
            TerrainPass pass,
            boolean localIndex,
            AllocationIdentity vertexAllocation,
            AllocationIdentity indexAllocation,
            List<IndexedDrawRecord> draws
    ) {
        public Candidate(
                final SectionIdentity section,
                final Aabb worldAabb,
                final TerrainPass pass,
                final boolean localIndex,
                final AllocationIdentity vertexAllocation,
                final AllocationIdentity indexAllocation
        ) {
            this(section, worldAabb, pass, localIndex, vertexAllocation, indexAllocation, List.of());
        }

        public Candidate {
            Objects.requireNonNull(section, "section");
            Objects.requireNonNull(worldAabb, "worldAabb");
            Objects.requireNonNull(pass, "pass");
            Objects.requireNonNull(vertexAllocation, "vertexAllocation");
            Objects.requireNonNull(indexAllocation, "indexAllocation");
            draws = List.copyOf(draws);
            for (int ordinal = 0; ordinal < draws.size(); ordinal++) {
                IndexedDrawRecord draw = Objects.requireNonNull(draws.get(ordinal), "draw");
                if (draw.ordinal() != ordinal
                        || !draw.section().equals(section)
                        || draw.pass() != pass
                        || !draw.vertexAllocation().equals(vertexAllocation)
                        || !draw.indexAllocation().equals(indexAllocation)) {
                    throw new IllegalArgumentException("Candidate draw records must preserve candidate identity and ordinal");
                }
            }
        }

        /** A cheap producer-side admission check before a future GPU consumer uses the records. */
        boolean recordsLive() {
            return !draws.isEmpty()
                    && vertexAllocation.live()
                    && indexAllocation.live()
                    && draws.stream().allMatch(IndexedDrawRecord::live);
        }
    }

    /**
     * Value-only indexed draw input for future GPU frustum/ICB compaction.
     * The data pointer is retained as a content-generation token; the
     * registry re-materializes against the live storage before publication or
     * any later consumer admission.
     */
    public record IndexedDrawRecord(
            int ordinal,
            SectionIdentity section,
            TerrainPass pass,
            IrisMetalIndirectCommandStream.IndexedDraw arguments,
            int facingMask,
            long dataPointer,
            AllocationIdentity vertexAllocation,
            AllocationIdentity indexAllocation
    ) {
        public IndexedDrawRecord {
            if (ordinal < 0 || facingMask == 0 || dataPointer <= 0L) {
                throw new IllegalArgumentException("Invalid terrain candidate indexed draw record");
            }
            Objects.requireNonNull(section, "section");
            Objects.requireNonNull(pass, "pass");
            Objects.requireNonNull(arguments, "arguments");
            Objects.requireNonNull(vertexAllocation, "vertexAllocation");
            Objects.requireNonNull(indexAllocation, "indexAllocation");
        }

        boolean live() {
            return dataPointer > 0L && vertexAllocation.live() && indexAllocation.live();
        }
    }

    private final CameraPosition camera;
    private final VisibilityTransform visibilityTransform;
    private final List<Candidate> candidates;

    TerrainCandidateSnapshot(
            final CameraPosition camera,
            final VisibilityTransform visibilityTransform,
            final List<Candidate> candidates
    ) {
        this.camera = Objects.requireNonNull(camera, "camera");
        this.visibilityTransform = Objects.requireNonNull(visibilityTransform, "visibilityTransform");
        this.candidates = List.copyOf(candidates);
    }

    public CameraPosition camera() {
        return camera;
    }

    public VisibilityTransform visibilityTransform() {
        return visibilityTransform;
    }

    public List<Candidate> candidates() {
        return candidates;
    }
}
