package com.metallum.client.metal.render;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalFxMathTest {
    @Test
    void haltonSequenceUsesOneBasedIndices() {
        assertEquals(0.5F, MetalFxMath.halton(1, 2), 1.0E-6F);
        assertEquals(1.0F / 3.0F, MetalFxMath.halton(1, 3), 1.0E-6F);
        Vector2f first = MetalFxMath.pixelJitter(0, 4);
        assertEquals(0.0F, first.x, 1.0E-6F);
        assertEquals(-1.0F / 6.0F, first.y, 1.0E-6F);
    }

    @Test
    void pixelJitterConvertsToTheDocumentedClipConvention() {
        Vector2f clip = MetalFxMath.clipJitter(new Vector2f(0.25F, -0.5F), 1000, 500);
        assertEquals(0.0005F, clip.x, 1.0E-7F);
        assertEquals(0.002F, clip.y, 1.0E-7F);
    }

    /**
     * Closes the "projection jitter sign" item the MetalFX audit (6.5) left
     * open. The raster offset the jittered projection actually produces must
     * equal the pixel jitter handed to {@code jitterOffsetX/Y}; a right-handed
     * projection ({@code m23 == -1}) inverts the naive third-column edit, so
     * this pins the corrected direction against a real Minecraft-shaped
     * perspective rather than against an identity matrix.
     */
    @Test
    void projectionJitterMovesTheRasterByTheReportedPixelJitter() {
        int renderWidth = 1000;
        int renderHeight = 500;
        Vector2f pixelJitter = new Vector2f(0.25F, -0.5F);

        Matrix4f projection = new Matrix4f().setPerspective(
                (float) Math.toRadians(70.0),
                (float) renderWidth / renderHeight,
                0.05F,
                1000.0F
        );
        assertEquals(-1.0F, projection.m23(), 1.0E-6F, "Minecraft's projection is right handed");

        Vector4f viewPosition = new Vector4f(1.0F, 1.0F, -10.0F, 1.0F);
        Vector4f unjittered = new Vector4f(viewPosition).mul(projection);

        Matrix4f jittered = new Matrix4f(projection);
        MetalFxMath.applyProjectionJitter(
                jittered,
                MetalFxMath.clipJitter(pixelJitter, renderWidth, renderHeight)
        );
        Vector4f offset = new Vector4f(viewPosition).mul(jittered);

        float ndcDeltaX = offset.x / offset.w - unjittered.x / unjittered.w;
        float ndcDeltaY = offset.y / offset.w - unjittered.y / unjittered.w;
        // Screen space: +x right, +y down, so the NDC Y delta is negated.
        assertEquals(pixelJitter.x, ndcDeltaX * renderWidth * 0.5F, 1.0E-4F);
        assertEquals(pixelJitter.y, -ndcDeltaY * renderHeight * 0.5F, 1.0E-4F);
    }

    @Test
    void cutoutReactiveRadiusCoversJitterAndUpscaleFootprint() {
        assertEquals(0, MetalFxMath.cutoutReactiveRadius(1.0F, new Vector2f()));
        assertEquals(1, MetalFxMath.cutoutReactiveRadius(
                0.67F,
                new Vector2f(0.0F, -1.0F / 6.0F)
        ));
        assertEquals(2, MetalFxMath.cutoutReactiveRadius(
                0.5F,
                new Vector2f(0.5F, -0.5F)
        ));
    }

    @Test
    void invalidCutoutReactiveFootprintFailsClosed() {
        assertEquals(3, MetalFxMath.cutoutReactiveRadius(
                Float.NaN,
                new Vector2f()
        ));
        assertEquals(3, MetalFxMath.cutoutReactiveRadius(
                0.67F,
                new Vector2f(Float.NaN, 0.0F)
        ));
    }

    @Test
    void perspectiveProjectionProvidesTheWorldVerticalFieldOfView() {
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(70.0D), 16.0F / 9.0F, 0.05F, 1000.0F
        );
        assertEquals(70.0F, MetalFxMath.verticalFieldOfViewDegrees(projection, 55.0F), 1.0E-4F);
    }

    @Test
    void invalidProjectionUsesTheFieldOfViewFallback() {
        Matrix4f invalid = new Matrix4f().m11(Float.NaN);
        assertEquals(55.0F, MetalFxMath.verticalFieldOfViewDegrees(invalid, 55.0F), 1.0E-6F);
    }

    @Test
    void staleNarrowProjectionUsesTheFieldOfViewFallback() {
        Matrix4f stale = new Matrix4f().m11(13.5F);
        assertEquals(55.0F, MetalFxMath.verticalFieldOfViewDegrees(stale, 55.0F), 1.0E-6F);
    }

    @Test
    void staticCameraProducesZeroMotion() {
        Matrix4f identity = new Matrix4f();
        Vector2f motion = MetalFxMath.reconstructMotion(0.5F, 100.0F, 50.0F, 400, 200, identity, identity, identity);
        assertEquals(0.0F, motion.x, 1.0E-5F);
        assertEquals(0.0F, motion.y, 1.0E-5F);
    }

    @Test
    void cameraTranslationProducesCurrentToPreviousPixels() {
        Matrix4f inverseCurrent = new Matrix4f();
        Matrix4f previous = new Matrix4f().translate(0.1F, 0.0F, 0.0F);
        Vector2f motion = MetalFxMath.reconstructMotion(0.5F, 100.0F, 50.0F, 400, 200, new Matrix4f(), inverseCurrent, previous);
        assertEquals(20.0F, motion.x, 1.0E-4F);
        assertEquals(0.0F, motion.y, 1.0E-4F);
    }

    @Test
    void cameraTranslationUsesTopLeftScreenCoordinatesForVerticalMotion() {
        Matrix4f inverseCurrent = new Matrix4f();
        Matrix4f previous = new Matrix4f().translate(0.0F, 0.1F, 0.0F);
        Vector2f motion = MetalFxMath.reconstructMotion(0.5F, 100.0F, 50.0F, 400, 200, new Matrix4f(), inverseCurrent, previous);
        assertEquals(0.0F, motion.x, 1.0E-4F);
        assertEquals(-10.0F, motion.y, 1.0E-4F);
    }

    @Test
    void fixedCameraAndMovingObjectProducesObjectMotion() {
        MetalMotionContract.VertexMotion motion = MetalMotionContract.projectVertex(
                new Vector4f(0.0F, 0.0F, 0.0F, 1.0F),
                new Matrix4f(),
                new Matrix4f(),
                new Matrix4f().translate(0.25F, 0.0F, 0.0F),
                new Matrix4f(),
                new Matrix4f()
        );
        assertTrue(motion.valid());
        assertEquals(-0.25F, motion.motionNdc().x, 1.0E-5F);
        assertEquals(0.0F, motion.motionNdc().y, 1.0E-5F);
    }

    @Test
    void movingCameraAndStaticObjectUsesTheSameContract() {
        MetalMotionContract.VertexMotion motion = MetalMotionContract.projectVertex(
                new Vector4f(0.0F, 0.0F, 0.0F, 1.0F),
                new Matrix4f(),
                new Matrix4f(),
                new Matrix4f(),
                new Matrix4f(),
                new Matrix4f().translate(0.25F, 0.0F, 0.0F)
        );
        assertTrue(motion.valid());
        assertEquals(0.25F, motion.motionNdc().x, 1.0E-5F);
        assertEquals(0.0F, motion.motionNdc().y, 1.0E-5F);
    }

    @Test
    void jitterOnlyChangesRasterClipNotMotion() {
        MetalMotionContract.VertexMotion unjittered = MetalMotionContract.projectVertex(
                new Vector4f(0.1F, 0.0F, -1.0F, 1.0F),
                new Matrix4f(),
                new Matrix4f(),
                new Matrix4f(),
                new Matrix4f(),
                new Matrix4f()
        );
        MetalMotionContract.VertexMotion jittered = MetalMotionContract.projectVertex(
                new Vector4f(0.1F, 0.0F, -1.0F, 1.0F),
                new Matrix4f().m20(0.25F).m21(-0.125F),
                new Matrix4f(),
                new Matrix4f(),
                new Matrix4f(),
                new Matrix4f()
        );
        assertTrue(unjittered.valid() && jittered.valid());
        assertEquals(0.0F, jittered.motionNdc().x, 1.0E-5F);
        assertEquals(0.0F, jittered.motionNdc().y, 1.0E-5F);
        assertTrue(jittered.currentRasterClip().x != unjittered.currentRasterClip().x);
    }

    @Test
    void nearPlaneCrossingAndNonFiniteMotionAreInvalid() {
        MetalMotionContract.VertexMotion nearPlane = MetalMotionContract.projectVertex(
                new Vector4f(0.0F, 0.0F, 0.0F, 1.0F),
                new Matrix4f(),
                new Matrix4f(),
                new Matrix4f(),
                new Matrix4f(),
                new Matrix4f().m33(-1.0F)
        );
        MetalMotionContract.VertexMotion nonFinite = MetalMotionContract.projectVertex(
                new Vector4f(Float.NaN, 0.0F, 0.0F, 1.0F),
                new Matrix4f(), new Matrix4f(), new Matrix4f(), new Matrix4f(), new Matrix4f()
        );
        assertFalse(nearPlane.valid());
        assertFalse(nonFinite.valid());
    }

    @Test
    void motionScaleUsesInputResolution() {
        Vector2f scale = MetalMotionContract.motionVectorScale(1280, 720);
        assertEquals(640.0F, scale.x, 1.0E-6F);
        assertEquals(360.0F, scale.y, 1.0E-6F);
    }

    @Test
    void mergePrefersValidObjectMotionOverCameraFallback() {
        MetalMotionContract.MergedMotion merged = MetalMotionContract.merge(
                new Vector2f(0.10F, -0.20F),
                new Vector2f(-0.35F, 0.45F),
                true,
                false
        );
        assertEquals(-0.35F, merged.motionNdc().x, 1.0E-6F);
        assertEquals(0.45F, merged.motionNdc().y, 1.0E-6F);
        assertTrue(merged.objectMotionUsed());
        assertFalse(merged.historyRejected());
    }

    @Test
    void mergeFallsBackToCameraMotionWhenObjectProducerDidNotWrite() {
        MetalMotionContract.MergedMotion merged = MetalMotionContract.merge(
                new Vector2f(0.10F, -0.20F),
                new Vector2f(),
                false,
                false
        );
        assertEquals(0.10F, merged.motionNdc().x, 1.0E-6F);
        assertEquals(-0.20F, merged.motionNdc().y, 1.0E-6F);
        assertFalse(merged.objectMotionUsed());
        assertFalse(merged.historyRejected());
    }

    @Test
    void mergeRejectsHistoryForDisocclusionWithoutReplacingValidMotion() {
        MetalMotionContract.MergedMotion merged = MetalMotionContract.merge(
                new Vector2f(0.10F, -0.20F),
                new Vector2f(-0.35F, 0.45F),
                true,
                true
        );
        assertEquals(-0.35F, merged.motionNdc().x, 1.0E-6F);
        assertEquals(0.45F, merged.motionNdc().y, 1.0E-6F);
        assertTrue(merged.objectMotionUsed());
        assertTrue(merged.historyRejected());
    }

    @Test
    void mergeRejectsNonFiniteObjectMotionAndUsesCameraFallback() {
        MetalMotionContract.MergedMotion merged = MetalMotionContract.merge(
                new Vector2f(0.10F, -0.20F),
                new Vector2f(Float.NaN, Float.POSITIVE_INFINITY),
                true,
                false
        );
        assertEquals(0.10F, merged.motionNdc().x, 1.0E-6F);
        assertEquals(-0.20F, merged.motionNdc().y, 1.0E-6F);
        assertFalse(merged.objectMotionUsed());
        assertTrue(merged.historyRejected());
    }

    @Test
    void mergeSanitizesNonFiniteCameraMotionToZeroAndRejectsHistory() {
        MetalMotionContract.MergedMotion merged = MetalMotionContract.merge(
                new Vector2f(Float.NaN, 0.0F),
                new Vector2f(),
                false,
                false
        );
        assertEquals(0.0F, merged.motionNdc().x, 1.0E-6F);
        assertEquals(0.0F, merged.motionNdc().y, 1.0E-6F);
        assertFalse(merged.objectMotionUsed());
        assertTrue(merged.historyRejected());
    }

    @Test
    void mergeRejectsObjectMotionBeyondReasonableRange() {
        MetalMotionContract.MergedMotion merged = MetalMotionContract.merge(
                new Vector2f(-0.10F, 0.20F),
                new Vector2f(MetalMotionContract.MAX_REASONABLE_NDC_MOTION + 1.0F, 0.0F),
                true,
                false
        );
        assertEquals(-0.10F, merged.motionNdc().x, 1.0E-6F);
        assertEquals(0.20F, merged.motionNdc().y, 1.0E-6F);
        assertFalse(merged.objectMotionUsed());
        assertTrue(merged.historyRejected());
    }

    @Test
    void previousStateAdvancesOnlyAfterSuccessfulFrameCommit() {
        MetalMotionStateStore store = new MetalMotionStateStore();
        MetalMotionStateStore.ObjectKey key = new MetalMotionStateStore.ObjectKey(7L, 1L);
        Matrix4f first = new Matrix4f().translate(1.0F, 0.0F, 0.0F);
        store.beginFrame();
        store.observe(key, first);
        assertFalse(store.hasPrevious(key));
        store.discardFrame();
        assertFalse(store.hasPrevious(key));

        store.beginFrame();
        store.observe(key, first);
        store.commitSubmittedFrame();
        assertTrue(store.hasPrevious(key));
        assertEquals(1.0F, store.previous(key).m30(), 1.0E-6F);
    }

    @Test
    void packetSideObservationOutsideFrameIsSkippedWithoutPollutingHistory() {
        MetalMotionStateStore store = new MetalMotionStateStore();
        MetalMotionStateStore.ObjectKey key = new MetalMotionStateStore.ObjectKey(9L, 1L);
        Matrix4f submitted = new Matrix4f().translate(1.0F, 0.0F, 0.0F);
        Matrix4f packetSide = new Matrix4f().translate(99.0F, 0.0F, 0.0F);

        store.beginFrame();
        assertTrue(store.observeIfFrameOpen(key, submitted));
        store.commitSubmittedFrame();

        assertFalse(store.observeIfFrameOpen(key, packetSide));
        assertEquals(1.0F, store.previous(key).m30(), 1.0E-6F);

        store.beginFrame();
        assertEquals(1.0F, store.previous(key).m30(), 1.0E-6F);
        store.discardFrame();
        assertEquals(1.0F, store.previous(key).m30(), 1.0E-6F);
    }

    @Test
    void cameraJitterDoesNotBecomeMotion() {
        Matrix4f unjittered = new Matrix4f();
        Matrix4f jitteredInverse = new Matrix4f().translate(0.25F, -0.125F, 0.0F);
        Vector2f motion = MetalFxMath.reconstructMotion(
                0.5F, 100.0F, 50.0F, 400, 200, unjittered, jitteredInverse, unjittered
        );
        assertEquals(0.0F, motion.x, 1.0E-5F);
        assertEquals(0.0F, motion.y, 1.0E-5F);
    }

    @Test
    void pureCameraRotationProducesDirectionalMotion() {
        Matrix4f identity = new Matrix4f();
        Matrix4f previous = new Matrix4f().rotateZ(0.1F);
        Vector2f motion = MetalFxMath.reconstructMotion(
                0.5F, 100.0F, 50.0F, 400, 200, identity, identity, previous
        );
        assertTrue(motion.x < 0.0F);
        assertTrue(motion.y > 0.0F);
    }

    @Test
    void invalidMatrixIsRejected() {
        Matrix4f invalid = new Matrix4f().m00(Float.NaN);
        assertFalse(MetalFxMath.isFinite(invalid));
        assertTrue(MetalFxMath.isFinite(new Matrix4f()));
    }

    @Test
    void scaleRulesKeepNativeResolutionExact() {
        assertEquals(1920, MetalFxConfig.scaledDimension(1920, 1.0F));
        assertEquals(1286, MetalFxConfig.scaledDimension(1920, 0.67F));
        assertEquals(960, MetalFxConfig.scaledDimension(1920, 0.5F));
        assertEquals(867, MetalFxConfig.scaledDimension(1734, 0.5F));
        assertEquals(8, MetalFxConfig.phaseCount(1.0F));
        assertEquals(18, MetalFxConfig.phaseCount(0.67F));
        assertEquals(32, MetalFxConfig.phaseCount(0.5F));
        float frameGenerationScale = MetalFxConfig.frameGenerationOutputScale(1708, 1440);
        assertEquals(1440, MetalFxConfig.scaledDimension(1708, frameGenerationScale));
        assertEquals(808, MetalFxConfig.scaledDimension(960, frameGenerationScale));
        assertEquals(964, MetalFxConfig.scaledDimension(1708, 0.67F * frameGenerationScale));
        assertEquals(542, MetalFxConfig.scaledDimension(960, 0.67F * frameGenerationScale));
        assertEquals(1.0F, MetalFxConfig.frameGenerationOutputScale(3024, 0));
        assertEquals(0, MetalFxConfig.parseFrameGenerationOutputWidth("native", 1280));
        assertEquals(0, MetalFxConfig.parseFrameGenerationOutputWidth("display", 1280));
        assertEquals(0, MetalFxConfig.parseFrameGenerationOutputWidth("0", 1280));
        assertEquals(3024, MetalFxConfig.parseFrameGenerationOutputWidth("3024", 1280));
        assertEquals(1280, MetalFxConfig.parseFrameGenerationOutputWidth("invalid", 1280));
        assertEquals(0.0F, MetalFxConfig.textureLodBias(1708, 1708), 1.0E-6F);
        assertEquals(-1.825F, MetalFxConfig.textureLodBias(964, 1708), 1.0E-3F);
    }

    @Test
    void sodiumScaleOptionsUseOnlySupportedRenderRatios() {
        assertEquals(MetalFxConfig.Scale.NATIVE, MetalFxConfig.Scale.fromPercent(100));
        assertEquals(MetalFxConfig.Scale.QUALITY, MetalFxConfig.Scale.fromPercent(67));
        assertEquals(MetalFxConfig.Scale.HALF, MetalFxConfig.Scale.fromPercent(50));
        assertEquals(MetalFxConfig.Scale.QUALITY, MetalFxConfig.Scale.fromRatio(0.67F));
    }

    @Test
    void configurationOverridesHaveStableFallbacks() {
        assertEquals(MetalFxConfig.Mode.TEMPORAL,
                MetalFxConfig.parseMode(" temporal ", MetalFxConfig.Mode.OFF));
        assertEquals(MetalFxConfig.Mode.SPATIAL,
                MetalFxConfig.parseMode("unknown", MetalFxConfig.Mode.SPATIAL));
        assertTrue(MetalFxConfig.parseBoolean("true", false));
        assertFalse(MetalFxConfig.parseBoolean("unknown", false));
        assertEquals(0.67F, MetalFxConfig.parseScale("invalid", 0.67F), 1.0E-6F);
    }

    @Test
    void sceneCutMathOnlyTriggersForLargeOrInvalidMotion() {
        assertFalse(MetalFxMath.exceedsSceneCutDistance(0.0, 0.0, 0.0, 1.0, 2.0, 2.0, 32.0));
        assertTrue(MetalFxMath.exceedsSceneCutDistance(0.0, 0.0, 0.0, 33.0, 0.0, 0.0, 32.0));
        assertTrue(MetalFxMath.exceedsSceneCutDistance(0.0, 0.0, 0.0, Double.NaN, 0.0, 0.0, 32.0));
    }

    @Test
    void projectionDifferenceUsesAStableEpsilon() {
        Matrix4f first = new Matrix4f();
        Matrix4f second = new Matrix4f().m00(1.0005F);
        Matrix4f changed = new Matrix4f().m00(1.002F);
        assertTrue(MetalFxMath.maxAbsDifference(first, second) < 1.0E-3F);
        assertTrue(MetalFxMath.maxAbsDifference(first, changed) > 1.0E-3F);
    }

    @Test
    void unsupportedModesFallBackWithoutChangingTheRequestedOffMode() {
        assertEquals(MetalFxConfig.Mode.TEMPORAL,
                MetalFxManager.selectMode(MetalFxConfig.Mode.AUTO, true, true));
        assertEquals(MetalFxConfig.Mode.SPATIAL,
                MetalFxManager.selectMode(MetalFxConfig.Mode.TEMPORAL, true, false));
        assertEquals(MetalFxConfig.Mode.OFF,
                MetalFxManager.selectMode(MetalFxConfig.Mode.SPATIAL, false, false));
        assertEquals(MetalFxConfig.Mode.OFF,
                MetalFxManager.selectMode(MetalFxConfig.Mode.OFF, true, true));
    }
}
