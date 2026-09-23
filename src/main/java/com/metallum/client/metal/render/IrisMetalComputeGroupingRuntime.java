package com.metallum.client.metal.render;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/**
 * Evidence and allocation-based admission for scoped Iris compute grouping.
 * Encoder ownership lives in MetalCommandEncoder, never in a ThreadLocal or a
 * mixin callback. Names, reflection order and shader bindings are not identities.
 */
public final class IrisMetalComputeGroupingRuntime {
    private static final LongAdder admissionCandidates = new LongAdder();
    private static final LongAdder admissions = new LongAdder();
    private static final LongAdder rejections = new LongAdder();
    private static final LongAdder analysisFailures = new LongAdder();
    private static final LongAdder deferredPassCloses = new LongAdder();

    private IrisMetalComputeGroupingRuntime() {
    }

    static void recordAdmission(final boolean reused) {
        admissionCandidates.increment();
        if (reused) admissions.increment();
        else rejections.increment();
    }

    static void recordAnalysisFailure() {
        analysisFailures.increment();
    }

    static void recordDeferredClose() {
        deferredPassCloses.increment();
    }

    /** Immutable accesses to actual renderer-owned allocation generations. */
    record AccessSet(Set<MetalAllocationIdentity> reads, Set<MetalAllocationIdentity> writes) {
        AccessSet {
            reads = Set.copyOf(reads);
            writes = Set.copyOf(writes);
        }
    }

    /** Accumulates only the dispatches in one still-live native encoder. */
    static final class IndependenceWindow {
        private final Set<MetalAllocationIdentity> reads = new HashSet<>();
        private final Set<MetalAllocationIdentity> writes = new HashSet<>();

        boolean admits(final AccessSet next) {
            return java.util.Collections.disjoint(next.reads(), writes)
                    && java.util.Collections.disjoint(next.writes(), reads)
                    && java.util.Collections.disjoint(next.writes(), writes);
        }

        void append(final AccessSet next) {
            reads.addAll(next.reads());
            writes.addAll(next.writes());
        }

        void reset() {
            reads.clear();
            writes.clear();
        }
    }

    public static synchronized Snapshot snapshot() {
        return new Snapshot(admissionCandidates.sum(), admissions.sum(), rejections.sum(),
                analysisFailures.sum(), deferredPassCloses.sum());
    }

    public static synchronized void reset() {
        admissionCandidates.reset();
        admissions.reset();
        rejections.reset();
        analysisFailures.reset();
        deferredPassCloses.reset();
    }

    public record Snapshot(long admissionCandidates, long admissions, long rejections,
                           long analysisFailures, long deferredPassCloses) {
    }
}
