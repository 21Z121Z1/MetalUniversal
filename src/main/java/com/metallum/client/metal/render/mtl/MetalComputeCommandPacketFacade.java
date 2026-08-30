package com.metallum.client.metal.render.mtl;

import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

/** Public narrow facade used by the compute encoder mixin. */
public final class MetalComputeCommandPacketFacade implements AutoCloseable {
    private final MetalComputeCommandPacket packet;

    private MetalComputeCommandPacketFacade(final MetalComputeCommandPacket packet) {
        this.packet = packet;
    }

    public static @Nullable MetalComputeCommandPacketFacade createIfAvailable() {
        MetalComputeCommandPacket packet = MetalComputeCommandPacket.createIfAvailable();
        return packet == null ? null : new MetalComputeCommandPacketFacade(packet);
    }

    public boolean pipeline(final MemorySegment encoder, final MemorySegment value) {
        return packet.appendPipeline(encoder, value);
    }

    public boolean buffer(
            final MemorySegment encoder,
            final MemorySegment buffer,
            final long offset,
            final int index
    ) {
        return packet.appendBuffer(encoder, buffer, offset, index);
    }

    public boolean texture(
            final MemorySegment encoder,
            final MemorySegment texture,
            final int index
    ) {
        return packet.appendTexture(encoder, texture, index);
    }

    public boolean sampler(
            final MemorySegment encoder,
            final MemorySegment sampler,
            final int index
    ) {
        return packet.appendSampler(encoder, sampler, index);
    }

    public boolean dispatch(
            final MemorySegment encoder,
            final int groupsX,
            final int groupsY,
            final int groupsZ,
            final int threadsPerGroupX,
            final int threadsPerGroupY,
            final int threadsPerGroupZ
    ) {
        return packet.appendDispatch(
                encoder,
                groupsX,
                groupsY,
                groupsZ,
                threadsPerGroupX,
                threadsPerGroupY,
                threadsPerGroupZ
        );
    }

    public boolean dispatchIndirect(
            final MemorySegment encoder,
            final MemorySegment indirectBuffer,
            final long indirectOffset,
            final int threadsPerGroupX,
            final int threadsPerGroupY,
            final int threadsPerGroupZ
    ) {
        return packet.appendDispatchIndirect(
                encoder,
                indirectBuffer,
                indirectOffset,
                threadsPerGroupX,
                threadsPerGroupY,
                threadsPerGroupZ
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
