package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Metal4RuntimeConfigurationTest {
    @Test
    void disabledMasterRejectsDependentSubfeatures() {
        var configuration = Metal4RuntimeConfiguration.resolve(
                false, true,
                true, true, true, true,
                false, false
        );

        assertFalse(configuration.available());
        assertFalse(configuration.compiler());
        assertFalse(configuration.present());
        assertFalse(configuration.mainQueuePilot());
        assertFalse(configuration.mainRenderer());
        assertEquals(
                "Metal 4 sub-feature requested while master switch is disabled",
                configuration.rejectionReason()
        );
    }

    @Test
    void unsupportedDeviceDisablesEveryMetal4Path() {
        var configuration = Metal4RuntimeConfiguration.resolve(
                true, false,
                true, true, true, true,
                true, true
        );

        assertFalse(configuration.available());
        assertFalse(configuration.compiler());
        assertFalse(configuration.present());
        assertFalse(configuration.mainQueuePilot());
        assertFalse(configuration.mainRenderer());
        assertTrue(configuration.barrier());
        assertTrue(configuration.residency());
        assertEquals(
                "device or runtime does not support Metal 4",
                configuration.rejectionReason()
        );
    }

    @Test
    void mainRendererClosesRequiredDependencies() {
        var configuration = Metal4RuntimeConfiguration.resolve(
                true, true,
                false, false, false, true,
                false, false
        );

        assertTrue(configuration.available());
        assertTrue(configuration.mainRenderer());
        assertTrue(configuration.compiler());
        assertTrue(configuration.present());
        assertTrue(configuration.residency());
        assertNull(configuration.rejectionReason());
    }

    @Test
    void presentCannotRunWithoutCompiler() {
        var configuration = Metal4RuntimeConfiguration.resolve(
                true, true,
                false, true, false, false,
                false, false
        );

        assertTrue(configuration.available());
        assertFalse(configuration.compiler());
        assertFalse(configuration.present());
        assertEquals(
                "Metal 4 present requires the compiler path",
                configuration.rejectionReason()
        );
    }

    @Test
    void compilerAndPilotCanBeEnabledIndependently() {
        var configuration = Metal4RuntimeConfiguration.resolve(
                true, true,
                true, false, true, false,
                false, false
        );

        assertTrue(configuration.compiler());
        assertFalse(configuration.present());
        assertTrue(configuration.mainQueuePilot());
        assertFalse(configuration.mainRenderer());
        assertNull(configuration.rejectionReason());
    }

    @Test
    void barrierAndResidencyPilotsRemainAvailableOnMetal3() {
        var configuration = Metal4RuntimeConfiguration.resolve(
                false, false,
                false, false, false, false,
                true, true
        );

        assertFalse(configuration.available());
        assertTrue(configuration.barrier());
        assertTrue(configuration.residency());
        assertNull(configuration.rejectionReason());
    }
}
