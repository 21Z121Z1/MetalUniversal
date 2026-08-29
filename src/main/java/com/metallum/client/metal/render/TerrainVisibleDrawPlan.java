package com.metallum.client.metal.render;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * Immutable source-draw to visibility-candidate mapping for GPU-authored
 * terrain ICBs.
 *
 * <p>The source draw ordinal remains the ICB slot. The mapped candidate ordinal
 * is used only to read the GPU visibility bitset. Construction is deliberately
 * fail-closed: draw metadata must be complete, live, and resolve to exactly one
 * candidate with producer-materialized draw records. Legacy wildcard candidates
 * are useful for diagnostics, but are not draw-authoritative enough for culling.</p>
 */
final class TerrainVisibleDrawPlan {
    private final long candidateEpoch;
    private final int candidateCount;
    private final int[] candidateBySourceOrdinal;

    private TerrainVisibleDrawPlan(
            final long candidateEpoch,
            final int candidateCount,
            final int[] candidateBySourceOrdinal
    ) {
        this.candidateEpoch = candidateEpoch;
        this.candidateCount = candidateCount;
        this.candidateBySourceOrdinal = candidateBySourceOrdinal;
    }

    /**
     * Returns a complete plan, or {@code null} when the current producer state
     * cannot safely authorize visibility-masked ICB generation.
     */
    static TerrainVisibleDrawPlan tryBuild(
            final TerrainSceneSnapshot source,
            final TerrainCandidateSnapshot candidates
    ) {
        try {
            return buildStrict(source, candidates);
        } catch (RuntimeException rejected) {
            return null;
        }
    }

    static TerrainVisibleDrawPlan buildStrict(
            final TerrainSceneSnapshot source,
            final TerrainCandidateSnapshot candidates
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(candidates, "candidates");
        final int sourceDrawCount = source.draws().size();
        final int candidateCount = candidates.candidates().size();
        if (sourceDrawCount <= 0) {
            throw new IllegalArgumentException("Visible terrain ICB requires source draws");
        }
        if (candidateCount <= 0
                || candidateCount > TerrainCandidateSnapshot.GPU_VISIBILITY_MAX_CANDIDATES) {
            throw new IllegalArgumentException("Visible terrain ICB requires bounded candidates");
        }

        final TerrainCandidateDrawIndex index = TerrainCandidateDrawIndex.build(candidates);
        final int[] mapping = new int[sourceDrawCount];
        for (int sourceOrdinal = 0; sourceOrdinal < sourceDrawCount; sourceOrdinal++) {
            TerrainSceneSnapshot.Draw sourceDraw = source.draws().get(sourceOrdinal);
            if (sourceDraw.ordinal() != sourceOrdinal || sourceDraw.metadata() == null) {
                throw new IllegalArgumentException("Visible terrain ICB requires ordinal-bound draw metadata");
            }
            TerrainDrawMetadata metadata = sourceDraw.metadata();
            if (!metadata.arguments().equals(sourceDraw.arguments())
                    || !metadata.contentGeneration().live()) {
                throw new IllegalArgumentException("Visible terrain ICB draw metadata is stale");
            }

            int candidateIndex = index.uniqueCandidateIndex(metadata);
            if (candidateIndex < 0 || candidateIndex >= candidateCount) {
                throw new IllegalArgumentException("Visible terrain ICB candidate index is out of range");
            }
            TerrainCandidateSnapshot.Candidate candidate = candidates.candidates().get(candidateIndex);
            // Empty draw lists are coarse diagnostic/legacy wildcard records.
            // They cannot prove which source draw belongs to the candidate.
            if (candidate.draws().isEmpty() || !candidate.recordsLive()) {
                throw new IllegalArgumentException("Visible terrain ICB candidate is not draw-authoritative");
            }
            mapping[sourceOrdinal] = candidateIndex;
        }
        return new TerrainVisibleDrawPlan(candidates.epoch(), candidateCount, mapping);
    }

    long candidateEpoch() {
        return candidateEpoch;
    }

    int candidateCount() {
        return candidateCount;
    }

    int drawCount() {
        return candidateBySourceOrdinal.length;
    }

    int candidateIndex(final int sourceOrdinal) {
        return candidateBySourceOrdinal[sourceOrdinal];
    }

    /** Packs one uint32-compatible candidate ordinal per source ICB slot. */
    MemorySegment packCandidateIndices(final Arena arena) {
        Objects.requireNonNull(arena, "arena");
        MemorySegment packed = arena.allocate(
                Math.multiplyExact((long) candidateBySourceOrdinal.length, Integer.BYTES),
                Integer.BYTES
        );
        for (int sourceOrdinal = 0; sourceOrdinal < candidateBySourceOrdinal.length; sourceOrdinal++) {
            packed.set(
                    ValueLayout.JAVA_INT,
                    (long) sourceOrdinal * Integer.BYTES,
                    candidateBySourceOrdinal[sourceOrdinal]
            );
        }
        return packed;
    }
}
