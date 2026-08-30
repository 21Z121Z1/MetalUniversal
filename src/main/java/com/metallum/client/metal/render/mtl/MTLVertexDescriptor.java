package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;

import java.lang.foreign.MemorySegment;

public final class MTLVertexDescriptor implements AutoCloseable {
    private final MemorySegment handle;
    private boolean configured;
    private boolean closed;

    public MTLVertexDescriptor() {
        this.handle = MetalNativeBridge.metallum_MTLVertexDescriptor_create();
    }

    public MemorySegment handle() {
        return this.handle;
    }

    /**
     * Returns whether this descriptor still represents Metal's default "no
     * vertex layout" state. A render pipeline whose vertex function has no
     * per-vertex stage-in attributes should leave its vertexDescriptor nil
     * rather than attaching an allocated-but-empty descriptor.
     */
    public boolean isEmpty() {
        return !this.configured;
    }

    public void setAttribute(long index, long format, long offset, long bufferIndex) {
        MetalNativeBridge.metallum_MTLVertexDescriptor_setAttribute(this.handle, index, format, offset, bufferIndex);
        this.configured = true;
    }

    public void setLayout(long bufferIndex, long stride, MTLVertexStepFunction stepFunction, long stepRate) {
        MetalNativeBridge.metallum_MTLVertexDescriptor_setLayout(this.handle, bufferIndex, stride, stepFunction.value, stepRate);
        this.configured = true;
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            MetalNativeBridge.metallum_release_object(this.handle);
        }
    }
}
