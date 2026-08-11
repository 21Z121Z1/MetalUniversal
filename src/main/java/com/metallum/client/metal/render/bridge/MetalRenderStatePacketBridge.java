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
        // The decoder performs bounded native-only work and has no upcall path.
        // Match the critical FFM policy already used by individual Metal state
        // setters; hosted CI measures this candidate against the ordinary control.
        return MetalFfmCallTelemetry.instrumentDowncall(
                Linker.nativeLinker().downcallHandle(
                        table.entry(0),
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG
                        ),
                        Linker.Option.critical(false)
                )
        );
    }
}
