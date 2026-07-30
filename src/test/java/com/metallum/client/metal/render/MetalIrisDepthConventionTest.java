package com.metallum.client.metal.render;

import com.mojang.blaze3d.platform.CompareOp;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalIrisDepthConventionTest {
    @Test
    void reversesMojangDepthStateOnlyWhenEnabled() {
        Map.ofEntries(
                Map.entry(CompareOp.ALWAYS_PASS, CompareOp.ALWAYS_PASS),
                Map.entry(CompareOp.LESS_THAN, CompareOp.GREATER_THAN),
                Map.entry(CompareOp.LESS_THAN_OR_EQUAL, CompareOp.GREATER_THAN_OR_EQUAL),
                Map.entry(CompareOp.EQUAL, CompareOp.EQUAL),
                Map.entry(CompareOp.NOT_EQUAL, CompareOp.NOT_EQUAL),
                Map.entry(CompareOp.GREATER_THAN_OR_EQUAL, CompareOp.LESS_THAN_OR_EQUAL),
                Map.entry(CompareOp.GREATER_THAN, CompareOp.LESS_THAN),
                Map.entry(CompareOp.NEVER_PASS, CompareOp.NEVER_PASS)
        ).forEach((source, expected) -> {
            assertEquals(source, MetalIrisDepthConvention.adaptCompare(source, false));
            assertEquals(expected, MetalIrisDepthConvention.adaptCompare(source, true));
        });

        assertEquals(0.0, MetalIrisDepthConvention.adaptClear(0.0, false));
        assertEquals(1.0, MetalIrisDepthConvention.adaptClear(0.0, true));
        assertEquals(0.0, MetalIrisDepthConvention.adaptClear(1.0, true));
        assertEquals(0.75, MetalIrisDepthConvention.adaptClear(0.25, true));
    }

    @Test
    void packProjectionPreservesOpenGlWindowDepth() {
        float fov = (float) Math.toRadians(70.0);
        float aspect = 16.0F / 9.0F;
        float near = 0.05F;
        float far = 512.0F;
        Matrix4f zeroToOne = new Matrix4f().setPerspective(fov, aspect, near, far, true);
        Matrix4f expectedOpenGl = new Matrix4f().setPerspective(fov, aspect, near, far, false);

        Matrix4f converted = MetalIrisDepthConvention.zeroToOneToOpenGl(zeroToOne);
        assertTrue(converted.equals(expectedOpenGl, 0.00001F));

        Matrix4f convertedInverse = MetalIrisDepthConvention
                .zeroToOneToOpenGl(new Matrix4f(zeroToOne))
                .invert();
        assertTrue(convertedInverse.equals(new Matrix4f(expectedOpenGl).invert(), 0.00001F));
    }
}
