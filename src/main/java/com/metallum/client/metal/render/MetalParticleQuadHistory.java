package com.metallum.client.metal.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;

/** Per-particle correspondence across draw partitions, independent of renderer/GPU identities. */
final class MetalParticleQuadHistory {
    record Batch<T>(List<Long> ids, float[] positions, T proof) {
        Batch { ids = List.copyOf(ids); positions = positions.clone(); }
        @Override public float[] positions() { return positions.clone(); }
    }

    record Match<T>(float[] positions, T previousProof, List<T> previousSources) {
        Match { positions = positions.clone(); previousSources = List.copyOf(previousSources); }
        @Override public float[] positions() { return positions.clone(); }
    }

    private record Quad<T>(int batch, int offset, Batch<T> source) {}

    private MetalParticleQuadHistory() {}

    static <T> List<Optional<Match<T>>> remap(List<Batch<T>> previous, List<Batch<T>> current,
                                             BiPredicate<T, T> compatible, BiPredicate<T, T> samePrevious) {
        if (!valid(previous) || !valid(current)) return missing(current.size());
        Map<Long, List<Quad<T>>> old = index(previous), fresh = index(current);
        List<Optional<Match<T>>> result = new ArrayList<>();
        for (int batchIndex = 0; batchIndex < current.size(); batchIndex++) {
            Batch<T> batch = current.get(batchIndex);
            float[] positions = new float[batch.positions.length];
            java.util.Arrays.fill(positions, Float.NaN);
            Map<Long, Integer> occurrences = new HashMap<>();
            T reference = null;
            // Geometry and necessary visibility inputs must come from the same matched
            // sources. A single representative cannot describe a merged draw's alpha.
            Map<Integer, T> sources = new LinkedHashMap<>();
            boolean rejected = false;
            for (int quad = 0; quad < batch.ids.size(); quad++) {
                long id = batch.ids.get(quad);
                int occurrence = occurrences.merge(id, 1, Integer::sum) - 1;
                List<Quad<T>> before = old.get(id), after = fresh.get(id);
                // Multiple emissions have an order only within one source draw.
                // A changed count or emission across groups is explicit invalid history.
                if (before == null || before.size() != after.size() || !oneDraw(before) || !oneDraw(after)) continue;
                Quad<T> source = before.get(occurrence);
                T candidate = source.source.proof;
                if (!compatible.test(batch.proof, candidate)
                        || (reference != null && !samePrevious.test(reference, candidate))) {
                    rejected = true;
                    break;
                }
                reference = candidate;
                sources.putIfAbsent(source.batch, candidate);
                System.arraycopy(source.source.positions, source.offset * 12, positions, quad * 12, 12);
            }
            result.add(rejected || reference == null ? Optional.empty()
                    : Optional.of(new Match<>(positions, reference, new ArrayList<>(sources.values()))));
        }
        return List.copyOf(result);
    }

    private static <T> boolean valid(List<Batch<T>> batches) {
        for (Batch<T> batch : batches) {
            if (batch.proof == null || batch.ids.isEmpty() || batch.positions.length != (long) batch.ids.size() * 12
                    || batch.ids.stream().anyMatch(id -> id == null || id <= 0)) return false;
            for (float position : batch.positions) if (!Float.isFinite(position)) return false;
        }
        return !batches.isEmpty();
    }

    private static <T> Map<Long, List<Quad<T>>> index(List<Batch<T>> batches) {
        Map<Long, List<Quad<T>>> result = new HashMap<>();
        for (int batch = 0; batch < batches.size(); batch++) {
            Batch<T> source = batches.get(batch);
            for (int quad = 0; quad < source.ids.size(); quad++)
                result.computeIfAbsent(source.ids.get(quad), ignored -> new ArrayList<>()).add(new Quad<>(batch, quad, source));
        }
        return result;
    }

    private static <T> boolean oneDraw(List<Quad<T>> quads) {
        int first = quads.getFirst().batch();
        return quads.stream().allMatch(quad -> quad.batch() == first);
    }

    private static <T> List<Optional<Match<T>>> missing(int count) {
        List<Optional<Match<T>>> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(Optional.empty());
        return List.copyOf(result);
    }
}
