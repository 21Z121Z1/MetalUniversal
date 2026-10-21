package com.metallum.client.metal.render.bridge;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/** Negotiated ordinary-downcall bridge for render-state packets. */
public final class MetalRenderStatePacketBridge {
    public static final int FEATURE_ID = 3;
    public static final int ABI_VERSION = 1;
    public static final long CAPABILITY_BIT = 1L << 10;

    private static final MethodHandle APPLY = resolveApply();

    private MetalRenderStatePacketBridge() {
    }

    public static boolean available() {
        return APPLY != null;
    }

    /** Returns applied entry count, or a negative value on an unavailable/failed call. */
    public static int apply(
            final MemorySegment encoder,
            final MemorySegment packet,
            final long byteCount
    ) {
        MethodHandle handle = APPLY;
        if (handle == null) {
            return -100;
        }
        try {
            return (int) handle.invokeExact(encoder, packet, byteCount);
        } catch (Throwable ignored) {
            return -101;
        }
    }

    public static boolean drawAvailable() {
        return DrawAccess.DRAW != null;
    }

    /**
     * One means the draw was encoded; every other native status guarantees no draw.
     * An FFM exception has an uncertain outcome and must never replay a draw.
     */
    public static int applyDraw(
            final MemorySegment encoder,
            final MemorySegment packet,
            final long byteCount,
            final MemorySegment draw
    ) {
        MethodHandle handle = DrawAccess.DRAW;
        if (handle == null) return -100;
        try {
            return (int) handle.invokeExact(encoder, packet, byteCount, draw);
        } catch (Throwable failure) {
            throw new IllegalStateException("Uncertain native state/draw outcome; draw replay is unsafe", failure);
        }
    }

    public static boolean indexedBatchAvailable() {
        return IndexedBatchAccess.DRAW != null;
    }

    /** A rejection encodes no draw and changes no binding; an uncertain call must never replay. */
    public static int applyIndexedBatch(MemorySegment encoder, MemorySegment descriptor, long bytes) {
        MethodHandle handle = IndexedBatchAccess.DRAW;
        if (handle == null) return -100;
        try {
            return (int) handle.invokeExact(encoder, descriptor, bytes);
        } catch (Throwable failure) {
            throw new IllegalStateException("Uncertain native indexed batch outcome; draw replay is unsafe", failure);
        }
    }

    private static final class IndexedBatchAccess {
        private static final MethodHandle DRAW = resolve();

        private static MethodHandle resolve() {
            MetalNativeInterfaceTable table = MetalNativeInterfaceTable.negotiate(FEATURE_ID, 3);
            if (table == null || table.entryCount() < 3
                    || (table.buildCapabilities() & CAPABILITY_BIT) == 0L) return null;
            return Linker.nativeLinker().downcallHandle(table.entry(2), FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        }
    }

    private static final class DrawAccess {
        private static final MethodHandle DRAW = resolve();

        private static MethodHandle resolve() {
            MetalNativeInterfaceTable table = MetalNativeInterfaceTable.negotiate(FEATURE_ID, 2);
            if (table == null || table.entryCount() < 2
                    || (table.buildCapabilities() & CAPABILITY_BIT) == 0L) return null;
            return Linker.nativeLinker().downcallHandle(table.entry(1), FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        }
    }

    private static MethodHandle resolveApply() {
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
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG
                )
        );
    }
}
