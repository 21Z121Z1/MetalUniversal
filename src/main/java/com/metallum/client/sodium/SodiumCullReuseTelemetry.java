package com.metallum.client.sodium;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Process-local counters for the verify-only Sodium cull-reuse lane. */
public final class SodiumCullReuseTelemetry {
    private static final AtomicLong candidates = new AtomicLong();
    private static final AtomicLong matches = new AtomicLong();
    private static final AtomicLong mismatches = new AtomicLong();
    private static final AtomicLong racyCompletions = new AtomicLong();
    private static final Map<SodiumCullReusePolicy.AdmissionReason, AtomicLong> reasons =
            new EnumMap<>(SodiumCullReusePolicy.AdmissionReason.class);

    static {
        for (SodiumCullReusePolicy.AdmissionReason reason : SodiumCullReusePolicy.AdmissionReason.values()) {
            reasons.put(reason, new AtomicLong());
        }
    }

    private SodiumCullReuseTelemetry() {
    }

    public static void recordAdmission(SodiumCullReusePolicy.AdmissionReason reason) {
        reasons.get(reason).incrementAndGet();
        if (reason == SodiumCullReusePolicy.AdmissionReason.ELIGIBLE) {
            candidates.incrementAndGet();
        }
    }

    public static void recordMatch() {
        matches.incrementAndGet();
    }

    public static void recordMismatch() {
        mismatches.incrementAndGet();
    }

    public static void recordRacyCompletion() {
        racyCompletions.incrementAndGet();
    }

    public static long candidateCount() {
        return candidates.get();
    }

    public static long matchCount() {
        return matches.get();
    }

    public static long mismatchCount() {
        return mismatches.get();
    }

    public static long racyCompletionCount() {
        return racyCompletions.get();
    }

    public static long reasonCount(SodiumCullReusePolicy.AdmissionReason reason) {
        return reasons.get(reason).get();
    }
}
