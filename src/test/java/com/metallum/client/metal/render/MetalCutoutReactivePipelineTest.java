package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalCutoutReactivePipelineTest {
    @Test
    void irisOwnershipPreventsIndependentCutoutAttachmentSubstitution() {
        assertTrue(MetalCutoutReactivePipeline.shouldSubstitute(true, false));
        assertFalse(MetalCutoutReactivePipeline.shouldSubstitute(true, true));
        assertFalse(MetalCutoutReactivePipeline.shouldSubstitute(false, false));
    }
}
