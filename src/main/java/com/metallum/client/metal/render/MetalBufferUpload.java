package com.metallum.client.metal.render;

import java.nio.ByteBuffer;

/** CPU copy contract for an unpublished dynamic backing. Never mutates a live backing. */
public final class MetalBufferUpload {
    private MetalBufferUpload() { }

    public static void validate(long bufferSize, long offset, long sliceLength, int bytes) {
        if (offset < 0 || sliceLength < 0 || bytes < 0 || offset > bufferSize
                || sliceLength > bufferSize - offset || bytes > sliceLength) {
            throw new IllegalArgumentException("Upload exceeds destination buffer slice");
        }
    }

    static void copy(ByteBuffer previous, ByteBuffer fresh, long logicalSize, long offset,
                     ByteBuffer data, boolean omitOverwrittenBytes) {
        validate(logicalSize, offset, logicalSize - offset, data.remaining());
        int size = Math.toIntExact(logicalSize);
        int start = Math.toIntExact(offset);
        int count = data.remaining();
        if (previous.capacity() < size || fresh.capacity() < size) {
            throw new IllegalArgumentException("Backing is smaller than the logical buffer");
        }
        ByteBuffer old = previous.duplicate().clear();
        ByteBuffer next = fresh.duplicate().clear();
        if (start != 0 || count != size) {
            if (omitOverwrittenBytes) {
                next.put(0, old, 0, start);
                next.put(start + count, old, start + count, size - start - count);
            } else {
                next.put(0, old, 0, size);
            }
        }
        next.put(start, data, data.position(), count);
    }
}
