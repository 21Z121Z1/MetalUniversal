package com.metallum.client.sodium;

import java.util.concurrent.atomic.AtomicLong;

public final class SodiumVisibilitySweepTelemetry {
    private static final AtomicLong verified = new AtomicLong();
    private static final AtomicLong mismatches = new AtomicLong();
    private static final AtomicLong accelerated = new AtomicLong();

    private SodiumVisibilitySweepTelemetry() {
    }

    public static void recordVerified(boolean match) {
        verified.incrementAndGet();
        if (!match) {
            mismatches.incrementAndGet();
        }
    }

    public static void recordAccelerated() {
        accelerated.incrementAndGet();
    }

    public static long verifiedCount() {
        return verified.get();
    }

    public static long mismatchCount() {
        return mismatches.get();
    }

    public static long acceleratedCount() {
        return accelerated.get();
    }
}
