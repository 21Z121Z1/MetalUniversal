package com.metallum.client.metal.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

final class MetalEntityMotionCaptureOffTest {
    @AfterEach
    void restoreCapture() {
        MetalEntityMotionCapture.setEnabled(true);
        MetalEntityMotionCapture.beginFrame();
    }

    @Test
    void disabledCaptureRejectsAllFrameWork() {
        MetalEntityMotionCapture.setEnabled(false);

        MetalEntityMotionCapture.beginFrame();
        MetalEntityMotionCapture.beginEntitySubmission(new Object());
        MetalEntityMotionCapture.captureModelSubmit(new Object());
        MetalEntityMotionCapture.endEntitySubmission();
        MetalEntityMotionCapture.endModelBuild();

        assertFalse(MetalEntityMotionCapture.isEnabled());
        assertNull(MetalEntityMotionCapture.takeExecute(null));
        MetalEntityMotionCapture.Diagnostics diagnostics = MetalEntityMotionCapture.diagnostics();
        assertEquals(0, diagnostics.statesAttached());
        assertEquals(0, diagnostics.entitySubmissionsMatched());
        assertEquals(0, diagnostics.modelSubmitsCaptured());
        assertEquals(0, diagnostics.motionDrawsEncoded());
    }
}
