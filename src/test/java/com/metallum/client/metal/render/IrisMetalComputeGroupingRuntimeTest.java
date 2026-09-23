package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class IrisMetalComputeGroupingRuntimeTest {
    private static final MetalAllocationIdentity A = new MetalAllocationIdentity(101L, 1L);
    private static final MetalAllocationIdentity B = new MetalAllocationIdentity(102L, 1L);
    private static final MetalAllocationIdentity C = new MetalAllocationIdentity(103L, 1L);

    @Test
    void admissionRejectsAllReadWriteHazardsAndAllowsOnlyIndependentWork() {
        // All read/write subsets over three actual allocations: 4096 pairs.
        // The oracle checks each allocation directly rather than calling the implementation's set operations.
        for (int priorReads = 0; priorReads < 8; priorReads++) {
            for (int priorWrites = 0; priorWrites < 8; priorWrites++) {
                var window = new IrisMetalComputeGroupingRuntime.IndependenceWindow();
                window.append(accesses(priorReads, priorWrites));
                for (int nextReads = 0; nextReads < 8; nextReads++) {
                    for (int nextWrites = 0; nextWrites < 8; nextWrites++) {
                        boolean hazard = false;
                        for (int bit = 1; bit < 8; bit <<= 1) {
                            boolean previousWrite = (priorWrites & bit) != 0;
                            boolean previousRead = (priorReads & bit) != 0;
                            boolean currentWrite = (nextWrites & bit) != 0;
                            boolean currentRead = (nextReads & bit) != 0;
                            hazard |= previousWrite && (currentRead || currentWrite)
                                    || previousRead && currentWrite;
                        }
                        assertEquals(!hazard, window.admits(accesses(nextReads, nextWrites)),
                                "prior=" + priorReads + "/" + priorWrites + " next=" + nextReads + "/" + nextWrites);
                    }
                }
            }
        }
    }

    @Test
    void hazardsAreCheckedAgainstEveryDispatchInTheOpenEncoder() {
        var window = new IrisMetalComputeGroupingRuntime.IndependenceWindow();
        window.append(accesses(1, 1));
        assertTrue(window.admits(accesses(2, 2)));
        window.append(accesses(2, 2));
        assertFalse(window.admits(accesses(1, 0)), "the first dispatch remains an outstanding writer");
        assertFalse(window.admits(accesses(0, 2)), "the second dispatch remains an outstanding writer");
        assertTrue(window.admits(accesses(4, 4)));
        window.reset();
        assertTrue(window.admits(accesses(3, 3)), "a real encoder/fence boundary retires the old access window");
    }

    @Test
    void identitiesIncludeAllocationGenerationAndDoNotDependOnNamesOrBindingIndices() {
        var window = new IrisMetalComputeGroupingRuntime.IndependenceWindow();
        window.append(new IrisMetalComputeGroupingRuntime.AccessSet(Set.of(), Set.of(A)));
        assertFalse(window.admits(new IrisMetalComputeGroupingRuntime.AccessSet(
                Set.of(new MetalAllocationIdentity(A.allocationId(), A.generation())), Set.of())));
        assertTrue(window.admits(new IrisMetalComputeGroupingRuntime.AccessSet(
                Set.of(new MetalAllocationIdentity(A.allocationId(), A.generation() + 1L)), Set.of())));
        assertTrue(window.admits(new IrisMetalComputeGroupingRuntime.AccessSet(Set.of(B), Set.of())));
    }

    @Test
    void accessSnapshotsCannotBeChangedAfterAdmission() {
        var reads = new HashSet<>(Set.of(A));
        var writes = new HashSet<>(Set.of(B));
        var accesses = new IrisMetalComputeGroupingRuntime.AccessSet(reads, writes);
        reads.clear();
        writes.add(C);
        assertEquals(Set.of(A), accesses.reads());
        assertEquals(Set.of(B), accesses.writes());
        assertThrows(UnsupportedOperationException.class, () -> accesses.reads().add(C));
        assertThrows(UnsupportedOperationException.class, () -> accesses.writes().clear());
        assertThrows(NullPointerException.class,
                () -> new IrisMetalComputeGroupingRuntime.AccessSet(null, Set.of()));
        assertThrows(NullPointerException.class,
                () -> new IrisMetalComputeGroupingRuntime.AccessSet(Set.of(), null));
    }

    private static IrisMetalComputeGroupingRuntime.AccessSet accesses(int reads, int writes) {
        return new IrisMetalComputeGroupingRuntime.AccessSet(identities(reads), identities(writes));
    }

    private static Set<MetalAllocationIdentity> identities(int bits) {
        Set<MetalAllocationIdentity> result = new HashSet<>();
        if ((bits & 1) != 0) result.add(A);
        if ((bits & 2) != 0) result.add(B);
        if ((bits & 4) != 0) result.add(C);
        return result;
    }
}
