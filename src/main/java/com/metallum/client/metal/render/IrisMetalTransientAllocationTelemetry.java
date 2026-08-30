package com.metallum.client.metal.render;

import java.util.concurrent.atomic.LongAdder;

/**
 * Runtime counters for the optional transient allocation lanes. A planner
 * receipt proves eligibility; these counters prove whether a native allocator
 * actually consumed that receipt during the current validation window.
 */
public final class IrisMetalTransientAllocationTelemetry {
    private static final LongAdder MEMORYLESS_REQUESTS = new LongAdder();
    private static final LongAdder MEMORYLESS_CREATED = new LongAdder();
    private static final LongAdder MEMORYLESS_REJECTIONS = new LongAdder();
    private static final LongAdder HEAP_REQUESTS = new LongAdder();
    private static final LongAdder HEAP_CREATED = new LongAdder();
    private static final LongAdder HEAP_REJECTIONS = new LongAdder();
    private static final LongAdder HEAP_ADOPTIONS = new LongAdder();

    private IrisMetalTransientAllocationTelemetry() {
    }

    static void memorylessRequested() {
        MEMORYLESS_REQUESTS.increment();
    }

    static void memorylessCreated() {
        MEMORYLESS_CREATED.increment();
    }

    static void memorylessRejected() {
        MEMORYLESS_REJECTIONS.increment();
    }

    static void heapRequested() {
        HEAP_REQUESTS.increment();
    }

    static void heapCreated() {
        HEAP_CREATED.increment();
    }

    static void heapRejected() {
        HEAP_REJECTIONS.increment();
    }

    static void heapAdopted() {
        HEAP_ADOPTIONS.increment();
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                MEMORYLESS_REQUESTS.sum(),
                MEMORYLESS_CREATED.sum(),
                MEMORYLESS_REJECTIONS.sum(),
                HEAP_REQUESTS.sum(),
                HEAP_CREATED.sum(),
                HEAP_REJECTIONS.sum(),
                HEAP_ADOPTIONS.sum()
        );
    }

    public static void reset() {
        MEMORYLESS_REQUESTS.reset();
        MEMORYLESS_CREATED.reset();
        MEMORYLESS_REJECTIONS.reset();
        HEAP_REQUESTS.reset();
        HEAP_CREATED.reset();
        HEAP_REJECTIONS.reset();
        HEAP_ADOPTIONS.reset();
    }

    public record Snapshot(
            long memorylessRequests,
            long memorylessCreated,
            long memorylessRejections,
            long heapRequests,
            long heapCreated,
            long heapRejections,
            long heapAdoptions
    ) {
        public boolean memorylessActivated() {
            return memorylessCreated > 0L;
        }

        public boolean heapActivated() {
            return heapAdoptions > 0L;
        }
    }
}
