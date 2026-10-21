package com.metallum.client.metal.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class MetalFirstPersonProjectionHistoryTest {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation("hand_history/entity").withVertexShader("core/entity").withFragmentShader("core/entity")
            .withVertexBinding(0, DefaultVertexFormat.ENTITY).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();
    private static final MetalEntityMotionCapture.Sample MAIN = hand(11, -1);
    private static final MetalEntityMotionCapture.Sample OFF = hand(12, -2);
    private static final Vector4f POINT = new Vector4f(.2F, .1F, -1, 1);

    @AfterEach
    void reset() {
        MetalPreviousVertexHistory.discardFrame();
        MetalPreviousVertexHistory.reset();
        MetalExactMotionCoverage.reset();
    }

    @Test
    void cameraLockedHandHasZeroMotionWhenTheWorldCameraTurns() {
        Matrix4f projection = hud(90), oldView = new Matrix4f().rotateY(.2F), view = new Matrix4f().rotateY(-.3F);
        Vector4f oldPosition = new Vector4f(POINT).mul(new Matrix4f(oldView).invert());
        Vector4f position = new Vector4f(POINT).mul(new Matrix4f(view).invert());
        MetalPreviousVertexHistory.beginFrame();
        stage(MAIN, oldPosition, projection, oldView);
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();
        var token = stage(MAIN, position, projection, view);
        var plan = MetalPreviousVertexReplay.handTransforms(token);
        assertNotNull(plan);
        Vector4f raster = new Vector4f(position).mul(projection.mul(view, new Matrix4f()));
        Vector4f current = raster.mul(plan.currentUnjitteredFromRaster(), new Vector4f());
        Vector4f previous = new Vector4f(oldPosition).mul(plan.previousFromPosition());
        assertNdcEquals(current, previous);

        Vector4f wrongWorld = new Vector4f(oldPosition).mul(hud(70).mul(oldView));
        assertTrue(Math.abs(wrongWorld.x/wrongWorld.w - current.x/current.w) * 640 > 1,
                "The old world projection would invent visible hand motion");
        assertTrue(plan.currentUnjitteredFromRaster().equals(new Matrix4f().scaling(1, -1, 1), 0),
                "Unjittered HUD rendering must not subtract world-camera jitter");
    }

    @Test
    void changingHudFovAndLayeringRetainsThePreviousDrawsActualTransform() {
        Matrix4f oldProjection = hud(55), oldView = new Matrix4f().translate(.05F, -.1F, 0).rotateZ(.12F);
        MetalPreviousVertexHistory.beginFrame();
        stage(MAIN, POINT, oldProjection, oldView);
        Matrix4f expected = MetalMotionContract.minecraftToMetalFxClip(new Matrix4f(oldProjection).mul(oldView));
        // Caller reuse/mutation must not mutate pending history.
        oldProjection.identity(); oldView.zero();
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();
        var token = stage(MAIN, new Vector4f(.3F, .2F, -1, 1), hud(105), new Matrix4f().rotateZ(-.2F));
        var plan = MetalPreviousVertexReplay.handTransforms(token);
        assertNotNull(plan);
        assertTrue(plan.previousFromPosition().equals(expected, 1E-6F));
        assertTrue(plan.currentProjection().equals(hud(105), 1E-6F));
        plan.previousFromPosition().zero(); plan.currentProjection().zero();
        assertTrue(MetalPreviousVertexReplay.handTransforms(token).previousFromPosition().equals(expected, 1E-6F));
    }

    @Test
    void rejectedSourceFrameAdvancesNeitherMatricesNorVertices() {
        MetalPreviousVertexHistory.beginFrame();
        stage(MAIN, POINT, hud(60), new Matrix4f().translate(.1F, 0, 0));
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();
        stage(MAIN, new Vector4f(9, 9, 9, 1), hud(120), new Matrix4f().rotateX(1));
        MetalPreviousVertexHistory.discardFrame();
        MetalPreviousVertexHistory.beginFrame();
        var token = stage(MAIN, POINT, hud(80), new Matrix4f());
        assertArrayEquals(new float[]{POINT.x, POINT.y, POINT.z}, MetalPreviousVertexHistory.matchedPreviousPositions(token));
        assertTrue(MetalPreviousVertexReplay.handTransforms(token).previousFromPosition().equals(
                MetalMotionContract.minecraftToMetalFxClip(hud(60).translate(.1F, 0, 0)), 1E-6F));
    }

    @Test
    void bothHandsAndMaterialDrawsKeepIndependentTransforms() {
        MetalPreviousVertexHistory.beginFrame();
        stage(MAIN, POINT, hud(60), new Matrix4f());
        stage(MAIN, POINT, hud(60), new Matrix4f().translate(.02F, .03F, 0));
        stage(OFF, POINT, hud(95), new Matrix4f().rotateZ(.1F));
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();
        var main = stage(MAIN, POINT, hud(65), new Matrix4f());
        var layer = stage(MAIN, POINT, hud(65), new Matrix4f());
        var off = stage(OFF, POINT, hud(90), new Matrix4f());
        assertTrue(MetalPreviousVertexReplay.handTransforms(main).previousFromPosition().equals(
                MetalMotionContract.minecraftToMetalFxClip(hud(60)), 1E-6F));
        assertTrue(MetalPreviousVertexReplay.handTransforms(layer).previousFromPosition().equals(
                MetalMotionContract.minecraftToMetalFxClip(hud(60).translate(.02F, .03F, 0)), 1E-6F));
        assertTrue(MetalPreviousVertexReplay.handTransforms(off).previousFromPosition().equals(
                MetalMotionContract.minecraftToMetalFxClip(hud(95).rotateZ(.1F)), 1E-6F));
    }

    @Test
    void oneMissingMaterialTransformRejectsTheEntireHandManifest() {
        MetalPreviousVertexHistory.beginFrame();
        stage(MAIN, POINT, hud(70), new Matrix4f());
        stage(MAIN, POINT, hud(70), new Matrix4f());
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();
        var first = stage(MAIN, POINT, hud(70), new Matrix4f());
        var second = stage(MAIN, POINT, null, null);
        assertNull(MetalPreviousVertexReplay.handTransforms(first));
        assertNull(MetalPreviousVertexReplay.handTransforms(second));
        MetalPreviousVertexHistory.stageDrawTransform(second, new MetalPreviousVertexHistory.DrawTransform(hud(70), new Matrix4f()));
        assertNotNull(MetalPreviousVertexReplay.handTransforms(first));
        assertNotNull(MetalPreviousVertexReplay.handTransforms(second));
    }

    @Test
    void vertexHistoryWithoutPreviousProjectionDoesNotAdmitTheHand() {
        MetalPreviousVertexHistory.beginFrame();
        stage(MAIN, POINT, null, null);
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();
        var current = stage(MAIN, POINT, hud(70), new Matrix4f());
        assertNotNull(MetalPreviousVertexHistory.matchedPreviousPositions(current));
        assertNull(MetalPreviousVertexReplay.handTransforms(current));
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();
        assertNotNull(MetalPreviousVertexReplay.handTransforms(stage(MAIN, POINT, hud(75), new Matrix4f())));
    }

    @Test
    void firstAppearanceAndReturningAfterAnAbsentSubmittedFrameMustWarmUp() {
        MetalPreviousVertexHistory.beginFrame();
        assertNull(MetalPreviousVertexReplay.handTransforms(stage(MAIN, POINT, hud(70), new Matrix4f())));
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();
        assertNull(MetalPreviousVertexReplay.handTransforms(stage(MAIN, POINT, hud(70), new Matrix4f())));
    }

    @Test
    void resetDuringAnOpenFrameClearsOldHistoryButPrimesTheNextFrame() {
        MetalPreviousVertexHistory.beginFrame();
        stage(MAIN, POINT, hud(70), new Matrix4f());
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();
        MetalPreviousVertexHistory.reset(); // resource reload / resize / camera discontinuity
        assertNull(MetalPreviousVertexReplay.handTransforms(stage(MAIN, POINT, hud(90), new Matrix4f())));
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();
        var next = stage(MAIN, POINT, hud(95), new Matrix4f());
        assertTrue(MetalPreviousVertexReplay.handTransforms(next).previousFromPosition().equals(
                MetalMotionContract.minecraftToMetalFxClip(hud(90)), 1E-6F));
    }

    @Test
    void changedItemGenerationCannotReuseTheOldProjection() {
        MetalPreviousVertexHistory.beginFrame();
        stage(MAIN, POINT, hud(70), new Matrix4f());
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();
        assertNull(MetalPreviousVertexReplay.handTransforms(stage(hand(11, -3), POINT, hud(70), new Matrix4f())));
    }

    @Test
    void nonFiniteMatrixAndChangedDrawManifestFailClosed() {
        MetalPreviousVertexHistory.beginFrame();
        stage(MAIN, POINT, hud(70), new Matrix4f());
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();
        var token = stage(MAIN, POINT, hud(70).m00(Float.NaN), new Matrix4f());
        assertNull(MetalPreviousVertexReplay.handTransforms(token));
        MetalPreviousVertexHistory.stageDrawTransform(token, new MetalPreviousVertexHistory.DrawTransform(hud(70), new Matrix4f()));
        assertNotNull(MetalPreviousVertexReplay.handTransforms(token));
        stage(MAIN, POINT, hud(70), new Matrix4f());
        assertNull(MetalPreviousVertexReplay.handTransforms(token));
    }

    @Test
    void firstAppearanceRetainsCurrentProjectionButCannotInventPreviousVisibility() {
        MetalPreviousVertexHistory.beginFrame();
        Matrix4f projection=hud(92);
        var token=stage(MAIN,POINT,projection,new Matrix4f().rotateX(.2F));
        assertNull(MetalPreviousVertexReplay.handTransforms(token));
        var current=MetalPreviousVertexReplay.currentOnlyTransforms(token,true);
        assertNotNull(current);
        assertTrue(current.currentProjection().equals(projection,0));
        assertTrue(current.currentUnjitteredFromRaster().equals(
                MetalMotionContract.minecraftToMetalFxClip(new Matrix4f()),0));
        assertEquals(0,new Vector4f(POINT).mul(current.previousFromPosition()).w,0);
        assertEquals(1,MetalPreviousVertexHistory.currentVertexCount(token));
        MetalPreviousVertexHistory.discardFrame();
        assertNull(MetalPreviousVertexReplay.currentOnlyTransforms(token,true));
        assertEquals(0,MetalPreviousVertexHistory.currentVertexCount(token));
    }

    @Test
    void currentOwnershipStillRequiresFiniteSourceUniformsAndCurrentGeometry() {
        MetalPreviousVertexHistory.beginFrame();
        var token=stage(MAIN,POINT,hud(70).m00(Float.NaN),new Matrix4f());
        assertNull(MetalPreviousVertexReplay.currentOnlyTransforms(token,true));
        MetalPreviousVertexHistory.stageDrawTransform(token,new MetalPreviousVertexHistory.DrawTransform(hud(70),new Matrix4f()));
        assertNotNull(MetalPreviousVertexReplay.currentOnlyTransforms(token,true));
        var uncaptured=MetalPreviousVertexHistory.reserveDraw(MAIN,PIPELINE);
        assertNull(MetalPreviousVertexReplay.currentOnlyTransforms(uncaptured,true));
    }

    @Test
    void completeHistoryStillNeedsEveryDrawToActuallyEncode() {
        MetalPreviousVertexHistory.beginFrame();
        stage(MAIN, POINT, hud(70), new Matrix4f()); stage(MAIN, POINT, hud(70), new Matrix4f());
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();
        var first = stage(MAIN, POINT, hud(70), new Matrix4f());
        var second = stage(MAIN, POINT, hud(70), new Matrix4f());
        MetalExactMotionCoverage.require(MAIN);
        assertNotNull(MetalPreviousVertexReplay.handTransforms(first));
        assertNotNull(MetalPreviousVertexReplay.handTransforms(second));
        assertFalse(MetalExactMotionCoverage.complete());
        MetalExactMotionCoverage.recordExactEncoded(first);
        assertFalse(MetalExactMotionCoverage.complete());
        MetalExactMotionCoverage.recordExactEncoded(second);
        assertTrue(MetalExactMotionCoverage.complete());
    }

    static Matrix4f hud(float fov) {
        return new Matrix4f().perspective((float)Math.toRadians(fov), 1.5F, 100, .05F, true);
    }

    private static MetalEntityMotionCapture.Sample hand(long id, long generation) {
        return new MetalEntityMotionCapture.Sample(id, generation, new Matrix4f(), new Matrix4f(),
                FrameSynthesisContract.ProducerDomain.FIRST_PERSON);
    }

    private static MetalPreviousVertexHistory.DrawToken stage(MetalEntityMotionCapture.Sample hand, Vector4f position,
                                                             Matrix4f projection, Matrix4f modelView) {
        var token = MetalPreviousVertexHistory.reserveDraw(hand, PIPELINE);
        var format = PIPELINE.getVertexFormatBinding(0);
        MetalPreviousVertexHistory.stageSnapshot(token, new MetalPreviousVertexHistory.Signature(
                PIPELINE.getLocation().toString(), format.getElements(), format.getVertexSize(),
                PrimitiveTopology.TRIANGLES, 1, 3), new float[]{position.x, position.y, position.z});
        if (projection != null && modelView != null) {
            MetalPreviousVertexHistory.stageDrawTransform(token, new MetalPreviousVertexHistory.DrawTransform(projection, modelView));
        }
        return token;
    }

    private static void assertNdcEquals(Vector4f current, Vector4f previous) {
        assertEquals(current.x/current.w, previous.x/previous.w, 1E-6F);
        assertEquals(current.y/current.w, previous.y/previous.w, 1E-6F);
    }
}
