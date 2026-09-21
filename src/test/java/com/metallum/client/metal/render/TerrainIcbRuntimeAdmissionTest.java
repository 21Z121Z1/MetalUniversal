package com.metallum.client.metal.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerrainIcbRuntimeAdmissionTest {
    @AfterEach
    void resetAdmission() {
        TerrainIcbRuntimeAdmission.reset();
    }

    @Test
    void finalPipelineRejectionDisablesUpstreamGpuIcbProducerWork() {
        assertTrue(TerrainIcbRuntimeAdmission.gpuIcbAdmitted());

        TerrainIcbRuntimeAdmission.rejectFinalPipeline();

        assertFalse(TerrainIcbRuntimeAdmission.gpuIcbAdmitted());
    }
}
