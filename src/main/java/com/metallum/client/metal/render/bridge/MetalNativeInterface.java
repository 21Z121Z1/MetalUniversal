package com.metallum.client.metal.render.bridge;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Reader for the dylib's versioned interface table.
 *
 * <p>Per-symbol downcalls make the jar and the dylib a matched pair: looking up
 * a symbol an older dylib does not export fails at bridge initialisation, and
 * there is no way to ask a dylib what it implements before calling into it. This
 * class asks. {@code metallum_get_interface} reports, per feature, an interface
 * version and the capabilities the dylib was built with; a caller then uses the
 * feature at a version both sides understand or degrades.</p>
 *
 * <p>Absence is not an error here. A dylib without the symbol is simply an older
 * dylib, and {@link #negotiate} reports that as an empty result rather than an
 * exception, because refusing to start is exactly the failure mode this is meant
 * to remove. A table that is <em>present but malformed</em> is a different
 * matter and does throw: that means the two sides disagree about the layout, and
 * reading on would interpret arbitrary bytes as function pointers.</p>
 *
 * <p>This class deliberately takes its {@link SymbolLookup} from the caller
 * instead of loading the dylib itself. The bridge already owns extraction and
 * loading, and a second extraction would produce a second loaded copy with its
 * own table cache.</p>
 */
public final class MetalNativeInterface {
    /** Header size at the version this reader was written against. */
    private static final int BASELINE_HEADER_SIZE = 32;
    private static final int OFFSET_HEADER_SIZE = 0;
    private static final int OFFSET_BYTE_COUNT = 4;
    private static final int OFFSET_ABI_VERSION = 8;
    private static final int OFFSET_FEATURE_ID = 12;
    private static final int OFFSET_ENTRY_COUNT = 16;
    private static final int OFFSET_BUILD_CAPABILITIES = 24;
    private static final long ENTRY_STRIDE = ValueLayout.ADDRESS.byteSize();

    private static final String NEGOTIATION_SYMBOL = "metallum_get_interface";
    private static final FunctionDescriptor NEGOTIATION_DESCRIPTOR =
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);

    private static final int STATUS_OK = 0;
    private static final int STATUS_UNKNOWN_FEATURE = 1;
    private static final int STATUS_VERSION_TOO_NEW = 2;

    private final Feature feature;
    private final int version;
    private final Set<Capability> capabilities;
    private final List<MemorySegment> entries;

    private MetalNativeInterface(
            final Feature feature,
            final int version,
            final Set<Capability> capabilities,
            final List<MemorySegment> entries
    ) {
        this.feature = feature;
        this.version = version;
        this.capabilities = Set.copyOf(capabilities);
        this.entries = List.copyOf(entries);
    }

    /**
     * Negotiates one feature's interface.
     *
     * @param minVersion lowest interface version this caller can work with
     * @return the negotiated interface, or empty when the dylib does not export
     *         the negotiation symbol at all, does not know the feature, or only
     *         provides an older version than {@code minVersion}
     * @throws IllegalStateException if the table is present but does not match
     *         the documented layout
     */
    public static Optional<MetalNativeInterface> negotiate(
            final SymbolLookup lookup,
            final Feature feature,
            final int minVersion
    ) {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(feature, "feature");
        if (minVersion < 1) {
            throw new IllegalArgumentException("minVersion must be at least 1");
        }
        Optional<MemorySegment> symbol = lookup.find(NEGOTIATION_SYMBOL);
        if (symbol.isEmpty()) {
            return Optional.empty();
        }
        MethodHandle negotiate = Linker.nativeLinker().downcallHandle(symbol.get(), NEGOTIATION_DESCRIPTOR);

        int status;
        MemorySegment table;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
            try {
                status = (int) negotiate.invokeExact(feature.id(), minVersion, out);
            } catch (Throwable throwable) {
                throw new IllegalStateException("Calling " + NEGOTIATION_SYMBOL + " failed", throwable);
            }
            if (status == STATUS_UNKNOWN_FEATURE || status == STATUS_VERSION_TOO_NEW) {
                return Optional.empty();
            }
            if (status != STATUS_OK) {
                throw new IllegalStateException(NEGOTIATION_SYMBOL + " returned unknown status " + status);
            }
            table = out.get(ValueLayout.ADDRESS, 0);
        }
        if (table.equals(MemorySegment.NULL)) {
            throw new IllegalStateException(NEGOTIATION_SYMBOL + " reported success but produced no table");
        }
        return Optional.of(read(table, feature, minVersion));
    }

    private static MetalNativeInterface read(final MemorySegment table, final Feature feature, final int minVersion) {
        MemorySegment header = table.reinterpret(BASELINE_HEADER_SIZE);
        long headerSize = Integer.toUnsignedLong(header.get(ValueLayout.JAVA_INT, OFFSET_HEADER_SIZE));
        long byteCount = Integer.toUnsignedLong(header.get(ValueLayout.JAVA_INT, OFFSET_BYTE_COUNT));
        int version = header.get(ValueLayout.JAVA_INT, OFFSET_ABI_VERSION);
        int featureId = header.get(ValueLayout.JAVA_INT, OFFSET_FEATURE_ID);
        long entryCount = Integer.toUnsignedLong(header.get(ValueLayout.JAVA_INT, OFFSET_ENTRY_COUNT));
        long capabilityBits = header.get(ValueLayout.JAVA_LONG, OFFSET_BUILD_CAPABILITIES);

        // A header may only grow, so a shorter one means the dylib is speaking a
        // layout this reader predates and every offset below would be wrong.
        if (headerSize < BASELINE_HEADER_SIZE) {
            throw new IllegalStateException("Interface table header is " + headerSize
                    + " bytes; this reader requires at least " + BASELINE_HEADER_SIZE);
        }
        if (featureId != feature.id()) {
            throw new IllegalStateException("Asked for feature " + feature + " (" + feature.id()
                    + ") but the table reports feature id " + featureId);
        }
        if (version < minVersion) {
            throw new IllegalStateException("Interface table reports version " + version
                    + " after accepting a minimum of " + minVersion);
        }
        if (entryCount < 0 || entryCount > 1024) {
            throw new IllegalStateException("Interface table declares an implausible entry count " + entryCount);
        }
        long required = headerSize + entryCount * ENTRY_STRIDE;
        if (byteCount < required) {
            throw new IllegalStateException("Interface table declares " + byteCount + " bytes but "
                    + entryCount + " entries need " + required);
        }

        MemorySegment whole = table.reinterpret(byteCount);
        List<MemorySegment> entries = new java.util.ArrayList<>((int) entryCount);
        for (long index = 0; index < entryCount; index++) {
            MemorySegment entry = whole.get(ValueLayout.ADDRESS, headerSize + index * ENTRY_STRIDE);
            if (entry.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("Interface table entry " + index + " is null");
            }
            entries.add(entry);
        }

        Set<Capability> capabilities = EnumSet.noneOf(Capability.class);
        for (Capability capability : Capability.values()) {
            if ((capabilityBits & capability.bit()) != 0L) {
                capabilities.add(capability);
            }
        }
        return new MetalNativeInterface(feature, version, capabilities, entries);
    }

    public Feature feature() {
        return feature;
    }

    /** The interface version the dylib provides for this feature. */
    public int version() {
        return version;
    }

    /** What the dylib was built to implement. Device support is a separate question. */
    public Set<Capability> capabilities() {
        return capabilities;
    }

    public boolean supports(final Capability capability) {
        return capabilities.contains(Objects.requireNonNull(capability, "capability"));
    }

    public int entryCount() {
        return entries.size();
    }

    /**
     * Binds one table entry as a callable handle.
     *
     * <p>Entry indices are the frozen ABI order documented in
     * {@code MetallumInterface.swift}. Calling through the table rather than by
     * symbol name is what lets a dylib add entries without the jar's existing
     * calls moving.</p>
     */
    public MethodHandle entry(final int index, final FunctionDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (index < 0 || index >= entries.size()) {
            throw new IndexOutOfBoundsException("Entry " + index + " is outside the "
                    + entries.size() + " entries of " + feature + " v" + version);
        }
        return Linker.nativeLinker().downcallHandle(entries.get(index), descriptor);
    }

    /** Raw entry address, for diagnostics and for asserting table structure. */
    long entryAddress(final int index) {
        return entries.get(index).address();
    }

    /** Feature identities. Values match {@code MetallumInterfaceFeature} in Swift. */
    public enum Feature {
        CORE(1),
        METALFX(2);

        private final int id;

        Feature(final int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    /**
     * Capability bits. Values match {@code MetallumBuildCapability} in Swift; a
     * bit this reader does not know about is ignored rather than rejected, so a
     * newer dylib stays usable.
     */
    public enum Capability {
        CORE(1L << 0),
        RASTER(1L << 1),
        COMPUTE(1L << 2),
        METALFX_SPATIAL(1L << 3),
        METALFX_TEMPORAL(1L << 4),
        FRAME_GENERATION(1L << 5),
        MOTION_V2(1L << 6),
        CUTOUT_REACTIVE(1L << 7),
        HAND_OVERLAY(1L << 8),
        PRESENTATION_TIMELINE(1L << 9);

        private final long bit;

        Capability(final long bit) {
            this.bit = bit;
        }

        public long bit() {
            return bit;
        }
    }

    /** Frozen entry indices for {@link Feature#CORE} v1. */
    public static final class Core {
        /** {@code UInt64 metallum_core_build_capabilities(Int32 featureId)} */
        public static final int BUILD_CAPABILITIES = 0;
        /** {@code UInt64 metallum_core_device_capabilities(MTLDevice*)} */
        public static final int DEVICE_CAPABILITIES = 1;

        public static final FunctionDescriptor BUILD_CAPABILITIES_DESCRIPTOR =
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT);
        public static final FunctionDescriptor DEVICE_CAPABILITIES_DESCRIPTOR =
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS);

        private Core() {
        }
    }

    /** Frozen entry indices for {@link Feature#METALFX} v1. */
    public static final class MetalFX {
        public static final int SUPPORTS_SPATIAL = 0;
        public static final int SUPPORTS_TEMPORAL = 1;
        public static final int SUPPORTS_FRAME_GENERATION = 2;
        public static final int SUPPORTS_MOTION_V2 = 3;
        public static final int SUPPORTS_CUTOUT_REACTIVE = 4;
        public static final int SUPPORTS_HAND_OVERLAY = 5;

        /** Every MetalFX probe is {@code Int32 (MTLDevice*)}. */
        public static final FunctionDescriptor PROBE_DESCRIPTOR =
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS);

        private MetalFX() {
        }
    }
}
