package com.metallum.client.metal.render.bridge;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Metal 3/Metal 4 terrain indexed-draw ICB bridge.
 *
 * <p>A zero result means the native side executed no draws and the caller must
 * use the ordinary multi-draw path. A one result means the complete batch was
 * encoded into and executed through an indirect command buffer.</p>
 */
public final class MetalTerrainIcbBridge {
    public static final int FEATURE_ID = 6;
    public static final int ABI_VERSION = 1;
    public static final long CAPABILITY_BIT = 1L << 13;

    private static final MetalNativeInterfaceTable TABLE = resolveTable();
    private static final MethodHandle ENCODE = resolveEncode(TABLE);
    private static final MethodHandle STATS = resolveStats(TABLE);

    private MetalTerrainIcbBridge() {
    }

    public static boolean available() {
        return ENCODE != null;
    }

    public static NativeStats nativeStats() {
        MethodHandle handle = STATS;
        if (handle == null) {
            return NativeStats.UNAVAILABLE;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment allocations = arena.allocate(ValueLayout.JAVA_LONG);
            MemorySegment completionReleases = arena.allocate(ValueLayout.JAVA_LONG);
            MemorySegment budgetFallbacks = arena.allocate(ValueLayout.JAVA_LONG);
            MemorySegment zeroFallbacks = arena.allocate(ValueLayout.JAVA_LONG);
            int status = (int) handle.invokeExact(
                    allocations,
                    completionReleases,
                    budgetFallbacks,
                    zeroFallbacks
            );
            if (status != 0) {
                return NativeStats.UNAVAILABLE;
            }
            return new NativeStats(
                    true,
                    allocations.get(ValueLayout.JAVA_LONG, 0L),
                    completionReleases.get(ValueLayout.JAVA_LONG, 0L),
                    budgetFallbacks.get(ValueLayout.JAVA_LONG, 0L),
                    zeroFallbacks.get(ValueLayout.JAVA_LONG, 0L)
            );
        } catch (Throwable throwable) {
            throw new IllegalStateException("Terrain ICB stats invocation failed", throwable);
        }
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
                    "Terrain ICB invocation failed after admission",
                    throwable
            );
        }
    }

    private static MetalNativeInterfaceTable resolveTable() {
        MetalNativeInterfaceTable table = MetalNativeInterfaceTable.negotiate(
                FEATURE_ID,
                ABI_VERSION
        );
        if (table == null
                || table.entryCount() < 1
                || (table.buildCapabilities() & CAPABILITY_BIT) == 0L) {
            return null;
        }
        return table;
    }

    private static MethodHandle resolveEncode(final MetalNativeInterfaceTable table) {
        if (table == null) {
            return null;
        }
        return MetalFfmCallTelemetry.instrumentDowncall(
                Linker.nativeLinker().downcallHandle(
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
                )
        );
    }

    private static MethodHandle resolveStats(final MetalNativeInterfaceTable table) {
        if (table == null || table.entryCount() < 2) {
            return null;
        }
        return MetalFfmCallTelemetry.instrumentDowncall(
                Linker.nativeLinker().downcallHandle(
                        table.entry(1),
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS
                        )
                )
        );
    }

    public record NativeStats(
            boolean available,
            long allocations,
            long completionReleases,
            long budgetFallbacks,
            long zeroAllocationFallbacks
    ) {
        private static final NativeStats UNAVAILABLE = new NativeStats(false, 0L, 0L, 0L, 0L);
    }
}
