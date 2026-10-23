package com.metallum.client.metal.render;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Forward rasterization is the oracle, not a copy of the projection edits. */
final class MetalFxProjectionContractTest {
    private static Matrix4f cameraEffects() {
        // GameRenderer.renderLevel: P * bob/hurt * R * skew * inverse(R).
        return new Matrix4f().translate(0.13F, -0.08F, 0.03F)
                .rotateX(0.17F).rotateZ(-0.12F)
                .rotate(0.51F, 0.0F, 0.70710677F, 0.70710677F)
                .scale(0.83F, 1.0F, 1.0F)
                .rotate(-0.51F, 0.0F, 0.70710677F, 0.70710677F);
    }

    @Test
    void allHaltonPhasesOffsetEveryVertexEquallyAfterWorldCameraEffects() {
        for (int[] size : new int[][] {{1280, 720}, {857, 481}, {613, 997}}) {
            Matrix4f projection = new Matrix4f().setPerspective(
                    (float) Math.toRadians(70), (float) size[0] / size[1], 0.05F, 1000, true)
                    .mul(cameraEffects());
            assertTrue(Math.abs(projection.m13()) > 0.01F,
                    "The fixture must exercise a noncanonical homogeneous W row");
            for (int phase = 0; phase < 16; phase++) {
                Vector2f jitter = MetalFxMath.pixelJitter(phase, 16);
                assertRasterOffset(projection, jitter, size[0], size[1]);
            }
        }
    }

    @Test
    void orthographicAndAsymmetricProjectionsUseTheSamePixelConvention() {
        assertRasterOffset(new Matrix4f().setOrtho(-3, 5, -2, 4, 0.05F, 1000, true)
                .mul(cameraEffects()), new Vector2f(-0.375F, 0.25F), 853, 479);
        assertRasterOffset(new Matrix4f().setFrustum(-0.03F, 0.06F, -0.02F, 0.04F,
                0.05F, 1000, true).mul(cameraEffects()),
                new Vector2f(0.25F, -0.5F), 1920, 1080);
    }

    @Test
    void roundedRenderAspectMatchesRebuildingPerspectiveBeforeCameraEffects() {
        float displayAspect = 1920.0F / 1080;
        float renderAspect = 857.0F / 481;
        Matrix4f actual = new Matrix4f().setPerspective(
                (float) Math.toRadians(70), displayAspect, 0.05F, 1000, true)
                .mul(cameraEffects());
        Matrix4f expected = new Matrix4f().setPerspective(
                (float) Math.toRadians(70), renderAspect, 0.05F, 1000, true)
                .mul(cameraEffects());
        MetalFxMath.adjustPerspectiveAspect(actual, displayAspect, renderAspect);
        assertTrue(MetalFxMath.maxAbsDifference(actual, expected) < 2.0E-6F);
        assertRasterOffset(actual, new Vector2f(-0.25F, 0.4F), 857, 481);
    }

    @Test
    void invalidAspectDoesNotPartiallyMutateTheProjection() {
        Matrix4f original = new Matrix4f().setPerspective(1.2F, 1.7F, 0.05F, 500, true)
                .mul(cameraEffects());
        for (float invalid : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
            Matrix4f candidate = new Matrix4f(original);
            MetalFxMath.adjustPerspectiveAspect(candidate, 1.7F, invalid);
            assertEquals(0.0F, MetalFxMath.maxAbsDifference(candidate, original));
        }
    }

    private static void assertRasterOffset(Matrix4f projection, Vector2f jitter, int width, int height) {
        Matrix4f jittered = new Matrix4f(projection);
        MetalFxMath.applyProjectionJitter(jittered, MetalFxMath.clipJitter(jitter, width, height));
        for (Vector4f vertex : new Vector4f[] {
                new Vector4f(-0.03F, 0.01F, -0.4F, 1),
                new Vector4f(1.1F, -0.9F, -7, 1),
                new Vector4f(-12, 17, -140, 1)}) {
            Vector4f before = new Vector4f(vertex).mul(projection);
            Vector4f after = new Vector4f(vertex).mul(jittered);
            assertEquals(before.z, after.z, 0.0F, "Jitter must not change clip depth");
            assertEquals(before.w, after.w, 0.0F, "Jitter must not change homogeneous W");
            assertEquals(jitter.x, (after.x / after.w - before.x / before.w) * width * 0.5F,
                    2.5E-4F, "X must be measured in source pixels, independent of vertex position");
            assertEquals(jitter.y, -(after.y / after.w - before.y / before.w) * height * 0.5F,
                    2.5E-4F, "Y must be top-left screen pixels, not clip-space units");
        }
    }
}
