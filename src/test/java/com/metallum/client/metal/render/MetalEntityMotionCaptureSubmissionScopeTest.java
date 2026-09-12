package com.metallum.client.metal.render;

import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class MetalEntityMotionCaptureSubmissionScopeTest {
    @AfterEach
    void reset() {
        MetalEntityMotionCapture.setEnabled(false);
        MetalEntityMotionCapture.setEnabled(true);
    }

    @Test
    void nestedSubmissionRestoresOuterOwner() {
        MetalEntityMotionCapture.setEnabled(true);
        MetalEntityMotionCapture.beginFrame();
        Object outerState = new Object();
        Object innerState = new Object();
        Object outerBefore = new Object();
        Object inner = new Object();
        Object outerAfter = new Object();
        MetalEntityMotionCapture.Sample outerSample = sample(101L, 1L);
        MetalEntityMotionCapture.Sample innerSample = sample(202L, 2L);
        MetalEntityMotionCapture.attachState(outerState, outerSample);
        MetalEntityMotionCapture.attachState(innerState, innerSample);

        MetalEntityMotionCapture.beginEntitySubmission(outerState);
        MetalEntityMotionCapture.captureModelSubmit(outerBefore);
        MetalEntityMotionCapture.beginEntitySubmission(innerState);
        MetalEntityMotionCapture.captureModelSubmit(inner);
        MetalEntityMotionCapture.endEntitySubmission();
        MetalEntityMotionCapture.captureModelSubmit(outerAfter);
        MetalEntityMotionCapture.endEntitySubmission();

        assertEquals(outerSample.objectId(), MetalEntityMotionCapture.sampleForSubmit(outerBefore).objectId());
        assertEquals(innerSample.objectId(), MetalEntityMotionCapture.sampleForSubmit(inner).objectId());
        assertEquals(outerSample.objectId(), MetalEntityMotionCapture.sampleForSubmit(outerAfter).objectId());
    }

    @Test
    void unsupportedNestedSubmissionDoesNotInheritOuterOwner() {
        MetalEntityMotionCapture.setEnabled(true);
        MetalEntityMotionCapture.beginFrame();
        Object outerState = new Object();
        Object unsupportedState = new Object();
        Object leaked = new Object();
        Object restored = new Object();
        MetalEntityMotionCapture.Sample outerSample = sample(303L, 3L);
        MetalEntityMotionCapture.attachState(outerState, outerSample);

        MetalEntityMotionCapture.beginEntitySubmission(outerState);
        MetalEntityMotionCapture.beginEntitySubmission(unsupportedState);
        MetalEntityMotionCapture.captureModelSubmit(leaked);
        MetalEntityMotionCapture.endEntitySubmission();
        MetalEntityMotionCapture.captureModelSubmit(restored);
        MetalEntityMotionCapture.endEntitySubmission();

        assertNull(MetalEntityMotionCapture.sampleForSubmit(leaked));
        assertEquals(outerSample.objectId(), MetalEntityMotionCapture.sampleForSubmit(restored).objectId());
    }

    private static MetalEntityMotionCapture.Sample sample(final long objectId, final long generation) {
        Matrix4f identity = new Matrix4f();
        return new MetalEntityMotionCapture.Sample(objectId, generation, identity, identity);
    }
}
