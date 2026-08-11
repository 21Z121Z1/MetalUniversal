package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.bridge.MetalRenderCommandPacketBridge;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Ordered render command stream for one native render encoder.
 *
 * <p>Unlike {@link MetalRenderStatePacket}, this packet contains state and draw
 * operations in their original order. The native decoder validates the entire
 * packet before executing the first operation. Therefore a negative native
 * result means legacy replay is safe; an FFM invocation failure is fail-stop
 * because replaying after an unknown boundary could duplicate draws.</p>
 */
final class MetalRenderCommandPacket implements AutoCloseable {
    static final int MAGIC = 0x4D524350; // MRCP
    static final int VERSION = 1;
    static final int HEADER_SIZE = 24;
    static final int ENTRY_SIZE = 64;

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

    static final int OP_DRAW_PRIMITIVES = 32;
    static final int OP_DRAW_INDEXED = 33;
    static final int OP_DRAW_PRIMITIVES_INDIRECT = 34;
    static final int OP_DRAW_INDEXED_INDIRECT = 35;

    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty(
            "metallum.opt.renderCommandPacket", "true"
    ));
    private static final int CAPACITY = Math.clamp(
            Integer.getInteger("metallum.opt.renderCommandPacketEntries", 512),
            32,
            4096
    );
    private static final int MIN_NATIVE_OPERATIONS = Math.clamp(
            Integer.getInteger("metallum.opt.renderCommandPacketMinOperations", 2),
            1,
            16
    );

    private final Arena arena;
    private final MemorySegment storage;
    private int operationCount;
    private boolean active = true;
    private boolean closed;

    static @Nullable MetalRenderCommandPacket createIfAvailable() {
        if (!ENABLED || !MetalRenderCommandPacketBridge.available()) {
            return null;
        }
        return new MetalRenderCommandPacket(CAPACITY);
    }

    MetalRenderCommandPacket(final int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Render command packet capacity must be positive");
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
        return append(encoder, OP_PIPELINE, 0, address(pipeline), 0L, 0L, 0L, 0L, 0L, 0L);
    }

    boolean appendDepthStencil(final MemorySegment encoder, final MemorySegment state) {
        return append(encoder, OP_DEPTH_STENCIL, 0, address(state), 0L, 0L, 0L, 0L, 0L, 0L);
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
                Integer.toUnsignedLong(Float.floatToRawIntBits(depthBias)),
                Integer.toUnsignedLong(Float.floatToRawIntBits(slopeScale)),
                Integer.toUnsignedLong(Float.floatToRawIntBits(clamp)),
                0L,
                0L,
                0L,
                0L
        );
    }

    boolean appendWinding(final MemorySegment encoder, final long winding) {
        return append(encoder, OP_WINDING, 0, winding, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    boolean appendCullMode(final MemorySegment encoder, final long cullMode) {
        return append(encoder, OP_CULL_MODE, 0, cullMode, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    boolean appendFillMode(final MemorySegment encoder, final long fillMode) {
        return append(encoder, OP_FILL_MODE, 0, fillMode, 0L, 0L, 0L, 0L, 0L, 0L);
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
                address(buffer),
                offset,
                index,
                0L,
                0L,
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
        return append(
                encoder,
                OP_BUFFER_OFFSET,
                stageMask,
                offset,
                index,
                0L,
                0L,
                0L,
                0L,
                0L
        );
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
                address(texture),
                index,
                0L,
                0L,
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
                address(texture),
                address(sampler),
                index,
                0L,
                0L,
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
        return append(encoder, OP_SCISSOR, 0, x, y, width, height, 0L, 0L, 0L);
    }

    boolean appendDrawPrimitives(
            final MemorySegment encoder,
            final long primitiveType,
            final int firstVertex,
            final int vertexCount,
            final int instanceCount,
            final int baseInstance
    ) {
        return append(
                encoder,
                OP_DRAW_PRIMITIVES,
                0,
                primitiveType,
                firstVertex,
                vertexCount,
                instanceCount,
                baseInstance,
                0L,
                0L
        );
    }

    boolean appendDrawIndexed(
            final MemorySegment encoder,
            final long primitiveType,
            final int indexCount,
            final long indexType,
            final MemorySegment indexBuffer,
            final long offset,
            final int instanceCount,
            final int baseVertex,
            final int baseInstance
    ) {
        return append(
                encoder,
                OP_DRAW_INDEXED,
                0,
                primitiveType,
                indexCount,
                indexType,
                address(indexBuffer),
                offset,
                instanceCount,
                packInts(baseVertex, baseInstance)
        );
    }

    boolean appendDrawPrimitivesIndirect(
            final MemorySegment encoder,
            final long primitiveType,
            final MemorySegment indirectBuffer,
            final long indirectOffset,
            final int drawCount,
            final long stride
    ) {
        return append(
                encoder,
                OP_DRAW_PRIMITIVES_INDIRECT,
                0,
                primitiveType,
                address(indirectBuffer),
                indirectOffset,
                drawCount,
                stride,
                0L,
                0L
        );
    }

    boolean appendDrawIndexedIndirect(
            final MemorySegment encoder,
            final long primitiveType,
            final long indexType,
            final MemorySegment indexBuffer,
            final MemorySegment indirectBuffer,
            final long indirectOffset,
            final int drawCount,
            final long stride
    ) {
        return append(
                encoder,
                OP_DRAW_INDEXED_INDIRECT,
                0,
                primitiveType,
                indexType,
                address(indexBuffer),
                address(indirectBuffer),
                indirectOffset,
                drawCount,
                stride
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

        int applied = MetalRenderCommandPacketBridge.apply(encoder, this.storage, byteCount);
        if (applied == this.operationCount) {
            MetalCommandPacketTelemetry.renderPacket(this.operationCount);
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
                "Render command packet applied " + applied + " of " + this.operationCount
                        + " operations; refusing unsafe draw replay"
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
            final int flags,
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
        this.storage.set(ValueLayout.JAVA_INT, base + 4L, flags);
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
            int flags = this.storage.get(ValueLayout.JAVA_INT, base + 4L);
            long a = this.storage.get(ValueLayout.JAVA_LONG, base + 8L);
            long b = this.storage.get(ValueLayout.JAVA_LONG, base + 16L);
            long c = this.storage.get(ValueLayout.JAVA_LONG, base + 24L);
            long d = this.storage.get(ValueLayout.JAVA_LONG, base + 32L);
            long e = this.storage.get(ValueLayout.JAVA_LONG, base + 40L);
            long f = this.storage.get(ValueLayout.JAVA_LONG, base + 48L);
            long g = this.storage.get(ValueLayout.JAVA_LONG, base + 56L);
            switch (opcode) {
                case OP_PIPELINE -> MetalNativeBridge.MTLRenderCommandEncoder_setRenderPipelineState(
                        encoder, segment(a)
                );
                case OP_DEPTH_STENCIL -> MetalNativeBridge.MTLRenderCommandEncoder_setDepthStencilState(
                        encoder, segment(a)
                );
                case OP_DEPTH_BIAS -> MetalNativeBridge.MTLRenderCommandEncoder_setDepthBias(
                        encoder,
                        Float.intBitsToFloat((int) a),
                        Float.intBitsToFloat((int) b),
                        Float.intBitsToFloat((int) c)
                );
                case OP_WINDING -> MetalNativeBridge.MTLRenderCommandEncoder_setFrontFacingWinding(
                        encoder, (int) a
                );
                case OP_CULL_MODE -> MetalNativeBridge.MTLRenderCommandEncoder_setCullMode(
                        encoder, a
                );
                case OP_FILL_MODE -> MetalNativeBridge.MTLRenderCommandEncoder_setTriangleFillMode(
                        encoder, (int) a
                );
                case OP_BUFFER -> MetalNativeBridge.MTLRenderCommandEncoder_setBuffer(
                        encoder, segment(a), b, c, flags
                );
                case OP_BUFFER_OFFSET -> MetalNativeBridge.MTLRenderCommandEncoder_setBufferOffset(
                        encoder, a, b, flags
                );
                case OP_TEXTURE -> MetalNativeBridge.MTLRenderCommandEncoder_setTexture(
                        encoder, segment(a), b, flags
                );
                case OP_TEXTURE_AND_SAMPLER -> MetalNativeBridge.MTLRenderCommandEncoder_setTextureAndSampler(
                        encoder, segment(a), segment(b), c, flags
                );
                case OP_SCISSOR -> MetalNativeBridge.MTLRenderCommandEncoder_setScissorRect(
                        encoder, a, b, c, d
                );
                case OP_DRAW_PRIMITIVES -> MetalNativeBridge.MTLRenderCommandEncoder_drawPrimitives(
                        encoder, a, b, c, d, e
                );
                case OP_DRAW_INDEXED -> MetalNativeBridge.MTLRenderCommandEncoder_drawIndexedPrimitives(
                        encoder,
                        a,
                        b,
                        c,
                        segment(d),
                        e,
                        f,
                        unpackHigh(g),
                        unpackLow(g)
                );
                case OP_DRAW_PRIMITIVES_INDIRECT -> MetalNativeBridge.MTLRenderCommandEncoder_drawPrimitivesIndirect(
                        encoder, a, segment(b), c, d, e
                );
                case OP_DRAW_INDEXED_INDIRECT -> MetalNativeBridge.MTLRenderCommandEncoder_drawIndexedPrimitivesIndirect(
                        encoder, a, b, segment(c), segment(d), e, f, g
                );
                default -> throw new IllegalStateException(
                        "Unknown render command packet opcode " + opcode
                );
            }
        }
        MetalCommandPacketTelemetry.renderReplay();
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

    private static long packInts(final int high, final int low) {
        return ((long) high << 32) | Integer.toUnsignedLong(low);
    }

    private static int unpackHigh(final long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackLow(final long packed) {
        return (int) packed;
    }

    private static long address(final @Nullable MemorySegment segment) {
        return segment == null ? 0L : segment.address();
    }

    private static MemorySegment segment(final long address) {
        return address == 0L ? MemorySegment.NULL : MemorySegment.ofAddress(address);
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Render command packet is closed");
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
