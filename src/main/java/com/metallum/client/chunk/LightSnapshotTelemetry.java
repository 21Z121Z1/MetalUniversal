package com.metallum.client.chunk;

import java.util.concurrent.atomic.AtomicLong;

public final class LightSnapshotTelemetry {
    private static final AtomicLong fastSnapshots = new AtomicLong();
    private static final AtomicLong verifiedSnapshots = new AtomicLong();
    private static final AtomicLong rebases = new AtomicLong();
    private static final AtomicLong deltas = new AtomicLong();
    private static final AtomicLong mismatches = new AtomicLong();

    private LightSnapshotTelemetry() {}

    public static void fastSnapshot() { fastSnapshots.incrementAndGet(); }
    public static void verifiedSnapshot() { verifiedSnapshots.incrementAndGet(); }
    public static void rebase() { rebases.incrementAndGet(); }
    public static void delta() { deltas.incrementAndGet(); }
    public static void mismatch() { mismatches.incrementAndGet(); }

    public static long fastSnapshotCount() { return fastSnapshots.get(); }
    public static long verifiedSnapshotCount() { return verifiedSnapshots.get(); }
    public static long rebaseCount() { return rebases.get(); }
    public static long deltaCount() { return deltas.get(); }
    public static long mismatchCount() { return mismatches.get(); }
}
