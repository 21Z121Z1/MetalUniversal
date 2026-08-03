package com.metallum.client.terrain;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;

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
        int state = MetalNativeBridge.metallum_system_thermal_state();
        assumeTrue(state >= 0, "the loaded native library predates the thermal export");
        assertTrue(state <= 3, "Foundation thermal state was " + state);
    }

    @Test
    void memoryPressureSamplerIsBounded() {
        double pressure = TerrainRuntimeSignals.memoryPressure();
        assertTrue(pressure >= 0.0 && pressure <= 1.0, "memory pressure was " + pressure);
    }
}
