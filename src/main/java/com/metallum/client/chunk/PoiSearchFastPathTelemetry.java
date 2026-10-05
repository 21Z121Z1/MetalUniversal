package com.metallum.client.chunk;

import java.util.concurrent.atomic.AtomicLong;

public final class PoiSearchFastPathTelemetry {
    private static final AtomicLong fastCalls = new AtomicLong();
    private static final AtomicLong verifiedCalls = new AtomicLong();
    private static final AtomicLong mismatches = new AtomicLong();
    private static final AtomicLong filterCalls = new AtomicLong();

    private PoiSearchFastPathTelemetry() {}

    static void fastCall() { fastCalls.incrementAndGet(); }
    static void verified(long calls) {
        verifiedCalls.incrementAndGet();
        filterCalls.addAndGet(calls);
    }
    static void mismatch() { mismatches.incrementAndGet(); }

    public static long fastCallCount() { return fastCalls.get(); }
    public static long verifiedCallCount() { return verifiedCalls.get(); }
    public static long mismatchCount() { return mismatches.get(); }
    public static long filterCallCount() { return filterCalls.get(); }
}
