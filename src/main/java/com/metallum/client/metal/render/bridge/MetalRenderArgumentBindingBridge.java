package com.metallum.client.metal.render.bridge;

import org.jspecify.annotations.Nullable;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/** Negotiated ABI for compiled Metal render argument buffers. */
public final class MetalRenderArgumentBindingBridge {
    public static final int FEATURE_ID = 7;
    public static final int ABI_VERSION = 1;
    public static final long CAPABILITY_BIT = 1L << 14;

    private static final Interface NATIVE = Interface.resolve();

    private MetalRenderArgumentBindingBridge() {
    }

    public static boolean available() {
        return NATIVE != null;
    }

    public static @Nullable Layout createLayout(
            final MemorySegment vertexFunction,
            final MemorySegment fragmentFunction,
            final boolean hasVertexArguments,
            final boolean hasFragmentArguments
    ) {
        Interface nativeInterface = NATIVE;
        if (nativeInterface == null || (!hasVertexArguments && !hasFragmentArguments)) {
            return null;
        }
        try {
            MemorySegment handle = (MemorySegment) nativeInterface.create.invokeExact(
                    vertexFunction,
                    fragmentFunction,
                    hasVertexArguments ? 1 : 0,
                    hasFragmentArguments ? 1 : 0
            );
            if (handle == null || handle.address() == 0L) {
                return null;
            }
            long packedSizes = (long) nativeInterface.sizes.invokeExact(handle);
            long vertexLength = Integer.toUnsignedLong((int) packedSizes);
            long fragmentLength = Integer.toUnsignedLong((int) (packedSizes >>> 32));
            if ((hasVertexArguments && (vertexLength == 0L || vertexLength == 0xffff_ffffL))
                    || (hasFragmentArguments && (fragmentLength == 0L || fragmentLength == 0xffff_ffffL))) {
                MetalNativeBridge.metallum_release_object(handle);
                return null;
            }
            return new Layout(handle, vertexLength, fragmentLength);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Failed to create compiled Metal argument layout", throwable);
        }
    }

    public static int apply(
            final Layout layout,
            final MemorySegment encoder,
            final MemorySegment vertexArgumentBuffer,
            final long vertexArgumentOffset,
            final MemorySegment fragmentArgumentBuffer,
            final long fragmentArgumentOffset,
            final MemorySegment packet,
            final long byteCount
    ) {
        Interface nativeInterface = NATIVE;
        if (nativeInterface == null || layout == null || layout.closed) {
            return -100;
        }
        try {
            return (int) nativeInterface.apply.invokeExact(
                    layout.handle,
                    encoder,
                    vertexArgumentBuffer,
                    vertexArgumentOffset,
                    fragmentArgumentBuffer,
                    fragmentArgumentOffset,
                    packet,
                    byteCount
            );
        } catch (Throwable throwable) {
            throw new IllegalStateException(
                    "Metal argument packet invocation failed before a result was returned",
                    throwable
            );
        }
    }

    public static final class Layout implements AutoCloseable {
        private final MemorySegment handle;
        private final long vertexEncodedLength;
        private final long fragmentEncodedLength;
        private boolean closed;

        private Layout(
                final MemorySegment handle,
                final long vertexEncodedLength,
                final long fragmentEncodedLength
        ) {
            this.handle = handle;
            this.vertexEncodedLength = vertexEncodedLength;
            this.fragmentEncodedLength = fragmentEncodedLength;
        }

        public long vertexEncodedLength() {
            return vertexEncodedLength;
        }

        public long fragmentEncodedLength() {
            return fragmentEncodedLength;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            MetalNativeBridge.metallum_release_object(handle);
        }
    }

    private record Interface(MethodHandle create, MethodHandle sizes, MethodHandle apply) {
        private static @Nullable Interface resolve() {
            MetalNativeInterfaceTable table = MetalNativeInterfaceTable.negotiate(
                    FEATURE_ID,
                    ABI_VERSION
            );
            if (table == null
                    || table.entryCount() < 3
                    || (table.buildCapabilities() & CAPABILITY_BIT) == 0L) {
                return null;
            }
            Linker linker = Linker.nativeLinker();
            return new Interface(
                    MetalFfmCallTelemetry.instrumentDowncall(linker.downcallHandle(
                            table.entry(0),
                            FunctionDescriptor.of(
                                    ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT
                            )
                    )),
                    MetalFfmCallTelemetry.instrumentDowncall(linker.downcallHandle(
                            table.entry(1),
                            FunctionDescriptor.of(
                                    ValueLayout.JAVA_LONG,
                                    ValueLayout.ADDRESS
                            )
                    )),
                    MetalFfmCallTelemetry.instrumentDowncall(linker.downcallHandle(
                            table.entry(2),
                            FunctionDescriptor.of(
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_LONG,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_LONG,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_LONG
                            )
                    ))
            );
        }
    }
}
