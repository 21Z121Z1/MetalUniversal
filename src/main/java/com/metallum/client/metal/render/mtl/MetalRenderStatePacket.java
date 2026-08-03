package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.bridge.MetalRenderStatePacketBridge;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Reusable off-heap render-state packet for one native render encoder.
 *
 * <p>Entries are appended only after the Java state shadow admits a real
 * change. A draw flushes the packet through one ordinary FFM call. The native
 * decoder validates the complete packet before applying it; a failure is
 * replayed through the legacy setters and disables packet use for this encoder.</p>
 */
final class MetalRenderStatePacket implements AutoCloseable {
    static final int MAGIC = 0x4D525350;
    static final int VERSION = 1;
    static final int HEADER_SIZE = 16;
    static final int ENTRY_SIZE = 48;

    static final int OP_PIPELINE = 1;
    static final int OP_DEPTH_STENCIL = 2;
    static final int OP_DEPTH_BIAS = 3;
    static final int OP_WINDING = 4;
    static final int OP_CULL_MODE = 5;
    static final int OP_FILL_MODE = 6;
    static final int OP_BUFFER = 7;
    static final int OP_BUFFER_OFFSET = 8;
    static final int OP_TEXTURE = 9;
    static final int OP_TEXTURE_AND_SAMPLER = 10;
    static final int OP_SCISSOR = 11;

    private static final boolean ENABLED = !"false".equalsIgnoreCase(
            System.getProperty("metallum.opt.renderStatePacket", "true")
    );
    private static final int CAPACITY = Math.clamp(
            Integer.getInteger("metallum.opt.renderStatePacketEntries", 256),
            16,
            2048
    );
    private static final int MIN_NATIVE_ENTRIES = Math.clamp(
            Integer.getInteger("metallum.opt.renderStatePacketMinEntries", 2),
            1,
            16
    );

    private final Arena arena;
    private final MemorySegment storage;
    private int entryCount;
    private boolean active = true;
    private boolean closed;

    static @Nullable MetalRenderStatePacket createIfAvailable() {
        if (!ENABLED || !MetalRenderStatePacketBridge.available()) {
            return null;
        }
        return new MetalRenderStatePacket(CAPACITY);
    }

    MetalRenderStatePacket(final int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Render-state packet capacity must be positive");
        }
        this.arena = Arena.ofConfined();
        this.storage = this.arena.allocate(
                Math.addExact(HEADER_SIZE, Math.multiplyExact(capacity, ENTRY_SIZE)),
                Long.BYTES
        );
        this.storage.set(ValueLayout.JAVA_INT, 0L, MAGIC);
        this.storage.set(ValueLayout.JAVA_INT, 4L, VERSION);
        resetHeader();
    }

    boolean appendPipeline(final MemorySegment encoder, final MemorySegment pipeline) {
        return append(encoder, OP_PIPELINE, 0, 0L, address(pipeline), 0L, 0L, 0L);
    }

    boolean appendDepthStencil(final MemorySegment encoder, final MemorySegment state) {
        return append(encoder, OP_DEPTH_STENCIL, 0, 0L, address(state), 0L, 0L, 0L);
    }

    boolean appendDepthBias(
            final MemorySegment encoder,
            final float depthBias,
            final float slopeScale,
            final float clamp
    ) {
        return append(
                encoder,
                OP_DEPTH_BIAS,
                0,
                0L,
                Integer.toUnsignedLong(Float.floatToRawIntBits(depthBias)),
                Integer.toUnsignedLong(Float.floatToRawIntBits(slopeScale)),
                Integer.toUnsignedLong(Float.floatToRawIntBits(clamp)),
                0L
        );
    }

    boolean appendWinding(final MemorySegment encoder, final long winding) {
        return append(encoder, OP_WINDING, 0, 0L, winding, 0L, 0L, 0L);
    }

    boolean appendCullMode(final MemorySegment encoder, final long cullMode) {
        return append(encoder, OP_CULL_MODE, 0, 0L, cullMode, 0L, 0L, 0L);
    }

    boolean appendFillMode(final MemorySegment encoder, final long fillMode) {
        return append(encoder, OP_FILL_MODE, 0, 0L, fillMode, 0L, 0L, 0L);
    }

    boolean appendBuffer(
            final MemorySegment encoder,
            final MemorySegment buffer,
            final long offset,
            final long index,
            final int stageMask
    ) {
        return append(
                encoder,
                OP_BUFFER,
                stageMask,
                index,
                address(buffer),
                offset,
                0L,
                0L
        );
    }

    boolean appendBufferOffset(
            final MemorySegment encoder,
            final long offset,
            final long index,
            final int stageMask
    ) {
        return append(encoder, OP_BUFFER_OFFSET, stageMask, index, offset, 0L, 0L, 0L);
    }

    boolean appendTexture(
            final MemorySegment encoder,
            final MemorySegment texture,
            final long index,
            final int stageMask
    ) {
        return append(
                encoder,
                OP_TEXTURE,
                stageMask,
                index,
                address(texture),
                0L,
                0L,
                0L
        );
    }

    boolean appendTextureAndSampler(
            final MemorySegment encoder,
            final MemorySegment texture,
            final MemorySegment sampler,
            final long index,
            final int stageMask
    ) {
        return append(
                encoder,
                OP_TEXTURE_AND_SAMPLER,
                stageMask,
                index,
                address(texture),
                address(sampler),
                0L,
                0L
        );
    }

    boolean appendScissor(
            final MemorySegment encoder,
            final long x,
            final long y,
            final long width,
            final long height
    ) {
        return append(encoder, OP_SCISSOR, 0, x, y, width, height, 0L);
    }

    void flush(final MemorySegment encoder) {
        ensureOpen();
        if (this.entryCount == 0) {
            return;
        }
        int byteCount = byteCount();
        this.storage.set(ValueLayout.JAVA_INT, 8L, byteCount);
        this.storage.set(ValueLayout.JAVA_INT, 12L, this.entryCount);

        if (!this.active || this.entryCount < MIN_NATIVE_ENTRIES) {
            if (this.entryCount == 1) {
                MetalRenderStatePacketTelemetry.recordSingleEntryBypass();
            }
            replayLegacy(encoder);
            reset();
            return;
        }

        int applied = MetalRenderStatePacketBridge.apply(encoder, this.storage, byteCount);
        if (applied == this.entryCount) {
            MetalRenderStatePacketTelemetry.recordPacket(this.entryCount);
        } else {
            // State setters are idempotent. Even if a future decoder reports a
            // partial positive count, replaying the complete packet restores
            // the exact final encoder state.
            replayLegacy(encoder);
            this.active = false;
        }
        reset();
    }

    int entryCount() {
        return this.entryCount;
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
            final int stageMask,
            final long index,
            final long a,
            final long b,
            final long c,
            final long d
    ) {
        ensureOpen();
        if (!this.active) {
            return false;
        }
        if (this.entryCount >= capacity()) {
            MetalRenderStatePacketTelemetry.recordCapacityFlush();
            flush(encoder);
            if (!this.active) {
                return false;
            }
        }
        long base = HEADER_SIZE + (long) this.entryCount * ENTRY_SIZE;
        this.storage.set(ValueLayout.JAVA_INT, base, opcode);
        this.storage.set(ValueLayout.JAVA_INT, base + 4L, stageMask);
        this.storage.set(ValueLayout.JAVA_LONG, base + 8L, index);
        this.storage.set(ValueLayout.JAVA_LONG, base + 16L, a);
        this.storage.set(ValueLayout.JAVA_LONG, base + 24L, b);
        this.storage.set(ValueLayout.JAVA_LONG, base + 32L, c);
        this.storage.set(ValueLayout.JAVA_LONG, base + 40L, d);
        this.entryCount++;
        return true;
    }

    private void replayLegacy(final MemorySegment encoder) {
        for (int entry = 0; entry < this.entryCount; entry++) {
            long base = HEADER_SIZE + (long) entry * ENTRY_SIZE;
            int opcode = this.storage.get(ValueLayout.JAVA_INT, base);
            int stageMask = this.storage.get(ValueLayout.JAVA_INT, base + 4L);
            long index = this.storage.get(ValueLayout.JAVA_LONG, base + 8L);
            long a = this.storage.get(ValueLayout.JAVA_LONG, base + 16L);
            long b = this.storage.get(ValueLayout.JAVA_LONG, base + 24L);
            long c = this.storage.get(ValueLayout.JAVA_LONG, base + 32L);
            switch (opcode) {
                case OP_PIPELINE -> MetalNativeBridge.MTLRenderCommandEncoder_setRenderPipelineState(
                        encoder,
                        segment(a)
                );
                case OP_DEPTH_STENCIL -> MetalNativeBridge.MTLRenderCommandEncoder_setDepthStencilState(
                        encoder,
                        segment(a)
                );
                case OP_DEPTH_BIAS -> MetalNativeBridge.MTLRenderCommandEncoder_setDepthBias(
                        encoder,
                        Float.intBitsToFloat((int) a),
                        Float.intBitsToFloat((int) b),
                        Float.intBitsToFloat((int) c)
                );
                case OP_WINDING -> MetalNativeBridge.MTLRenderCommandEncoder_setFrontFacingWinding(
                        encoder,
                        a
                );
                case OP_CULL_MODE -> MetalNativeBridge.MTLRenderCommandEncoder_setCullMode(
                        encoder,
                        a
                );
                case OP_FILL_MODE -> MetalNativeBridge.MTLRenderCommandEncoder_setTriangleFillMode(
                        encoder,
                        a
                );
                case OP_BUFFER -> MetalNativeBridge.MTLRenderCommandEncoder_setBuffer(
                        encoder,
                        segment(a),
                        b,
                        index,
                        stageMask
                );
                case OP_BUFFER_OFFSET -> MetalNativeBridge.MTLRenderCommandEncoder_setBufferOffset(
                        encoder,
                        a,
                        index,
                        stageMask
                );
                case OP_TEXTURE -> MetalNativeBridge.MTLRenderCommandEncoder_setTexture(
                        encoder,
                        segment(a),
                        index,
                        stageMask
                );
                case OP_TEXTURE_AND_SAMPLER -> MetalNativeBridge.MTLRenderCommandEncoder_setTextureAndSampler(
                        encoder,
                        segment(a),
                        segment(b),
                        index,
                        stageMask
                );
                case OP_SCISSOR -> MetalNativeBridge.MTLRenderCommandEncoder_setScissorRect(
                        encoder,
                        index,
                        a,
                        b,
                        c
                );
                default -> throw new IllegalStateException(
                        "Unknown render-state packet opcode " + opcode
                );
            }
        }
        MetalRenderStatePacketTelemetry.recordLegacyReplay(this.entryCount);
    }

    private int capacity() {
        return Math.toIntExact((this.storage.byteSize() - HEADER_SIZE) / ENTRY_SIZE);
    }

    private int byteCount() {
        return Math.addExact(HEADER_SIZE, Math.multiplyExact(this.entryCount, ENTRY_SIZE));
    }

    private void reset() {
        this.entryCount = 0;
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
            throw new IllegalStateException("Render-state packet is closed");
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
