package com.metallum.client.metal.render;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Checks projected points, not matrix entries. Minecraft 26.3 passes P * bob * portal to the hook. */
final class MetalFxRasterTransformTest {
    @Test
    void jitterIsAConstantPixelTranslationAfterEveryWorldProjectionTransform() {
        Matrix4f base = new Matrix4f().setPerspective((float) Math.toRadians(70), 16f / 9, .05f, 1024f, true);
        Matrix4f[] projections = {
                base,
                new Matrix4f(base).translate(.25f, -.4f, .3f).rotateXYZ(.11f, -.07f, .19f),
                new Matrix4f(base).rotate(.8f, 0, .70710677f, .70710677f)
                        .scale(.7f, 1, 1).rotate(-.8f, 0, .70710677f, .70710677f),
                new Matrix4f().setOrtho(-4, 4, -3, 3, .05f, 1024f, true)
        };
        for (Matrix4f projection : projections) {
            for (int phase = 0; phase < 8; phase++) {
                Vector2f jitter = MetalFxMath.pixelJitter(phase, 8);
                Matrix4f raster = new Matrix4f(projection);
                MetalFxMath.applyProjectionJitter(raster, MetalFxMath.clipJitter(jitter, 853, 479));
                for (Vector4f point : points()) {
                    Vector4f before = new Vector4f(point).mul(projection);
                    Vector4f after = new Vector4f(point).mul(raster);
                    assertEquals(jitter.x, (after.x / after.w - before.x / before.w) * 853f / 2, 2e-4f);
                    assertEquals(jitter.y, -(after.y / after.w - before.y / before.w) * 479f / 2, 2e-4f);
                    assertEquals(before.z, after.z, 0f, "Jitter must not change device depth");
                    assertEquals(before.w, after.w, 0f, "Jitter must not change perspective division");
                }
            }
        }
    }

    @Test
    void aspectCorrectionScalesTheCompleteClipXNotOnlyTheBaseFocalLength() {
        Matrix4f projection = new Matrix4f().setPerspective((float) Math.toRadians(70), 16f / 9, .05f, 1024f, true)
                .translate(.25f, -.4f, .3f).rotateXYZ(.11f, -.07f, .19f);
        Matrix4f corrected = new Matrix4f(projection);
        float displayAspect = 16f / 9;
        float renderAspect = 853f / 479;
        MetalFxMath.adjustPerspectiveAspect(corrected, displayAspect, renderAspect);
        for (Vector4f point : points()) {
            Vector4f before = new Vector4f(point).mul(projection);
            Vector4f after = new Vector4f(point).mul(corrected);
            assertEquals(before.x * displayAspect / renderAspect, after.x, 2e-6f);
            assertEquals(before.y, after.y, 0f);
            assertEquals(before.z, after.z, 0f);
            assertEquals(before.w, after.w, 0f);
        }
    }

    private static Vector4f[] points() {
        return new Vector4f[] {
                new Vector4f(1, .7f, -4, 1), new Vector4f(-2, -.2f, -19, 1),
                new Vector4f(.1f, .5f, -2, 1), new Vector4f(3, 1, -120, 1)
        };
    }
}
