package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static com.metallum.client.metal.render.FrameSynthesisContract.ProducerCoverage.*;
import static com.metallum.client.metal.render.FrameSynthesisContract.ProducerDomain.*;
import static org.junit.jupiter.api.Assertions.*;

final class MetalFxSourceInputContractTest {
    @Test
    void everyObservedDomainRequiresExactMotionForInterpolation() {
        for (var domain : FrameSynthesisContract.ProducerDomain.values()) {
            for (var missing : new FrameSynthesisContract.ProducerCoverage[] {REACTIVE_ONLY, UNSUPPORTED}) {
                var receipts = new ArrayList<FrameSynthesisContract.ProducerReceipt>();
                for (var candidate : FrameSynthesisContract.ProducerDomain.values()) {
                    receipts.add(new FrameSynthesisContract.ProducerReceipt(candidate,
                            candidate == domain ? missing : REAL_MOTION, 1));
                }
                var coverage = new FrameSynthesisContract.ProducerCoverageSet(receipts);
                assertFalse(coverage.frameGenerationEligible(), domain + ": " + missing);
                assertEquals(missing == REACTIVE_ONLY, coverage.temporalEligible());
            }
        }
    }

    @Test
    void absenceCannotHideObservedSamplesOrReplaceCameraMotion() {
        for (var domain : FrameSynthesisContract.ProducerDomain.values()) {
            assertThrows(IllegalArgumentException.class,
                    () -> new FrameSynthesisContract.ProducerReceipt(domain, NOT_PRESENT, 1));
        }
        var receipts = new ArrayList<FrameSynthesisContract.ProducerReceipt>();
        for (var domain : FrameSynthesisContract.ProducerDomain.values()) {
            receipts.add(new FrameSynthesisContract.ProducerReceipt(domain, NOT_PRESENT, 0));
        }
        assertFalse(new FrameSynthesisContract.ProducerCoverageSet(receipts).frameGenerationEligible());
        receipts.set(CAMERA_DEPTH.ordinal(), new FrameSynthesisContract.ProducerReceipt(CAMERA_DEPTH, REAL_MOTION, 1));
        assertTrue(new FrameSynthesisContract.ProducerCoverageSet(receipts).frameGenerationEligible());
    }

    @Test
    void frustumIsRecoveredFromActualReversedProjectionIncludingNarrowZooms() {
        for (float fov : new float[] {1, 8, 30, 70, 110, 165}) {
            for (float aspect : new float[] {0.5625F, 857.0F / 481, 32.0F / 9}) {
                for (float far : new float[] {32, 1000, 4096}) {
                    Matrix4f projection = reversedProjection(fov, aspect, 0.05F, far);
                    var camera = FrameSynthesisContract.Perspective.fromProjection(projection);
                    assertEquals(fov, camera.fieldOfViewDegrees(), 3.0E-5F);
                    assertEquals(aspect, camera.aspectRatio(), 1.0E-6F);
                    assertEquals(0.05F, camera.nearPlane(), 1.0E-6F);
                    assertEquals(far, camera.farPlane(), far * 2.0E-6F);
                    // Forward projection, rather than reversing the extraction
                    // formula, independently checks the convention and planes.
                    Vector4f nearClip = new Vector4f(0, 0, -camera.nearPlane(), 1).mul(projection);
                    Vector4f farClip = new Vector4f(0, 0, -camera.farPlane(), 1).mul(projection);
                    assertEquals(1, nearClip.z / nearClip.w, 1.0E-6F);
                    assertEquals(0, farClip.z / farClip.w, 1.0E-6F);
                }
            }
        }
    }

    @Test
    void roundedRasterAspectIsNotConfusedWithTheDrawableAspect() {
        Matrix4f projection = reversedProjection(70, 1920.0F / 1080, 0.05F, 1000);
        MetalFxMath.adjustPerspectiveAspect(projection, 1920.0F / 1080, 857.0F / 481);
        assertEquals(857.0F / 481,
                FrameSynthesisContract.Perspective.fromProjection(projection).aspectRatio(), 1.0E-6F);
    }

    @Test
    void unsupportedProjectionDoesNotAcquirePlausibleLookingCameraDefaults() {
        for (Matrix4f invalid : new Matrix4f[] {
                new Matrix4f(), new Matrix4f().setOrtho(-1, 1, -1, 1, 0.05F, 1000, true),
                new Matrix4f().setPerspective(1.2F, 1.7F, 0.05F, 1000, true),
                reversedProjection(70, 1.7F, 0.05F, 1000).m20(0.1F),
                reversedProjection(70, 1.7F, 0.05F, 1000).m11(Float.NaN),
                reversedProjection(70, 1.7F, 0.05F, 1000).m22(0),
                reversedProjection(70, 1.7F, 0.05F, 1000).m32(-0.05F)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> FrameSynthesisContract.Perspective.fromProjection(invalid));
        }
    }

    @Test
    void sourceDeltaIsNeverClampedOrReplacedBySixtiethOfASecond() {
        var perspective = FrameSynthesisContract.Perspective.fromProjection(
                reversedProjection(70, 1.7F, 0.05F, 1000));
        for (float delta : new float[] {0.001F, 1.0F / 37, 0.3F, 1.25F}) {
            assertEquals(delta, perspective.atSourceInterval(delta).deltaSeconds(), 0);
        }
        for (float invalid : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> perspective.atSourceInterval(invalid));
        }
    }

    @Test
    void rigidCameraEffectsRemainRepresentableButPortalSkewDoesNot() {
        Matrix4f bob = new Matrix4f().translate(0.2F, -0.05F, 0)
                .rotateX(0.12F).rotateZ(-0.25F);
        assertTrue(MetalFxMath.isRigidViewTransform(bob));
        Matrix4f p = reversedProjection(8, 1.7F, 0.05F, 4096);
        Matrix4f world = new Matrix4f(p).mul(bob);
        assertTrue(MetalFxMath.isRigidViewTransform(new Matrix4f(p).invert().mul(world)));
        assertFalse(MetalFxMath.isRigidViewTransform(new Matrix4f(bob).scale(0.83F, 1, 1)));
        assertFalse(MetalFxMath.isRigidViewTransform(new Matrix4f(bob).scale(-1, 1, 1)));
        assertFalse(MetalFxMath.isRigidViewTransform(new Matrix4f(bob).m30(Float.NaN)));
    }

    @Test
    void depthTargetsMustBelongToTheSourceResolutionAndDepthRole() {
        GpuTexture valid = new Texture(857, 481, 1, 1, GpuFormat.D32_FLOAT, GpuTexture.USAGE_TEXTURE_BINDING, false);
        assertTrue(FrameSynthesisContract.sourceDepthMatches(valid, 857, 481));
        for (GpuTexture invalid : new GpuTexture[] {null,
                new Texture(1920, 1080, 1, 1, GpuFormat.D32_FLOAT, 4, false),
                new Texture(857, 481, 2, 1, GpuFormat.D32_FLOAT, 4, false),
                new Texture(857, 481, 1, 2, GpuFormat.D32_FLOAT, 4, false),
                new Texture(857, 481, 1, 1, GpuFormat.R32_FLOAT, 4, false),
                new Texture(857, 481, 1, 1, GpuFormat.D32_FLOAT, 0, false),
                new Texture(857, 481, 1, 1, GpuFormat.D32_FLOAT, 4, true)}) {
            assertFalse(FrameSynthesisContract.sourceDepthMatches(invalid, 857, 481));
        }
    }

    private static Matrix4f reversedProjection(float fov, float aspect, float near, float far) {
        return new Matrix4f().setPerspective((float) Math.toRadians(fov), aspect, far, near, true);
    }

    private record Texture(int width, int height, int layers, int mips, GpuFormat format,
                           int usage, boolean closed) implements GpuTexture {
        @Override public int getWidth(int mip) { return width; }
        @Override public int getHeight(int mip) { return height; }
        @Override public int getDepthOrLayers() { return layers; }
        @Override public int getMipLevels() { return mips; }
        @Override public GpuFormat getFormat() { return format; }
        @Override public String getLabel() { return "contract fixture"; }
        @Override public boolean isClosed() { return closed; }
        @Override public void close() { }
    }
}
