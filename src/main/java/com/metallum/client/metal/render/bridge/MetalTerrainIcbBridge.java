package com.metallum.client.metal.render.bridge;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Experimental Metal 3 terrain indexed-draw ICB bridge.
 *
 * <p>A zero result means the native side executed no draws and the caller must
 * use the ordinary multi-draw path. A one result means the complete batch was
 * encoded into and executed through an indirect command buffer.</p>
 */
public final class MetalTerrainIcbBridge {
    public static final int FEATURE_ID = 6;
    public static final int ABI_VERSION = 1;
    public static final long CAPABILITY_BIT = 1L << 13;

    private static final MethodHandle ENCODE = resolveEncode();

    private MetalTerrainIcbBridge() {
    }

    public static boolean available() {
        return ENCODE != null;
    }

    public static boolean encodeIndexedBatch(
            final MemorySegment encoder,
            final long primitiveType,
            final long indexType,
            final MemorySegment indexBuffer,
            final MemorySegment firstIndexOffsets,
            final MemorySegment indexCounts,
            final MemorySegment vertexOffsets,
            final int drawCount,
            final int instanceCount,
            final int baseInstance
    ) {
        MethodHandle handle = ENCODE;
        if (handle == null) {
            return false;
        }
        try {
            return (int) handle.invokeExact(
                    encoder,
                    primitiveType,
                    indexType,
                    indexBuffer,
                    firstIndexOffsets,
                    indexCounts,
                    vertexOffsets,
                    drawCount,
                    instanceCount,
                    baseInstance
            ) != 0;
        } catch (Throwable throwable) {
            throw new IllegalStateException(
                    "Terrain ICB pilot invocation failed after admission",
                    throwable
            );
        }
    }

    private static MethodHandle resolveEncode() {
        MetalNativeInterfaceTable table = MetalNativeInterfaceTable.negotiate(
                FEATURE_ID,
                ABI_VERSION
        );
        if (table == null
                || table.entryCount() < 1
                || (table.buildCapabilities() & CAPABILITY_BIT) == 0L) {
            return null;
        }
        return Linker.nativeLinker().downcallHandle(
                table.entry(0),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT
                )
        );
    }
}
