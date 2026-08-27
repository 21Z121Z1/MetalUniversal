package com.metallum.client.metal.render;

import com.metallum.mixin.sodium.GlBufferSegmentAccessor;
import com.metallum.mixin.sodium.GlBufferSegmentGeneration;
import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferSegment;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.List;
import java.util.Objects;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Immutable producer-side input for a future terrain visibility decision.
 *
 * <p>This deliberately contains no visible-list or draw-list state.  A
 * snapshot is made from Sodium's mesh-ready section registry before Sodium's
 * cull task is allowed to publish its visible lists.</p>
 */
public final class TerrainCandidateSnapshot {
    /** Enables the value-only GPU visibility probe; it never changes draw authority. */
    public static final String GPU_VISIBILITY_PROBE_PROPERTY =
            "metallum.opt.terrainGpuVisibilityProbe";
    public static final boolean GPU_VISIBILITY_PROBE_ENABLED = Boolean.parseBoolean(
            System.getProperty(GPU_VISIBILITY_PROBE_PROPERTY, "false")
    );
    /** Candidate ABI: eight float32 values (min xyz, max xyz, range, reserved). */
    public static final int GPU_VISIBILITY_CANDIDATE_STRIDE_BYTES = 32;
    public static final int GPU_VISIBILITY_MATRIX_BYTES = 16 * Float.BYTES;
    /** Hard cap shared by Java and Swift so count/word arithmetic stays bounded. */
    public static final int GPU_VISIBILITY_MAX_CANDIDATES = 1 << 20;

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
     * Conservative CPU reference for the native probe contract.  The result
     * is visible unless all eight corners are outside one complete clip plane.
     * Non-finite values and non-positive clip w are uncertain and visible.
     */
    public record VisibilityDecision(boolean visible, boolean uncertain) {
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
    private final long epoch;

    TerrainCandidateSnapshot(
            final CameraPosition camera,
            final VisibilityTransform visibilityTransform,
            final List<Candidate> candidates
    ) {
        this(0L, camera, visibilityTransform, candidates);
    }

    TerrainCandidateSnapshot(
            final long epoch,
            final CameraPosition camera,
            final VisibilityTransform visibilityTransform,
            final List<Candidate> candidates
    ) {
        if (epoch < 0L) {
            throw new IllegalArgumentException("Terrain candidate snapshot epoch must be non-negative");
        }
        this.epoch = epoch;
        this.camera = Objects.requireNonNull(camera, "camera");
        this.visibilityTransform = Objects.requireNonNull(visibilityTransform, "visibilityTransform");
        this.candidates = List.copyOf(candidates);
    }

    public long epoch() {
        return epoch;
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

    /**
     * Packs all producer candidates as camera-relative float32 values.  The
     * subtraction intentionally occurs in double precision before narrowing;
     * any overflow, non-finite value, or malformed range rejects the complete
     * probe rather than allowing a partially meaningful dispatch.
     */
    public MemorySegment packGpuVisibilityCandidates(final Arena arena) {
        Objects.requireNonNull(arena, "arena");
        if (candidates.isEmpty()) {
            return MemorySegment.NULL;
        }
        final long bytes = gpuVisibilityCandidateBytes(candidates.size());
        final MemorySegment packed = arena.allocate(bytes, 16);
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            Aabb aabb = candidate.worldAabb();
            double minX = aabb.minX() - camera.x();
            double minY = aabb.minY() - camera.y();
            double minZ = aabb.minZ() - camera.z();
            double maxX = aabb.maxX() - camera.x();
            double maxY = aabb.maxY() - camera.y();
            double maxZ = aabb.maxZ() - camera.z();
            long offset = (long) index * GPU_VISIBILITY_CANDIDATE_STRIDE_BYTES;
            packed.set(ValueLayout.JAVA_FLOAT, offset, conservativeMin(minX));
            packed.set(ValueLayout.JAVA_FLOAT, offset + 4, conservativeMin(minY));
            packed.set(ValueLayout.JAVA_FLOAT, offset + 8, conservativeMin(minZ));
            packed.set(ValueLayout.JAVA_FLOAT, offset + 12, conservativeMax(maxX));
            packed.set(ValueLayout.JAVA_FLOAT, offset + 16, conservativeMax(maxY));
            packed.set(ValueLayout.JAVA_FLOAT, offset + 20, conservativeMax(maxZ));
            packed.set(ValueLayout.JAVA_FLOAT, offset + 24,
                    checkedRange(minX, minY, minZ, maxX, maxY, maxZ));
            packed.set(ValueLayout.JAVA_FLOAT, offset + 28, 0.0F);
        }
        return packed;
    }

    static int gpuVisibilityWordCount(final int candidateCount) {
        if (candidateCount < 0 || candidateCount > GPU_VISIBILITY_MAX_CANDIDATES) {
            throw new IllegalArgumentException("Terrain visibility candidate count exceeds the bounded ABI");
        }
        return (candidateCount + 31) >>> 5;
    }

    static long gpuVisibilityCandidateBytes(final int candidateCount) {
        if (candidateCount < 0 || candidateCount > GPU_VISIBILITY_MAX_CANDIDATES) {
            throw new IllegalArgumentException("Terrain visibility candidate count exceeds the bounded ABI");
        }
        return Math.multiplyExact((long) candidateCount, GPU_VISIBILITY_CANDIDATE_STRIDE_BYTES);
    }

    /** Packs the fixed column-major clipFromCameraRelative matrix contract. */
    public MemorySegment packGpuVisibilityMatrix(final Arena arena) {
        Objects.requireNonNull(arena, "arena");
        final MemorySegment packed = arena.allocate(GPU_VISIBILITY_MATRIX_BYTES, 16);
        final float[] values = new float[]{
                visibilityTransform.m00(), visibilityTransform.m01(),
                visibilityTransform.m02(), visibilityTransform.m03(),
                visibilityTransform.m10(), visibilityTransform.m11(),
                visibilityTransform.m12(), visibilityTransform.m13(),
                visibilityTransform.m20(), visibilityTransform.m21(),
                visibilityTransform.m22(), visibilityTransform.m23(),
                visibilityTransform.m30(), visibilityTransform.m31(),
                visibilityTransform.m32(), visibilityTransform.m33()
        };
        for (int index = 0; index < values.length; index++) {
            if (!Float.isFinite(values[index])) {
                throw new IllegalArgumentException("Terrain visibility matrix contains a non-finite value");
            }
            packed.set(ValueLayout.JAVA_FLOAT, (long) index * Float.BYTES, values[index]);
        }
        return packed;
    }

    /** Pure CPU contract helper used by focused tests and native differential checks. */
    public static VisibilityDecision referenceDecision(
            final VisibilityTransform transform,
            final float minX,
            final float minY,
            final float minZ,
            final float maxX,
            final float maxY,
            final float maxZ
    ) {
        Objects.requireNonNull(transform, "transform");
        if (!orderedFinite(minX, minY, minZ, maxX, maxY, maxZ)
                || !finiteTransform(transform)) {
            return new VisibilityDecision(true, true);
        }
        boolean outsideLeft = true;
        boolean outsideRight = true;
        boolean outsideBottom = true;
        boolean outsideTop = true;
        boolean outsideNear = true;
        boolean outsideFar = true;
        for (int xIndex = 0; xIndex < 2; xIndex++) {
            float x = xIndex == 0 ? minX : maxX;
            for (int yIndex = 0; yIndex < 2; yIndex++) {
                float y = yIndex == 0 ? minY : maxY;
                for (int zIndex = 0; zIndex < 2; zIndex++) {
                    float z = zIndex == 0 ? minZ : maxZ;
                    float cx = transform.m00() * x + transform.m10() * y
                            + transform.m20() * z + transform.m30();
                    float cy = transform.m01() * x + transform.m11() * y
                            + transform.m21() * z + transform.m31();
                    float cz = transform.m02() * x + transform.m12() * y
                            + transform.m22() * z + transform.m32();
                    float cw = transform.m03() * x + transform.m13() * y
                            + transform.m23() * z + transform.m33();
                    if (!Float.isFinite(cx) || !Float.isFinite(cy) || !Float.isFinite(cz)
                            || !Float.isFinite(cw) || cw <= 0.0F) {
                        return new VisibilityDecision(true, true);
                    }
                    outsideLeft &= cx < -cw;
                    outsideRight &= cx > cw;
                    outsideBottom &= cy < -cw;
                    outsideTop &= cy > cw;
                    outsideNear &= cz < -cw;
                    outsideFar &= cz > cw;
                }
            }
        }
        return new VisibilityDecision(
                !(outsideLeft || outsideRight || outsideBottom || outsideTop || outsideNear || outsideFar),
                false
        );
    }

    private static float checkedFloat(final double value) {
        if (!Double.isFinite(value) || value > Float.MAX_VALUE || value < -Float.MAX_VALUE) {
            throw new IllegalArgumentException("Terrain visibility camera-relative value overflowed float32");
        }
        float narrowed = (float) value;
        if (!Float.isFinite(narrowed)) {
            throw new IllegalArgumentException("Terrain visibility camera-relative value is non-finite");
        }
        return narrowed;
    }

    /**
     * Narrows a minimum outward.  This matters at the clip boundary: a plain
     * float cast can move a mathematical minimum inward by one ulp and turn a
     * conservative AABB test into a false negative once masking is added.
     */
    private static float conservativeMin(final double value) {
        float narrowed = checkedFloat(value);
        if ((double) narrowed > value) {
            narrowed = Math.nextDown(narrowed);
        }
        if (!Float.isFinite(narrowed)) {
            throw new IllegalArgumentException("Terrain visibility minimum became non-finite");
        }
        return narrowed;
    }

    /** Narrows a maximum outward; see {@link #conservativeMin(double)}. */
    private static float conservativeMax(final double value) {
        float narrowed = checkedFloat(value);
        if ((double) narrowed < value) {
            narrowed = Math.nextUp(narrowed);
        }
        if (!Float.isFinite(narrowed)) {
            throw new IllegalArgumentException("Terrain visibility maximum became non-finite");
        }
        return narrowed;
    }

    private static float checkedRange(
            final double minX,
            final double minY,
            final double minZ,
            final double maxX,
            final double maxY,
            final double maxZ
    ) {
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)) {
            throw new IllegalArgumentException("Terrain visibility range is non-finite");
        }
        double range = Math.max(
                Math.max(Math.abs(minX), Math.max(Math.abs(minY), Math.abs(minZ))),
                Math.max(Math.abs(maxX), Math.max(Math.abs(maxY), Math.abs(maxZ)))
        );
        return conservativeMax(range);
    }

    private static boolean orderedFinite(
            final float minX,
            final float minY,
            final float minZ,
            final float maxX,
            final float maxY,
            final float maxZ
    ) {
        return Float.isFinite(minX) && Float.isFinite(minY) && Float.isFinite(minZ)
                && Float.isFinite(maxX) && Float.isFinite(maxY) && Float.isFinite(maxZ)
                && minX <= maxX && minY <= maxY && minZ <= maxZ;
    }

    private static boolean finiteTransform(final VisibilityTransform transform) {
        return Float.isFinite(transform.m00()) && Float.isFinite(transform.m01())
                && Float.isFinite(transform.m02()) && Float.isFinite(transform.m03())
                && Float.isFinite(transform.m10()) && Float.isFinite(transform.m11())
                && Float.isFinite(transform.m12()) && Float.isFinite(transform.m13())
                && Float.isFinite(transform.m20()) && Float.isFinite(transform.m21())
                && Float.isFinite(transform.m22()) && Float.isFinite(transform.m23())
                && Float.isFinite(transform.m30()) && Float.isFinite(transform.m31())
                && Float.isFinite(transform.m32()) && Float.isFinite(transform.m33());
    }
}
