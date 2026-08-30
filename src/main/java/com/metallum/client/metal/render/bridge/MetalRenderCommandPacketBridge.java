package com.metallum.client.metal.render.bridge;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/** Negotiated ordinary-downcall bridge for ordered render command packets. */
public final class MetalRenderCommandPacketBridge {
    public static final int FEATURE_ID = 4;
    public static final int ABI_VERSION = 1;
    public static final long CAPABILITY_BIT = 1L << 11;

    private static final MethodHandle APPLY = resolveApply();

    private MetalRenderCommandPacketBridge() {
    }

    public static boolean available() {
        return APPLY != null;
    }

    /**
     * Returns the applied operation count. A negative result is guaranteed by
     * the native ABI to mean that no operation was applied and legacy replay is
     * safe. A Java/FFM invocation failure is fail-stop because replaying a packet
     * after an unknown native boundary could duplicate draw commands.
     */
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
        } catch (Throwable throwable) {
            throw new IllegalStateException(
                    "Render command packet FFM invocation failed; refusing unsafe draw replay",
                    throwable
            );
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
        return MetalFfmCallTelemetry.instrumentDowncall(
                Linker.nativeLinker().downcallHandle(
                        table.entry(0),
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG
                        )
                )
        );
    }
}
