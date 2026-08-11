package com.metallum.client.metal.render.mtl;

import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

/** Public narrow facade used by the render encoder mixin. */
public final class MetalRenderCommandPacketFacade implements AutoCloseable {
    private final MetalRenderCommandPacket packet;

    private MetalRenderCommandPacketFacade(final MetalRenderCommandPacket packet) {
        this.packet = packet;
    }

    public static @Nullable MetalRenderCommandPacketFacade createIfAvailable() {
        MetalRenderCommandPacket packet = MetalRenderCommandPacket.createIfAvailable();
        return packet == null ? null : new MetalRenderCommandPacketFacade(packet);
    }

    public boolean pipeline(final MemorySegment encoder, final MemorySegment value) {
        return packet.appendPipeline(encoder, value);
    }

    public boolean depthStencil(final MemorySegment encoder, final MemorySegment value) {
        return packet.appendDepthStencil(encoder, value);
    }

    public boolean depthBias(
            final MemorySegment encoder,
            final float bias,
            final float slope,
            final float clamp
    ) {
        return packet.appendDepthBias(encoder, bias, slope, clamp);
    }

    public boolean winding(final MemorySegment encoder, final long value) {
        return packet.appendWinding(encoder, value);
    }

    public boolean cullMode(final MemorySegment encoder, final long value) {
        return packet.appendCullMode(encoder, value);
    }

    public boolean fillMode(final MemorySegment encoder, final long value) {
        return packet.appendFillMode(encoder, value);
    }

    public boolean buffer(
            final MemorySegment encoder,
            final MemorySegment buffer,
            final long offset,
            final long index,
            final int stageMask
    ) {
        return packet.appendBuffer(encoder, buffer, offset, index, stageMask);
    }

    public boolean bufferOffset(
            final MemorySegment encoder,
            final long offset,
            final long index,
            final int stageMask
    ) {
        return packet.appendBufferOffset(encoder, offset, index, stageMask);
    }

    public boolean texture(
            final MemorySegment encoder,
            final MemorySegment texture,
            final long index,
            final int stageMask
    ) {
        return packet.appendTexture(encoder, texture, index, stageMask);
    }

    public boolean textureAndSampler(
            final MemorySegment encoder,
            final MemorySegment texture,
            final MemorySegment sampler,
            final long index,
            final int stageMask
    ) {
        return packet.appendTextureAndSampler(encoder, texture, sampler, index, stageMask);
    }

    public boolean scissor(
            final MemorySegment encoder,
            final long x,
            final long y,
            final long width,
            final long height
    ) {
        return packet.appendScissor(encoder, x, y, width, height);
    }

    public boolean drawPrimitives(
            final MemorySegment encoder,
            final long primitiveType,
            final int firstVertex,
            final int vertexCount,
            final int instanceCount,
            final int baseInstance
    ) {
        return packet.appendDrawPrimitives(
                encoder, primitiveType, firstVertex, vertexCount, instanceCount, baseInstance
        );
    }

    public boolean drawIndexed(
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
        return packet.appendDrawIndexed(
                encoder,
                primitiveType,
                indexCount,
                indexType,
                indexBuffer,
                offset,
                instanceCount,
                baseVertex,
                baseInstance
        );
    }

    public boolean drawPrimitivesIndirect(
            final MemorySegment encoder,
            final long primitiveType,
            final MemorySegment indirectBuffer,
            final long indirectOffset,
            final int drawCount,
            final long stride
    ) {
        return packet.appendDrawPrimitivesIndirect(
                encoder, primitiveType, indirectBuffer, indirectOffset, drawCount, stride
        );
    }

    public boolean drawIndexedIndirect(
            final MemorySegment encoder,
            final long primitiveType,
            final long indexType,
            final MemorySegment indexBuffer,
            final MemorySegment indirectBuffer,
            final long indirectOffset,
            final int drawCount,
            final long stride
    ) {
        return packet.appendDrawIndexedIndirect(
                encoder,
                primitiveType,
                indexType,
                indexBuffer,
                indirectBuffer,
                indirectOffset,
                drawCount,
                stride
        );
    }

    public void flush(final MemorySegment encoder) {
        packet.flush(encoder);
    }

    @Override
    public void close() {
        packet.close();
    }
}
