package com.metallum.client.metal.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Structured render-graph telemetry for the Iris TBDR compiler work.
 *
 * Counts are cheap monotonic longs updated on the render thread; they exist so
 * baseline/candidate comparisons have an authoritative structured source
 * (rendergraph.json) instead of log regex. Three identities matter:
 *
 * - passes requested: how many semantic render passes the Java side asked for;
 * - native encoders created: how many MTLRenderCommandEncoders actually had
 *   to begin (encoder reuse means fusion happened);
 * - estimated attachment store/load bytes: bandwidth implied by the CURRENT
 *   ABI semantics (V2 stores every live color attachment every pass). This is
 *   the number the load/store planner must drive down without changing any
 *   observable pixel.
 */
public final class RenderGraphTelemetry {
    private static final AtomicLong PASSES_REQUESTED = new AtomicLong();
    private static final AtomicLong ENCODERS_CREATED = new AtomicLong();
    private static final AtomicLong ENCODERS_REUSED = new AtomicLong();
    private static final AtomicLong COLOR_STORE_BYTES = new AtomicLong();
    private static final AtomicLong COLOR_LOAD_BYTES = new AtomicLong();
    private static final AtomicLong DEPTH_STORE_BYTES = new AtomicLong();
    private static final List<Map<String, Object>> EVENTS = new ArrayList<>();
    private static final int MAX_EVENTS = 4096;

    private RenderGraphTelemetry() {
    }

    public static void reset() {
        PASSES_REQUESTED.set(0);
        ENCODERS_CREATED.set(0);
        ENCODERS_REUSED.set(0);
        COLOR_STORE_BYTES.set(0);
        COLOR_LOAD_BYTES.set(0);
        DEPTH_STORE_BYTES.set(0);
        synchronized (EVENTS) {
            EVENTS.clear();
        }
    }

    /** One semantic render pass reached the encoder boundary. */
    public static void onPassRequested(final String label) {
        PASSES_REQUESTED.incrementAndGet();
        record(Map.of("event", "pass-requested", "label", label == null ? "" : label));
    }

    /**
     * A new native render encoder began. {@code slotPixelBytes} carries the
     * per-slot bytes-per-pixel (0 for absent slots); clear flags follow the
     * current ABI so byte estimates reflect what today's semantics imply.
     */
    public static void onEncoderCreated(
            final String label,
            final int width,
            final int height,
            final int[] slotPixelBytes,
            final boolean[] slotClear,
            final boolean depthAttached,
            final int depthPixelBytes,
            final boolean depthClear
    ) {
        ENCODERS_CREATED.incrementAndGet();
        long pixels = (long) width * height;
        long store = 0;
        long load = 0;
        for (int index = 0; index < slotPixelBytes.length; index++) {
            int bytes = slotPixelBytes[index];
            if (bytes <= 0) {
                continue;
            }
            // V2 semantics: every live color attachment is stored every pass,
            // and loaded unless this pass cleared it.
            store += pixels * bytes;
            if (!slotClear[index]) {
                load += pixels * bytes;
            }
        }
        if (depthAttached && depthPixelBytes > 0 && !depthClear) {
            DEPTH_STORE_BYTES.addAndGet(pixels * depthPixelBytes);
        }
        COLOR_STORE_BYTES.addAndGet(store);
        COLOR_LOAD_BYTES.addAndGet(load);
        record(Map.of(
                "event", "encoder-created",
                "label", label == null ? "" : label,
                "width", width,
                "height", height,
                "storeBytesEstimate", store,
                "loadBytesEstimate", load
        ));
    }

    /** An incoming pass reused the already-open encoder (fusion candidate path). */
    public static void onEncoderReused(final String label) {
        ENCODERS_REUSED.incrementAndGet();
        record(Map.of("event", "encoder-reused", "label", label == null ? "" : label));
    }

    private static void record(final Map<String, Object> event) {
        synchronized (EVENTS) {
            if (EVENTS.size() < MAX_EVENTS) {
                EVENTS.add(new LinkedHashMap<>(event));
            }
        }
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("passesRequested", PASSES_REQUESTED.get());
        snapshot.put("nativeEncodersCreated", ENCODERS_CREATED.get());
        snapshot.put("encodersReused", ENCODERS_REUSED.get());
        snapshot.put("colorStoreBytesEstimate", COLOR_STORE_BYTES.get());
        snapshot.put("colorLoadBytesEstimate", COLOR_LOAD_BYTES.get());
        snapshot.put("depthStoreBytesEstimate", DEPTH_STORE_BYTES.get());
        synchronized (EVENTS) {
            snapshot.put("events", new ArrayList<>(EVENTS));
        }
        return snapshot;
    }
}
