package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerrainGpuIcbAdmissionTest {
    private static TerrainGpuIcbAdmission.Inputs base() {
        return new TerrainGpuIcbAdmission.Inputs(
                true, true, true, true, true,
                true, false, 1024, 512, 1024L, 1024L
        );
    }

    @Test
    void effectiveCountersAreRequiredForActivation() {
        TerrainGpuIcbAdmission.Decision admitted = TerrainGpuIcbAdmission.decide(base());
        assertTrue(admitted.admitted());
        assertEquals(TerrainGpuIcbAdmission.Path.VISIBLE_TWO_STAGE, admitted.path());

        TerrainGpuIcbAdmission.Inputs noEncode = new TerrainGpuIcbAdmission.Inputs(
                true, true, true, true, true, true, false, 1024, 512, 0L, 1024L
        );
        TerrainGpuIcbAdmission.Decision rejected = TerrainGpuIcbAdmission.decide(noEncode);
        assertFalse(rejected.admitted());
        assertEquals("effective-encode-counter-zero", rejected.reason());
    }

    @Test
    void unsupportedOrStaleProducerFallsBack() {
        TerrainGpuIcbAdmission.Inputs stale = new TerrainGpuIcbAdmission.Inputs(
                true, true, true, true, false, false, false, 4, 4, 4L, 4L
        );
        TerrainGpuIcbAdmission.Decision rejected = TerrainGpuIcbAdmission.decide(stale);
        assertFalse(rejected.admitted());
        assertEquals("producer-generation-unavailable", rejected.reason());

        TerrainGpuIcbAdmission.Inputs unsupported = new TerrainGpuIcbAdmission.Inputs(
                true, false, true, true, true, false, false, 4, 4, 4L, 4L
        );
        assertEquals("metal4-capability-unavailable", TerrainGpuIcbAdmission.decide(unsupported).reason());
    }

    @Test
    void disabledLaneIsExplicitlyRejected() {
        TerrainGpuIcbAdmission.Inputs disabled = new TerrainGpuIcbAdmission.Inputs(
                false, true, true, true, true, false, false, 1, 1, 1L, 1L
        );
        TerrainGpuIcbAdmission.Decision decision = TerrainGpuIcbAdmission.decide(disabled);
        assertFalse(decision.admitted());
        assertEquals("feature-disabled", decision.reason());
    }
}
