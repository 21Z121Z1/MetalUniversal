package com.metallum.client.chunk;

import java.util.concurrent.atomic.AtomicLong;

public final class ChunkPaletteFastPathTelemetry {
    private static final AtomicLong decodeFast = new AtomicLong();
    private static final AtomicLong decodeFallback = new AtomicLong();
    private static final AtomicLong decodeVerified = new AtomicLong();
    private static final AtomicLong encodeFast = new AtomicLong();
    private static final AtomicLong encodeFallback = new AtomicLong();
    private static final AtomicLong encodeVerified = new AtomicLong();
    private static final AtomicLong mismatches = new AtomicLong();

    private ChunkPaletteFastPathTelemetry() {}

    static void decodeFast() { decodeFast.incrementAndGet(); }
    static void decodeFallback() { decodeFallback.incrementAndGet(); }
    static void decodeVerified() { decodeVerified.incrementAndGet(); }
    static void encodeFast() { encodeFast.incrementAndGet(); }
    static void encodeFallback() { encodeFallback.incrementAndGet(); }
    static void encodeVerified() { encodeVerified.incrementAndGet(); }
    static void mismatch() { mismatches.incrementAndGet(); }

    public static long decodeFastCount() { return decodeFast.get(); }
    public static long decodeFallbackCount() { return decodeFallback.get(); }
    public static long decodeVerifiedCount() { return decodeVerified.get(); }
    public static long encodeFastCount() { return encodeFast.get(); }
    public static long encodeFallbackCount() { return encodeFallback.get(); }
    public static long encodeVerifiedCount() { return encodeVerified.get(); }
    public static long mismatchCount() { return mismatches.get(); }
}
