package com.metallum.client.metal.render.mtl;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Reusable render-thread packet that collapses changed encoder state into one
 * Java-to-native call. The 64-byte record is an explicitly versioned internal
 * ABI shared with {@code MetallumNative.swift}.
 */
final class MetalRenderStatePacket {
    static final int ABI_VERSION = 1;
    static final long RECORD_SIZE = 64L;

    static final int PIPELINE = 1;
    static final int DEPTH_STENCIL = 2;
    static final int DEPTH_BIAS = 3;
    static final int WINDING = 4;
    static final int CULL_MODE = 5;
    static final int FILL_MODE = 6;
    static final int BUFFER = 7;
    static final int BUFFER_OFFSET = 8;
    static final int TEXTURE = 9;
    static final int TEXTURE_AND_SAMPLER = 10;
    static final int SCISSOR = 11;

    private static final long KIND_OFFSET = 0L;
    private static final long STAGE_MASK_OFFSET = 4L;
    private static final long INDEX_OFFSET = 8L;
    private static final long OBJECT0_OFFSET = 16L;
    private static final long OBJECT1_OFFSET = 24L;
    private static final long VALUE0_OFFSET = 32L;
    private static final long VALUE1_OFFSET = 40L;
    private static final long VALUE2_OFFSET = 48L;
    private static final long VALUE3_OFFSET = 56L;

    private final Arena arena = Arena.ofConfined();
    private final int capacity;
    private final MemorySegment records;
    private MemorySegment owner = MemorySegment.NULL;
    private int count;

    MetalRenderStatePacket() {
        this(resolveCapacity());
    }

    MetalRenderStatePacket(final int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.records = arena.allocate(Math.multiplyExact(RECORD_SIZE, capacity), 8L);
    }

    private static int resolveCapacity() {
        int configured = Integer.getInteger("metallum.opt.renderStatePacketCapacity", 128);
        return Math.max(16, Math.min(configured, 1024));
    }

    boolean hasOwner(final MemorySegment encoder) {
        return count > 0 && owner.address() == encoder.address();
    }

    boolean hasDifferentOwner(final MemorySegment encoder) {
        return count > 0 && owner.address() != encoder.address();
    }

    void begin(final MemorySegment encoder) {
        if (count != 0 && owner.address() != encoder.address()) {
            throw new IllegalStateException("render state packet switched encoder before flush");
        }
        owner = encoder;
    }

    MemorySegment owner() {
        return owner;
    }

    MemorySegment records() {
        return records;
    }

    int count() {
        return count;
    }

    boolean full() {
        return count == capacity;
    }

    void reset() {
        count = 0;
        owner = MemorySegment.NULL;
    }

    void pipeline(final MemorySegment pipeline) {
        append(PIPELINE, 0, 0L, pipeline, MemorySegment.NULL, 0L, 0L, 0L, 0L);
    }

    void depthStencil(final MemorySegment state) {
        append(DEPTH_STENCIL, 0, 0L, state, MemorySegment.NULL, 0L, 0L, 0L, 0L);
    }

    void depthBias(final float depthBias, final float slopeScale, final float clamp) {
        append(
                DEPTH_BIAS,
                0,
                0L,
                MemorySegment.NULL,
                MemorySegment.NULL,
                Integer.toUnsignedLong(Float.floatToRawIntBits(depthBias)),
                Integer.toUnsignedLong(Float.floatToRawIntBits(slopeScale)),
                Integer.toUnsignedLong(Float.floatToRawIntBits(clamp)),
                0L
        );
    }

    void winding(final int winding) {
        append(WINDING, 0, 0L, MemorySegment.NULL, MemorySegment.NULL, winding, 0L, 0L, 0L);
    }

    void cullMode(final long cullMode) {
        append(CULL_MODE, 0, 0L, MemorySegment.NULL, MemorySegment.NULL, cullMode, 0L, 0L, 0L);
    }

    void fillMode(final int fillMode) {
        append(FILL_MODE, 0, 0L, MemorySegment.NULL, MemorySegment.NULL, fillMode, 0L, 0L, 0L);
    }

    void buffer(
            final MemorySegment buffer,
            final long offset,
            final long index,
            final int stageMask
    ) {
        append(BUFFER, stageMask, index, buffer, MemorySegment.NULL, offset, 0L, 0L, 0L);
    }

    void bufferOffset(final long offset, final long index, final int stageMask) {
        append(BUFFER_OFFSET, stageMask, index, MemorySegment.NULL, MemorySegment.NULL, offset, 0L, 0L, 0L);
    }

    void texture(final MemorySegment texture, final long index, final int stageMask) {
        append(TEXTURE, stageMask, index, texture, MemorySegment.NULL, 0L, 0L, 0L, 0L);
    }

    void textureAndSampler(
            final MemorySegment texture,
            final MemorySegment sampler,
            final long index,
            final int stageMask
    ) {
        append(TEXTURE_AND_SAMPLER, stageMask, index, texture, sampler, 0L, 0L, 0L, 0L);
    }

    void scissor(final long x, final long y, final long width, final long height) {
        append(SCISSOR, 0, 0L, MemorySegment.NULL, MemorySegment.NULL, x, y, width, height);
    }

    private void append(
            final int kind,
            final int stageMask,
            final long index,
            final MemorySegment object0,
            final MemorySegment object1,
            final long value0,
            final long value1,
            final long value2,
            final long value3
    ) {
        if (full()) {
            throw new IllegalStateException("render state packet is full");
        }
        long base = (long) count * RECORD_SIZE;
        records.set(ValueLayout.JAVA_INT, base + KIND_OFFSET, kind);
        records.set(ValueLayout.JAVA_INT, base + STAGE_MASK_OFFSET, stageMask);
        records.set(ValueLayout.JAVA_LONG, base + INDEX_OFFSET, index);
        records.set(ValueLayout.ADDRESS, base + OBJECT0_OFFSET, normalize(object0));
        records.set(ValueLayout.ADDRESS, base + OBJECT1_OFFSET, normalize(object1));
        records.set(ValueLayout.JAVA_LONG, base + VALUE0_OFFSET, value0);
        records.set(ValueLayout.JAVA_LONG, base + VALUE1_OFFSET, value1);
        records.set(ValueLayout.JAVA_LONG, base + VALUE2_OFFSET, value2);
        records.set(ValueLayout.JAVA_LONG, base + VALUE3_OFFSET, value3);
        count++;
    }

    private static MemorySegment normalize(final MemorySegment segment) {
        return segment == null ? MemorySegment.NULL : segment;
    }
}
