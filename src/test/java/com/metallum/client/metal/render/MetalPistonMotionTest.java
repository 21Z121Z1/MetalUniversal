package com.metallum.client.metal.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MetalPistonMotionTest {
    private static final float EPSILON = 1.0e-6F;

    @Test
    void currentToPreviousIsExactOffsetDelta() {
        Matrix4f previous = MetalPistonMotion.offsetTransform(-0.75F, 0.0F, 0.0F);
        Matrix4f current = MetalPistonMotion.offsetTransform(-0.50F, 0.0F, 0.0F);
        MetalEntityMotionCapture.Sample sample = new MetalEntityMotionCapture.Sample(1L, 1L, current, previous);

        Vector3f point = MetalEntityMotionCapture.objectCurrentToPrevious(sample)
                .transformPosition(new Vector3f(2.0F, 3.0F, 4.0F));
        assertEquals(1.75F, point.x, EPSILON);
        assertEquals(3.0F, point.y, EPSILON);
        assertEquals(4.0F, point.z, EPSILON);
    }

    @Test
    void arbitraryAxisOffsetsPreservePreviousPosition() {
        Matrix4f previous = MetalPistonMotion.offsetTransform(0.0F, 0.25F, 0.0F);
        Matrix4f current = MetalPistonMotion.offsetTransform(0.0F, 0.75F, 0.0F);
        MetalEntityMotionCapture.Sample sample = new MetalEntityMotionCapture.Sample(2L, 1L, current, previous);

        Vector3f currentPoint = new Vector3f(1.0F, 2.75F, -4.0F);
        Vector3f previousPoint = MetalEntityMotionCapture.objectCurrentToPrevious(sample)
                .transformPosition(new Vector3f(currentPoint));
        assertEquals(1.0F, previousPoint.x, EPSILON);
        assertEquals(2.25F, previousPoint.y, EPSILON);
        assertEquals(-4.0F, previousPoint.z, EPSILON);
    }
}
