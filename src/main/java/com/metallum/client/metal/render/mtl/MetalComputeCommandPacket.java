package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.bridge.MetalComputeCommandPacketBridge;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** Ordered compute state and dispatch stream for one compute encoder. */
final class MetalComputeCommandPacket implements AutoCloseable {
    static final int MAGIC = 0x4D434350; // MCCP
    static final int VERSION = 1;
    static final int HEADER_SIZE = 24;
    static final int ENTRY_SIZE = 64;

    static final int OP_PIPELINE = 1;
    static final int OP_BUFFER = 2;
    static final int OP_TEXTURE = 3;
    static final int OP_SAMPLER = 4;
    static final int OP_DISPATCH = 32;
    static final int OP_DISPATCH_INDIRECT = 33;

    private static final boolean ENABLED = Boolean.getBoolean(
            "metallum.opt.computeCommandPacket"
    );
    private static final int CAPACITY = Math.clamp(
            Integer.getInteger("metallum.opt.computeCommandPacketEntries", 256),
            16,
            2048
    );
    private static final int MIN_NATIVE_OPERATIONS = Math.clamp(
            Integer.getInteger("metallum.opt.computeCommandPacketMinOperations", 2),
            1,
            16
    );

    private final Arena arena;
    private final MemorySegment storage;
    private int operationCount;
    private boolean active = true;
    private boolean closed;

    static @Nullable MetalComputeCommandPacket createIfAvailable() {
        if (!ENABLED || !MetalComputeCommandPacketBridge.available()) {
            return null;
        }
        return new MetalComputeCommandPacket(CAPACITY);
    }

    MetalComputeCommandPacket(final int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Compute command packet capacity must be positive");
        }
        this.arena = Arena.ofConfined();
        this.storage = this.arena.allocate(
                Math.addExact(HEADER_SIZE, Math.multiplyExact(capacity, ENTRY_SIZE)),
                Long.BYTES
        );
        this.storage.set(ValueLayout.JAVA_INT, 0L, MAGIC);
        this.storage.set(ValueLayout.JAVA_INT, 4L, VERSION);
        this.storage.set(ValueLayout.JAVA_INT, 16L, ENTRY_SIZE);
        this.storage.set(ValueLayout.JAVA_INT, 20L, 0);
        resetHeader();
    }

    boolean appendPipeline(final MemorySegment encoder, final MemorySegment pipeline) {
        return append(encoder, OP_PIPELINE, address(pipeline), 0L, 0L, 0L, 0L, 0L, 0L);
    }

    boolean appendBuffer(
            final MemorySegment encoder,
            final MemorySegment buffer,
            final long offset,
            final int index
    ) {
        return append(
                encoder,
                OP_BUFFER,
                address(buffer),
                offset,
                index,
                0L,
                0L,
                0L,
                0L
        );
    }

    boolean appendTexture(
            final MemorySegment encoder,
            final MemorySegment texture,
            final int index
    ) {
        return append(encoder, OP_TEXTURE, address(texture), index, 0L, 0L, 0L, 0L, 0L);
    }

    boolean appendSampler(
            final MemorySegment encoder,
            final MemorySegment sampler,
            final int index
    ) {
        return append(encoder, OP_SAMPLER, address(sampler), index, 0L, 0L, 0L, 0L, 0L);
    }

    boolean appendDispatch(
            final MemorySegment encoder,
            final int groupsX,
            final int groupsY,
            final int groupsZ,
            final int threadsPerGroupX,
            final int threadsPerGroupY,
            final int threadsPerGroupZ
    ) {
        return append(
                encoder,
                OP_DISPATCH,
                groupsX,
                groupsY,
                groupsZ,
                threadsPerGroupX,
                threadsPerGroupY,
                threadsPerGroupZ,
                0L
        );
    }

    boolean appendDispatchIndirect(
            final MemorySegment encoder,
            final MemorySegment indirectBuffer,
            final long indirectOffset,
            final int threadsPerGroupX,
            final int threadsPerGroupY,
            final int threadsPerGroupZ
    ) {
        return append(
                encoder,
                OP_DISPATCH_INDIRECT,
                address(indirectBuffer),
                indirectOffset,
                threadsPerGroupX,
                threadsPerGroupY,
                threadsPerGroupZ,
                0L,
                0L
        );
    }

    void flush(final MemorySegment encoder) {
        ensureOpen();
        if (this.operationCount == 0) {
            return;
        }
        int byteCount = byteCount();
        this.storage.set(ValueLayout.JAVA_INT, 8L, byteCount);
        this.storage.set(ValueLayout.JAVA_INT, 12L, this.operationCount);

        if (!this.active || this.operationCount < MIN_NATIVE_OPERATIONS) {
            replayLegacy(encoder);
            reset();
            return;
        }

        int applied = MetalComputeCommandPacketBridge.apply(encoder, this.storage, byteCount);
        if (applied == this.operationCount) {
            MetalCommandPacketTelemetry.computePacket(this.operationCount);
            reset();
            return;
        }
        if (applied < 0) {
            replayLegacy(encoder);
            this.active = false;
            reset();
            return;
        }
        throw new IllegalStateException(
                "Compute command packet applied " + applied + " of " + this.operationCount
                        + " operations; refusing unsafe dispatch replay"
        );
    }

    int operationCount() {
        return this.operationCount;
    }

    boolean active() {
        return this.active;
    }

    MemorySegment storageForTest() {
        return this.storage;
    }

    private boolean append(
            final MemorySegment encoder,
            final int opcode,
            final long a,
            final long b,
            final long c,
            final long d,
            final long e,
            final long f,
            final long g
    ) {
        ensureOpen();
        if (!this.active) {
            return false;
        }
        if (this.operationCount >= capacity()) {
            flush(encoder);
            if (!this.active) {
                return false;
            }
        }
        long base = HEADER_SIZE + (long) this.operationCount * ENTRY_SIZE;
        this.storage.set(ValueLayout.JAVA_INT, base, opcode);
        this.storage.set(ValueLayout.JAVA_INT, base + 4L, 0);
        this.storage.set(ValueLayout.JAVA_LONG, base + 8L, a);
        this.storage.set(ValueLayout.JAVA_LONG, base + 16L, b);
        this.storage.set(ValueLayout.JAVA_LONG, base + 24L, c);
        this.storage.set(ValueLayout.JAVA_LONG, base + 32L, d);
        this.storage.set(ValueLayout.JAVA_LONG, base + 40L, e);
        this.storage.set(ValueLayout.JAVA_LONG, base + 48L, f);
        this.storage.set(ValueLayout.JAVA_LONG, base + 56L, g);
        this.operationCount++;
        return true;
    }

    private void replayLegacy(final MemorySegment encoder) {
        for (int operation = 0; operation < this.operationCount; operation++) {
            long base = HEADER_SIZE + (long) operation * ENTRY_SIZE;
            int opcode = this.storage.get(ValueLayout.JAVA_INT, base);
            long a = this.storage.get(ValueLayout.JAVA_LONG, base + 8L);
            long b = this.storage.get(ValueLayout.JAVA_LONG, base + 16L);
            long c = this.storage.get(ValueLayout.JAVA_LONG, base + 24L);
            long d = this.storage.get(ValueLayout.JAVA_LONG, base + 32L);
            long e = this.storage.get(ValueLayout.JAVA_LONG, base + 40L);
            long f = this.storage.get(ValueLayout.JAVA_LONG, base + 48L);
            switch (opcode) {
                case OP_PIPELINE -> MetalNativeBridge.MTLComputeCommandEncoder_setComputePipelineState(
                        encoder, segment(a)
                );
                case OP_BUFFER -> MetalNativeBridge.MTLComputeCommandEncoder_setBuffer(
                        encoder, segment(a), b, (int) c
                );
                case OP_TEXTURE -> MetalNativeBridge.MTLComputeCommandEncoder_setTexture(
                        encoder, segment(a), (int) b
                );
                case OP_SAMPLER -> MetalNativeBridge.MTLComputeCommandEncoder_setSamplerState(
                        encoder, segment(a), (int) b
                );
                case OP_DISPATCH -> MetalNativeBridge.MTLComputeCommandEncoder_dispatchThreadgroups(
                        encoder,
                        (int) a,
                        (int) b,
                        (int) c,
                        (int) d,
                        (int) e,
                        (int) f
                );
                case OP_DISPATCH_INDIRECT -> MetalNativeBridge.MTLComputeCommandEncoder_dispatchThreadgroupsIndirect(
                        encoder,
                        segment(a),
                        b,
                        (int) c,
                        (int) d,
                        (int) e
                );
                default -> throw new IllegalStateException(
                        "Unknown compute command packet opcode " + opcode
                );
            }
        }
        MetalCommandPacketTelemetry.computeReplay();
    }

    private int capacity() {
        return Math.toIntExact((this.storage.byteSize() - HEADER_SIZE) / ENTRY_SIZE);
    }

    private int byteCount() {
        return Math.addExact(HEADER_SIZE, Math.multiplyExact(this.operationCount, ENTRY_SIZE));
    }

    private void reset() {
        this.operationCount = 0;
        resetHeader();
    }

    private void resetHeader() {
        this.storage.set(ValueLayout.JAVA_INT, 8L, HEADER_SIZE);
        this.storage.set(ValueLayout.JAVA_INT, 12L, 0);
    }

    private static long address(final @Nullable MemorySegment segment) {
        return segment == null ? 0L : segment.address();
    }

    private static MemorySegment segment(final long address) {
        return address == 0L ? MemorySegment.NULL : MemorySegment.ofAddress(address);
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Compute command packet is closed");
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.arena.close();
    }
}
