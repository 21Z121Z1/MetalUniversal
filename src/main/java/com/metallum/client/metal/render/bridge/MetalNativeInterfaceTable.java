package com.metallum.client.metal.render.bridge;

import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

/**
 * Reader for the append-only native interface tables exported by
 * {@code metallum_get_interface}.
 *
 * <p>The lookup is deliberately lazy. Loading {@link MetalNativeBridge} first
 * ensures the matched dylib is present; an older dylib or a platform whose
 * loader does not expose the negotiation symbol simply returns {@code null}
 * and leaves the legacy per-symbol path active.</p>
 */
public final class MetalNativeInterfaceTable {
    private static final int HEADER_MIN_BYTES = 32;
    private static final Linker LINKER = Linker.nativeLinker();
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG;
    private static final MethodHandle GET_INTERFACE = findGetInterface();

    private final int featureId;
    private final int abiVersion;
    private final long buildCapabilities;
    private final MemorySegment[] entries;

    private MetalNativeInterfaceTable(
            final int featureId,
            final int abiVersion,
            final long buildCapabilities,
            final MemorySegment[] entries
    ) {
        this.featureId = featureId;
        this.abiVersion = abiVersion;
        this.buildCapabilities = buildCapabilities;
        this.entries = entries;
    }

    public static @Nullable MetalNativeInterfaceTable negotiate(
            final int featureId,
            final int minimumVersion
    ) {
        if (GET_INTERFACE == null || minimumVersion < 0) {
            return null;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outTable = arena.allocate(ValueLayout.ADDRESS);
            int status = (int) GET_INTERFACE.invokeExact(featureId, minimumVersion, outTable);
            if (status != 0) {
                return null;
            }
            MemorySegment rawPointer = outTable.get(ValueLayout.ADDRESS, 0L);
            if (rawPointer.address() == 0L) {
                return null;
            }
            MemorySegment header = rawPointer.reinterpret(HEADER_MIN_BYTES);
            long headerSize = Integer.toUnsignedLong(header.get(INT, 0L));
            long byteCount = Integer.toUnsignedLong(header.get(INT, 4L));
            int abiVersion = header.get(INT, 8L);
            int actualFeatureId = header.get(INT, 12L);
            long entryCount = Integer.toUnsignedLong(header.get(INT, 16L));
            long capabilities = header.get(LONG, 24L);
            long pointerBytes = ValueLayout.ADDRESS.byteSize();

            if (actualFeatureId != featureId
                    || abiVersion < minimumVersion
                    || headerSize < HEADER_MIN_BYTES
                    || byteCount < headerSize
                    || entryCount > Integer.MAX_VALUE
                    || entryCount > (Long.MAX_VALUE - headerSize) / pointerBytes
                    || headerSize + entryCount * pointerBytes > byteCount) {
                return null;
            }

            MemorySegment table = rawPointer.reinterpret(byteCount);
            MemorySegment[] entries = new MemorySegment[(int) entryCount];
            for (int index = 0; index < entries.length; index++) {
                MemorySegment entry = table.get(
                        ValueLayout.ADDRESS,
                        headerSize + (long) index * pointerBytes
                );
                if (entry.address() == 0L) {
                    return null;
                }
                entries[index] = entry;
            }
            return new MetalNativeInterfaceTable(
                    actualFeatureId,
                    abiVersion,
                    capabilities,
                    entries
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    public int featureId() {
        return this.featureId;
    }

    public int abiVersion() {
        return this.abiVersion;
    }

    public long buildCapabilities() {
        return this.buildCapabilities;
    }

    public int entryCount() {
        return this.entries.length;
    }

    public MemorySegment entry(final int index) {
        if (index < 0 || index >= this.entries.length) {
            throw new IndexOutOfBoundsException(
                    "Native interface entry " + index + " of " + this.entries.length
            );
        }
        return this.entries[index];
    }

    private static @Nullable MethodHandle findGetInterface() {
        try {
            // Trigger the matched dylib load before asking loader/default lookup.
            MetalNativeBridge.isIOS();
            Optional<MemorySegment> symbol = SymbolLookup.loaderLookup().find("metallum_get_interface");
            if (symbol.isEmpty()) {
                symbol = LINKER.defaultLookup().find("metallum_get_interface");
            }
            return symbol.map(address -> LINKER.downcallHandle(
                    address,
                    FunctionDescriptor.of(
                            INT,
                            INT,
                            INT,
                            ValueLayout.ADDRESS
                    )
            )).orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
