package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class MetalParticleQuadHistoryTest {
    private record Proof(String layout, String transform, String surface, String uniforms) {}
    private static final Proof SAME = new Proof("particle", "old-mvp", "atlas-generation-1", "old-source-bytes");

    @Test void mergingAndSplittingGroupsKeepsEachParticlesActualOldQuad() {
        var merged = remap(List.of(batch(SAME, new long[]{1}, 11), batch(SAME, new long[]{2}, 22)),
                List.of(batch(SAME, new long[]{2, 1, 3}, 99, 99, 99)));
        assertEquals(22, merged.getFirst().orElseThrow().positions()[0]);
        assertEquals(11, merged.getFirst().orElseThrow().positions()[12]);
        assertTrue(Float.isNaN(merged.getFirst().orElseThrow().positions()[24]));
        var split = remap(List.of(batch(SAME, new long[]{1, 2}, 11, 22)),
                List.of(batch(SAME, new long[]{2}, 99), batch(SAME, new long[]{1}, 99)));
        assertEquals(22, split.get(0).orElseThrow().positions()[0]);
        assertEquals(11, split.get(1).orElseThrow().positions()[0]);
    }

    @Test void reorderedGroupsDoNotReuseDrawOrdinalsAsParticleIdentity() {
        var matches = remap(List.of(batch(SAME, new long[]{1}, 11), batch(SAME, new long[]{2}, 22)),
                List.of(batch(SAME, new long[]{2}, 99), batch(SAME, new long[]{1}, 99)));
        assertEquals(22, matches.get(0).orElseThrow().positions()[0]);
        assertEquals(11, matches.get(1).orElseThrow().positions()[0]);
    }

    @Test void incompatibleOldTransformsMaterialsUniformsAndLayoutsRejectAMergedDraw() {
        for (Proof changed : List.of(new Proof("particle", "different-mvp", SAME.surface, SAME.uniforms),
                new Proof("particle", SAME.transform, "different-atlas-generation", SAME.uniforms),
                new Proof("particle", SAME.transform, SAME.surface, "different-old-source-bytes"),
                new Proof("custom", SAME.transform, SAME.surface, SAME.uniforms))) {
            assertTrue(remap(List.of(batch(SAME, new long[]{1}, 11), batch(changed, new long[]{2}, 22)),
                    List.of(batch(SAME, new long[]{1, 2}, 99, 99))).getFirst().isEmpty(), changed.toString());
        }
    }

    @Test void changedEmissionCountOrAmbiguousCrossGroupEmissionDoesNotCorruptAnUnrelatedParticle() {
        var changed = remap(List.of(batch(SAME, new long[]{1, 1, 2}, 11, 12, 22)),
                List.of(batch(SAME, new long[]{1, 2}, 99, 99))).getFirst().orElseThrow().positions();
        assertTrue(Float.isNaN(changed[0])); assertEquals(22, changed[12]);
        var ambiguous = remap(List.of(batch(SAME, new long[]{1}, 11), batch(SAME, new long[]{1, 2}, 12, 22)),
                List.of(batch(SAME, new long[]{1, 1, 2}, 99, 99, 99))).getFirst().orElseThrow().positions();
        assertTrue(Float.isNaN(ambiguous[0])); assertTrue(Float.isNaN(ambiguous[12])); assertEquals(22, ambiguous[24]);
    }

    @Test void stableMultiQuadEmissionsKeepTheirOwnEmissionOrderAfterMovingGroup() {
        var matches = remap(List.of(batch(SAME, new long[]{1, 1}, 11, 12), batch(SAME, new long[]{2}, 22)),
                List.of(batch(SAME, new long[]{2}, 99), batch(SAME, new long[]{1, 1}, 99, 99)));
        assertEquals(11, matches.get(1).orElseThrow().positions()[0]);
        assertEquals(12, matches.get(1).orElseThrow().positions()[12]);
    }

    @Test void absentOldParticlesInvalidIdsAndMalformedPayloadCannotInventAReference() {
        assertTrue(remap(List.of(batch(SAME, new long[]{1}, 11)),
                List.of(batch(SAME, new long[]{2}, 99))).getFirst().isEmpty());
        assertTrue(remap(List.of(batch(SAME, new long[]{0}, 11)),
                List.of(batch(SAME, new long[]{1}, 99))).getFirst().isEmpty());
        var malformed = new MetalParticleQuadHistory.Batch<>(List.of(1L), new float[11], SAME);
        assertTrue(remap(List.of(malformed), List.of(batch(SAME, new long[]{1}, 99))).getFirst().isEmpty());
        var nonfinite = batch(SAME, new long[]{1}, Float.NaN);
        assertTrue(remap(List.of(nonfinite), List.of(batch(SAME, new long[]{1}, 99))).getFirst().isEmpty());
    }

    @Test void returnedAndInputArraysCannotMutateAcceptedHistory() {
        var old = batch(SAME, new long[]{1}, 11); old.positions()[0] = 42;
        var match = remap(List.of(old), List.of(batch(SAME, new long[]{1}, 99))).getFirst().orElseThrow();
        match.positions()[0] = 43;
        assertEquals(11, match.positions()[0]);
    }

    @Test void mergedProofRetainsEveryMatchedSourceInIdOrderAndCannotBeMutated() {
        record Alpha(int value) {}
        var a = new Alpha(0); var b = new Alpha(255);
        var before = List.of(new MetalParticleQuadHistory.Batch<>(List.of(1L), new float[12], a),
                new MetalParticleQuadHistory.Batch<>(List.of(2L), new float[12], b));
        for (var ids : List.of(List.of(2L, 1L), List.of(1L, 2L))) {
            var current = List.of(new MetalParticleQuadHistory.Batch<>(ids, new float[24], b));
            var match = MetalParticleQuadHistory.remap(before, current, (fresh, old) -> true, (x, y) -> true)
                    .getFirst().orElseThrow();
            assertEquals(ids.getFirst() == 2L ? List.of(b, a) : List.of(a, b), match.previousSources());
            assertThrows(UnsupportedOperationException.class, () -> match.previousSources().clear());
        }
    }

    private static MetalParticleQuadHistory.Batch<Proof> batch(Proof proof, long[] ids, float... values) {
        float[] positions = new float[ids.length * 12];
        for (int i = 0; i < ids.length; i++) Arrays.fill(positions, i * 12, (i + 1) * 12, values[i]);
        return new MetalParticleQuadHistory.Batch<>(Arrays.stream(ids).boxed().toList(), positions, proof);
    }

    private static List<java.util.Optional<MetalParticleQuadHistory.Match<Proof>>> remap(
            List<MetalParticleQuadHistory.Batch<Proof>> old, List<MetalParticleQuadHistory.Batch<Proof>> current) {
        return MetalParticleQuadHistory.remap(old, current,
                (fresh, previous) -> fresh.layout.equals(previous.layout) && fresh.surface.equals(previous.surface),
                Proof::equals);
    }
}
