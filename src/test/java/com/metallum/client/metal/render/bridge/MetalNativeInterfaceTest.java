package com.metallum.client.metal.render.bridge;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the ABI handshake against the dylib that actually ships, rather than
 * against a mock: the whole point of the table is to describe one specific
 * binary, so a test that does not read that binary proves nothing.
 */
final class MetalNativeInterfaceTest {
    private static final Path DYLIB = Path.of("src/main/resources/natives/macos/libmetallum.dylib");

    private static SymbolLookup shippedDylib(final Arena arena) {
        assumeTrue(Files.isRegularFile(DYLIB),
                "no macOS dylib built at " + DYLIB.toAbsolutePath() + "; run :buildMacNative");
        return SymbolLookup.libraryLookup(DYLIB, arena);
    }

    @Test
    void coreNegotiatesAtVersionOne() {
        try (Arena arena = Arena.ofConfined()) {
            MetalNativeInterface core = MetalNativeInterface
                    .negotiate(shippedDylib(arena), MetalNativeInterface.Feature.CORE, 1)
                    .orElseThrow(() -> new AssertionError("the shipped dylib must export metallum_get_interface"));

            assertEquals(1, core.version(), "CORE is at interface version 1");
            assertEquals(2, core.entryCount(),
                    "CORE v1 has exactly two frozen entries; adding one must bump the version");
            assertTrue(core.capabilities().containsAll(Set.of(
                            MetalNativeInterface.Capability.CORE,
                            MetalNativeInterface.Capability.RASTER,
                            MetalNativeInterface.Capability.COMPUTE)),
                    "CORE build capabilities were " + core.capabilities());
        }
    }

    @Test
    void metalFxNegotiatesWithEveryProbe() {
        try (Arena arena = Arena.ofConfined()) {
            MetalNativeInterface metalFx = MetalNativeInterface
                    .negotiate(shippedDylib(arena), MetalNativeInterface.Feature.METALFX, 1)
                    .orElseThrow(() -> new AssertionError("the shipped dylib must provide the MetalFX interface"));

            assertEquals(6, metalFx.entryCount(),
                    "METALFX v1 exposes the six device probes in a frozen order");
            assertTrue(metalFx.supports(MetalNativeInterface.Capability.FRAME_GENERATION),
                    "this dylib is built with MetalFX, so frame generation is implemented even where"
                            + " no device supports it");
            assertTrue(metalFx.supports(MetalNativeInterface.Capability.CUTOUT_REACTIVE),
                    "cutout reactive support was " + metalFx.capabilities());

            Set<Long> addresses = new HashSet<>();
            for (int index = 0; index < metalFx.entryCount(); index++) {
                assertTrue(addresses.add(metalFx.entryAddress(index)),
                        "entry " + index + " repeats an earlier address, so the table is not populated per entry");
            }
        }
    }

    @Test
    void theTableIsCallableAndAgreesWithItsOwnHeader() {
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup lookup = shippedDylib(arena);
            MetalNativeInterface core = MetalNativeInterface
                    .negotiate(lookup, MetalNativeInterface.Feature.CORE, 1).orElseThrow();
            MetalNativeInterface metalFx = MetalNativeInterface
                    .negotiate(lookup, MetalNativeInterface.Feature.METALFX, 1).orElseThrow();

            MethodHandle buildCapabilities = core.entry(
                    MetalNativeInterface.Core.BUILD_CAPABILITIES,
                    MetalNativeInterface.Core.BUILD_CAPABILITIES_DESCRIPTOR);

            long viaTable;
            try {
                viaTable = (long) buildCapabilities.invokeExact(MetalNativeInterface.Feature.METALFX.id());
            } catch (Throwable throwable) {
                throw new AssertionError("calling through the interface table failed", throwable);
            }

            long viaHeader = 0L;
            for (MetalNativeInterface.Capability capability : metalFx.capabilities()) {
                viaHeader |= capability.bit();
            }
            assertEquals(viaHeader, viaTable,
                    "the capability bits in the MetalFX table header must match what the CORE table's"
                            + " capability function reports for that feature");
        }
    }

    @Test
    void aVersionNewerThanTheDylibProvidesDegradesInsteadOfThrowing() {
        try (Arena arena = Arena.ofConfined()) {
            assertEquals(Optional.empty(),
                    MetalNativeInterface.negotiate(shippedDylib(arena), MetalNativeInterface.Feature.CORE, 99),
                    "a jar built against a newer interface must be told no, not crash the bridge");
        }
    }

    @Test
    void aDylibWithoutTheSymbolDegradesInsteadOfThrowing() {
        SymbolLookup olderDylib = name -> Optional.empty();
        assertEquals(Optional.empty(),
                MetalNativeInterface.negotiate(olderDylib, MetalNativeInterface.Feature.CORE, 1),
                "a dylib predating the handshake is an older dylib, not a fatal error");
    }

    @Test
    void anEntryOutsideTheTableIsRejected() {
        try (Arena arena = Arena.ofConfined()) {
            MetalNativeInterface core = MetalNativeInterface
                    .negotiate(shippedDylib(arena), MetalNativeInterface.Feature.CORE, 1).orElseThrow();
            assertThrows(IndexOutOfBoundsException.class, () -> core.entry(
                            core.entryCount(), MetalNativeInterface.Core.BUILD_CAPABILITIES_DESCRIPTOR),
                    "reading past the declared entry count would bind an arbitrary address as a function");
        }
    }

    @Test
    void aMinimumVersionBelowOneIsRejected() {
        SymbolLookup unused = name -> Optional.empty();
        assertThrows(IllegalArgumentException.class,
                () -> MetalNativeInterface.negotiate(unused, MetalNativeInterface.Feature.CORE, 0));
    }
}
