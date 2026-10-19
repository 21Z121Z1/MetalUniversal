package com.metallum.client.metal.render.bridge;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** Synchronously borrowed CPU arguments for the append-only render-state interface v3. */
public final class MetalIndexedBatchDescriptor {
    public static final int HEADER_BYTES = 64;
    public static final int BINDING_BYTES = 32;
    public static final int MAX_BINDINGS = 31;
    public static final int STORAGE_BYTES = HEADER_BYTES + MAX_BINDINGS * BINDING_BYTES;

    private MetalIndexedBatchDescriptor() { }

    public static int initialize(MemorySegment descriptor, int bindings, int drawCount,
                                 int primitive, int indexType, int instances, int firstInstance,
                                 MemorySegment indexBuffer, long parameters, long parameterBytes) {
        if (bindings < 1 || bindings > MAX_BINDINGS) throw new IllegalArgumentException("Invalid vertex binding count");
        int bytes = HEADER_BYTES + bindings * BINDING_BYTES;
        descriptor.set(ValueLayout.JAVA_INT, 0, 0x4D494242); // MIBB
        descriptor.set(ValueLayout.JAVA_INT, 4, 1);
        descriptor.set(ValueLayout.JAVA_INT, 8, bytes);
        descriptor.set(ValueLayout.JAVA_INT, 12, drawCount);
        descriptor.set(ValueLayout.JAVA_INT, 16, primitive);
        descriptor.set(ValueLayout.JAVA_INT, 20, indexType);
        descriptor.set(ValueLayout.JAVA_INT, 24, instances);
        descriptor.set(ValueLayout.JAVA_INT, 28, firstInstance);
        descriptor.set(ValueLayout.JAVA_LONG, 32, indexBuffer.address());
        descriptor.set(ValueLayout.JAVA_LONG, 40, parameters);
        descriptor.set(ValueLayout.JAVA_LONG, 48, parameterBytes);
        descriptor.set(ValueLayout.JAVA_INT, 56, bindings);
        descriptor.set(ValueLayout.JAVA_INT, 60, 0);
        return bytes;
    }

    public static void binding(MemorySegment descriptor, int record, MemorySegment buffer,
                               long originalOffset, int stride, int nativeSlot) {
        long offset = HEADER_BYTES + (long) record * BINDING_BYTES;
        descriptor.set(ValueLayout.JAVA_LONG, offset, buffer.address());
        descriptor.set(ValueLayout.JAVA_LONG, offset + 8, originalOffset);
        descriptor.set(ValueLayout.JAVA_LONG, offset + 16, stride);
        descriptor.set(ValueLayout.JAVA_INT, offset + 24, nativeSlot);
        descriptor.set(ValueLayout.JAVA_INT, offset + 28, 0);
    }
}
