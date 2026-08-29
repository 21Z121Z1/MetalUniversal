package com.metallum.client.metal.render;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Pre-indexes terrain candidates for source-draw admission.
 *
 * <p>The first visible-ICB rollout joined every submitted draw by scanning the
 * complete candidate list.  That makes admission O(draws * candidates) on a
 * CPU hot path.  This index performs one O(candidates + candidateDraws) build
 * and then narrows every source draw to two hash buckets: an exact draw bucket
 * for candidates with captured draw records and a coarse wildcard bucket for
 * legacy/fixture candidates whose draw list is empty.</p>
 *
 * <p>Fingerprints are only accelerators.  Every hit is revalidated with actual
 * allocation object identity and all fields from the original strict join, so
 * identity-hash or 64-bit fingerprint collisions cannot admit a wrong draw.
 * Ambiguous matches fail closed.</p>
 */
final class TerrainCandidateDrawIndex {
    private static final long HASH_SEED = 0x243f6a8885a308d3L;

    private final TerrainCandidateSnapshot snapshot;
    private final Map<Long, LongBucket> exactDraws;
    private final Map<Long, LongBucket> wildcardCandidates;
    private final int indexedDrawCount;

    private TerrainCandidateDrawIndex(
            final TerrainCandidateSnapshot snapshot,
            final Map<Long, LongBucket> exactDraws,
            final Map<Long, LongBucket> wildcardCandidates,
            final int indexedDrawCount
    ) {
        this.snapshot = snapshot;
        this.exactDraws = exactDraws;
        this.wildcardCandidates = wildcardCandidates;
        this.indexedDrawCount = indexedDrawCount;
    }

    static TerrainCandidateDrawIndex build(final TerrainCandidateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Map<Long, LongBucket> exact = new HashMap<>();
        Map<Long, LongBucket> wildcard = new HashMap<>();
        int drawCount = 0;

        for (int candidateIndex = 0; candidateIndex < snapshot.candidates().size(); candidateIndex++) {
            TerrainCandidateSnapshot.Candidate candidate = snapshot.candidates().get(candidateIndex);
            if (candidate.draws().isEmpty()) {
                add(wildcard, coarseFingerprint(candidate), Integer.toUnsignedLong(candidateIndex));
                continue;
            }
            drawCount = Math.addExact(drawCount, candidate.draws().size());
            for (int drawOrdinal = 0; drawOrdinal < candidate.draws().size(); drawOrdinal++) {
                TerrainCandidateSnapshot.IndexedDrawRecord draw = candidate.draws().get(drawOrdinal);
                long reference = (Integer.toUnsignedLong(candidateIndex) << 32)
                        | Integer.toUnsignedLong(drawOrdinal);
                add(exact, exactFingerprint(candidate, draw), reference);
            }
        }
        return new TerrainCandidateDrawIndex(snapshot, exact, wildcard, drawCount);
    }

    /**
     * Returns the one candidate matching this submitted draw.
     *
     * @throws IllegalArgumentException when the draw is stale, missing, or
     *                                  maps to more than one current candidate
     */
    int uniqueCandidateIndex(final TerrainDrawMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        TerrainDrawMetadata.ContentGeneration generation = metadata.contentGeneration();
        if (generation.dataPointer() <= 0L || generation.baseElement() < 0L) {
            throw new IllegalArgumentException("Terrain draw metadata has no current candidate");
        }

        int found = -1;
        LongBucket exact = exactDraws.get(exactFingerprint(metadata));
        if (exact != null) {
            for (int i = 0; i < exact.size; i++) {
                long reference = exact.values[i];
                int candidateIndex = (int) (reference >>> 32);
                int drawOrdinal = (int) reference;
                TerrainCandidateSnapshot.Candidate candidate = snapshot.candidates().get(candidateIndex);
                if (!sameCandidateCore(candidate, metadata)
                        || drawOrdinal < 0 || drawOrdinal >= candidate.draws().size()
                        || !sameDraw(candidate.draws().get(drawOrdinal), metadata)) {
                    continue;
                }
                found = mergeUnique(found, candidateIndex);
            }
        }

        LongBucket wildcard = wildcardCandidates.get(coarseFingerprint(metadata));
        if (wildcard != null) {
            for (int i = 0; i < wildcard.size; i++) {
                int candidateIndex = (int) wildcard.values[i];
                TerrainCandidateSnapshot.Candidate candidate = snapshot.candidates().get(candidateIndex);
                if (!candidate.draws().isEmpty() || !sameCandidateCore(candidate, metadata)) {
                    continue;
                }
                found = mergeUnique(found, candidateIndex);
            }
        }

        if (found < 0) {
            throw new IllegalArgumentException("Terrain draw metadata has no current candidate");
        }
        return found;
    }

    int candidateCount() {
        return snapshot.candidates().size();
    }

    int indexedDrawCount() {
        return indexedDrawCount;
    }

    private static int mergeUnique(final int found, final int candidateIndex) {
        if (found < 0 || found == candidateIndex) {
            return candidateIndex;
        }
        throw new IllegalArgumentException("Terrain draw metadata maps to ambiguous candidates");
    }

    private static void add(final Map<Long, LongBucket> buckets, final long fingerprint, final long value) {
        buckets.computeIfAbsent(fingerprint, ignored -> new LongBucket()).add(value);
    }

    private static boolean sameCandidateCore(
            final TerrainCandidateSnapshot.Candidate candidate,
            final TerrainDrawMetadata metadata
    ) {
        TerrainCandidateSnapshot.SectionIdentity section = candidate.section();
        TerrainDrawMetadata.SectionIdentity submitted = metadata.section();
        TerrainDrawMetadata.ContentGeneration generation = metadata.contentGeneration();
        TerrainDrawMetadata.AllocationStamp vertex = generation.vertexAllocation();
        TerrainDrawMetadata.AllocationStamp index = generation.indexAllocation();
        return section.regionX() == submitted.regionX()
                && section.regionY() == submitted.regionY()
                && section.regionZ() == submitted.regionZ()
                && section.localIndex() == submitted.localIndex()
                && section.sectionX() == submitted.sectionX()
                && section.sectionY() == submitted.sectionY()
                && section.sectionZ() == submitted.sectionZ()
                && candidate.pass() == (metadata.translucent()
                ? TerrainCandidateSnapshot.TerrainPass.TRANSLUCENT
                : TerrainCandidateSnapshot.TerrainPass.OPAQUE)
                && candidate.localIndex() == metadata.localIndex()
                && sameAllocation(candidate.vertexAllocation(), vertex)
                && sameAllocation(candidate.indexAllocation(), index)
                && sameAabb(candidate.worldAabb(), metadata.worldAabb());
    }

    private static boolean sameDraw(
            final TerrainCandidateSnapshot.IndexedDrawRecord candidateDraw,
            final TerrainDrawMetadata metadata
    ) {
        TerrainDrawMetadata.ContentGeneration generation = metadata.contentGeneration();
        return candidateDraw.arguments().equals(metadata.arguments())
                && candidateDraw.dataPointer() == generation.dataPointer()
                && candidateDraw.facingMask() == metadata.facingMask()
                && sameAllocation(candidateDraw.vertexAllocation(), generation.vertexAllocation())
                && sameAllocation(candidateDraw.indexAllocation(), generation.indexAllocation());
    }

    private static boolean sameAabb(
            final TerrainCandidateSnapshot.Aabb candidate,
            final TerrainDrawMetadata.Aabb submitted
    ) {
        return Double.doubleToLongBits(candidate.minX()) == Double.doubleToLongBits(submitted.minX())
                && Double.doubleToLongBits(candidate.minY()) == Double.doubleToLongBits(submitted.minY())
                && Double.doubleToLongBits(candidate.minZ()) == Double.doubleToLongBits(submitted.minZ())
                && Double.doubleToLongBits(candidate.maxX()) == Double.doubleToLongBits(submitted.maxX())
                && Double.doubleToLongBits(candidate.maxY()) == Double.doubleToLongBits(submitted.maxY())
                && Double.doubleToLongBits(candidate.maxZ()) == Double.doubleToLongBits(submitted.maxZ());
    }

    private static boolean sameAllocation(
            final TerrainCandidateSnapshot.AllocationIdentity candidate,
            final TerrainDrawMetadata.AllocationStamp submitted
    ) {
        return candidate != null && submitted != null
                && candidate.allocation() == submitted.allocation()
                && candidate.offset() == submitted.offset()
                && candidate.length() == submitted.length()
                && candidate.generation() == submitted.generation();
    }

    private static long coarseFingerprint(final TerrainCandidateSnapshot.Candidate candidate) {
        long value = HASH_SEED;
        TerrainCandidateSnapshot.SectionIdentity section = candidate.section();
        value = mixSection(value, section.regionX(), section.regionY(), section.regionZ(), section.localIndex(),
                section.sectionX(), section.sectionY(), section.sectionZ());
        value = mix(value, candidate.pass().ordinal());
        value = mix(value, candidate.localIndex() ? 1L : 0L);
        value = mixAllocation(value, candidate.vertexAllocation().allocation(), candidate.vertexAllocation().offset(),
                candidate.vertexAllocation().length(), candidate.vertexAllocation().generation());
        value = mixAllocation(value, candidate.indexAllocation().allocation(), candidate.indexAllocation().offset(),
                candidate.indexAllocation().length(), candidate.indexAllocation().generation());
        TerrainCandidateSnapshot.Aabb aabb = candidate.worldAabb();
        return mixAabb(value, aabb.minX(), aabb.minY(), aabb.minZ(), aabb.maxX(), aabb.maxY(), aabb.maxZ());
    }

    private static long coarseFingerprint(final TerrainDrawMetadata metadata) {
        long value = HASH_SEED;
        TerrainDrawMetadata.SectionIdentity section = metadata.section();
        value = mixSection(value, section.regionX(), section.regionY(), section.regionZ(), section.localIndex(),
                section.sectionX(), section.sectionY(), section.sectionZ());
        value = mix(value, metadata.translucent()
                ? TerrainCandidateSnapshot.TerrainPass.TRANSLUCENT.ordinal()
                : TerrainCandidateSnapshot.TerrainPass.OPAQUE.ordinal());
        value = mix(value, metadata.localIndex() ? 1L : 0L);
        TerrainDrawMetadata.ContentGeneration generation = metadata.contentGeneration();
        TerrainDrawMetadata.AllocationStamp vertex = generation.vertexAllocation();
        TerrainDrawMetadata.AllocationStamp index = generation.indexAllocation();
        value = mixAllocation(value, vertex.allocation(), vertex.offset(), vertex.length(), vertex.generation());
        value = mixAllocation(value, index.allocation(), index.offset(), index.length(), index.generation());
        TerrainDrawMetadata.Aabb aabb = metadata.worldAabb();
        return mixAabb(value, aabb.minX(), aabb.minY(), aabb.minZ(), aabb.maxX(), aabb.maxY(), aabb.maxZ());
    }

    private static long exactFingerprint(
            final TerrainCandidateSnapshot.Candidate candidate,
            final TerrainCandidateSnapshot.IndexedDrawRecord draw
    ) {
        long value = coarseFingerprint(candidate);
        value = mixDrawArguments(value, draw.arguments());
        value = mix(value, draw.dataPointer());
        return mix(value, draw.facingMask());
    }

    private static long exactFingerprint(final TerrainDrawMetadata metadata) {
        long value = coarseFingerprint(metadata);
        value = mixDrawArguments(value, metadata.arguments());
        value = mix(value, metadata.contentGeneration().dataPointer());
        return mix(value, metadata.facingMask());
    }

    private static long mixSection(
            long value,
            final int regionX,
            final int regionY,
            final int regionZ,
            final int localIndex,
            final int sectionX,
            final int sectionY,
            final int sectionZ
    ) {
        value = mix(value, regionX);
        value = mix(value, regionY);
        value = mix(value, regionZ);
        value = mix(value, localIndex);
        value = mix(value, sectionX);
        value = mix(value, sectionY);
        return mix(value, sectionZ);
    }

    private static long mixAllocation(
            long value,
            final Object allocation,
            final long offset,
            final long length,
            final long generation
    ) {
        value = mix(value, System.identityHashCode(allocation));
        value = mix(value, offset);
        value = mix(value, length);
        return mix(value, generation);
    }

    private static long mixAabb(
            long value,
            final double minX,
            final double minY,
            final double minZ,
            final double maxX,
            final double maxY,
            final double maxZ
    ) {
        value = mix(value, Double.doubleToLongBits(minX));
        value = mix(value, Double.doubleToLongBits(minY));
        value = mix(value, Double.doubleToLongBits(minZ));
        value = mix(value, Double.doubleToLongBits(maxX));
        value = mix(value, Double.doubleToLongBits(maxY));
        return mix(value, Double.doubleToLongBits(maxZ));
    }

    private static long mixDrawArguments(
            long value,
            final IrisMetalIndirectCommandStream.IndexedDraw draw
    ) {
        value = mix(value, draw.indexCount());
        value = mix(value, draw.instanceCount());
        value = mix(value, draw.firstIndex());
        value = mix(value, draw.baseVertex());
        return mix(value, draw.firstInstance());
    }

    private static long mix(long value, final long input) {
        value ^= input + 0x9e3779b97f4a7c15L + (value << 6) + (value >>> 2);
        value *= 0xbf58476d1ce4e5b9L;
        return value ^ (value >>> 29);
    }

    private static final class LongBucket {
        private long[] values = new long[2];
        private int size;

        private void add(final long value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, Math.multiplyExact(values.length, 2));
            }
            values[size++] = value;
        }
    }
}
