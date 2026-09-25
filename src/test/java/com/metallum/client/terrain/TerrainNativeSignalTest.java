package com.metallum.client.terrain;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class TerrainNativeSignalTest {
    private static final Path SHIPPED_DYLIB = Path.of("src/main/resources/natives/macos/libmetallum.dylib");

    @Test
    void shippedThermalExportReturnsFoundationState() throws Throwable {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("mac"));
        assumeTrue(Files.isRegularFile(SHIPPED_DYLIB),
                "run buildMacNative before this ABI smoke test");
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup lookup = SymbolLookup.libraryLookup(SHIPPED_DYLIB, arena);
            var symbol = lookup.find("metallum_system_thermal_state");
            assumeTrue(symbol.isPresent(), "the shipped native library predates the thermal export");
            MethodHandle thermalState = Linker.nativeLinker().downcallHandle(
                    symbol.orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT)
            );
            int state = (int) thermalState.invokeExact();
            assertTrue(state >= 0 && state <= 3, "Foundation thermal state was " + state);
        }
    }

    @Test
    void memoryPressureSamplerIsBounded() {
        double pressure = TerrainRuntimeSignals.memoryPressure();
        assertTrue(pressure >= 0.0 && pressure <= 1.0, "memory pressure was " + pressure);
    }
}
