package com.metallum.client.metal.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class MetalParticleVertexAlphaHistoryTest {
    private final RenderPipeline source = RenderPipeline.builder().withLocation("particle_vertex_alpha_history")
            .withVertexShader("core/particle").withFragmentShader("core/particle")
            .withVertexBinding(0, DefaultVertexFormat.PARTICLE).withPrimitiveTopology(PrimitiveTopology.QUADS).build();
    private final MetalEntityMotionCapture.Sample sample = new MetalEntityMotionCapture.Sample(72911, -31,
            new Matrix4f(), new Matrix4f(), FrameSynthesisContract.ProducerDomain.PARTICLES_WEATHER);

    @AfterEach void reset() {MetalPreviousVertexHistory.discardFrame(); MetalPreviousVertexHistory.reset();}

    @Test void acceptedZeroAlphaSurvivesSourceSliceOverwriteAndFreeWithoutBorrowingCurrentAlpha() {
        MetalPreviousVertexHistory.beginFrame(); stage(List.of(11L), false, 0, true);
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame(); var current = stage(List.of(11L), false, 255, true);
        assertTrue(MetalPreviousVertexHistory.matchingSurfaceManifest(current));
        assertNotNull(MetalPreviousVertexHistory.matchedPreviousPositions(current));
        assertEquals(1, MetalPreviousVertexHistory.matchedPreviousSourceUniforms(current).colorModulator().w);
        assertNull(MetalPreviousVertexReplay.previousPositionsForSource(source, current),
                "Source Color.a=0 must remain a rejection after its real allocations were overwritten and closed");
        assertNotNull(MetalPreviousVertexReplay.currentOnlyTransforms(current, false));
    }

    @Test void discardDoesNotAdvanceButAcceptedCurrentOnlyCanSeedTheNextVisibleFrame() {
        MetalPreviousVertexHistory.beginFrame(); stage(List.of(11L), false, 0, true);
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame(); var failed = stage(List.of(11L), false, 255, true);
        boolean firstRejected = MetalPreviousVertexReplay.previousPositionsForSource(source, failed) == null;
        MetalPreviousVertexHistory.discardFrame();
        MetalPreviousVertexHistory.beginFrame(); var accepted = stage(List.of(11L), false, 255, true);
        boolean discardedDidNotAdvance = MetalPreviousVertexReplay.previousPositionsForSource(source, accepted) == null;
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame(); var next = stage(List.of(11L), false, 255, true);
        assertNotNull(MetalPreviousVertexReplay.previousPositionsForSource(source, next));
        assertTrue(firstRejected); assertTrue(discardedDidNotAdvance);
    }

    @Test void continuouslyVisibleRealSlicesRetainExactPositions() {
        MetalPreviousVertexHistory.beginFrame(); stage(List.of(11L), false, 255, true);
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame(); var next = stage(List.of(11L), false, 255, true);
        assertArrayEquals(MetalPreviousVertexHistory.matchedPreviousPositions(next),
                MetalPreviousVertexReplay.previousPositionsForSource(source, next));
    }

    @Test void mergedSwappedIdsCannotLetZeroAUseVisibleBsSourceProof() {
        previousTwo(0);
        MetalPreviousVertexHistory.beginFrame(); var merged = stage(List.of(22L, 11L), true, 255, true);
        var raw = MetalPreviousVertexHistory.matchedPreviousPositions(merged); assertNotNull(raw);
        assertEquals(.25F, raw[0]); assertEquals(-.75F, raw[12]);
        assertTrue(MetalPreviousVertexHistory.matchingSurfaceManifest(merged));
        assertNull(MetalPreviousVertexReplay.previousPositionsForSource(source, merged),
                "B is the first remapped reference; A's accepted zero alpha must still reject the merged draw");
    }

    @Test void separateSwappedIdsPreserveBButCannotGiveItsHistoryToA() {
        previousTwo(0);
        MetalPreviousVertexHistory.beginFrame();
        var b = stage(List.of(22L), true, 255, true); var a = stage(List.of(11L), true, 255, true);
        assertNotNull(MetalPreviousVertexReplay.previousPositionsForSource(source, b));
        assertNull(MetalPreviousVertexReplay.previousPositionsForSource(source, a));
    }

    @Test void mergingPositiveOldBatchesPreservesTheirIdRemappedGeometry() {
        previousTwo(255);
        MetalPreviousVertexHistory.beginFrame(); var current = stage(List.of(22L, 11L), true, 255, true);
        var old = MetalPreviousVertexReplay.previousPositionsForSource(source, current); assertNotNull(old);
        assertEquals(.25F, old[0]); assertEquals(-.75F, old[12]);
    }

    @Test void currentVisibleSlicesCannotBackfillMissingAcceptedAlphaEvidence() {
        MetalPreviousVertexHistory.beginFrame(); stage(List.of(11L), false, 255, false);
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame(); var current = stage(List.of(11L), false, 255, true);
        assertNotNull(MetalPreviousVertexHistory.matchedPreviousPositions(current));
        assertNull(MetalPreviousVertexReplay.previousPositionsForSource(source, current));
    }

    private void previousTwo(int alpha) {
        MetalPreviousVertexHistory.beginFrame();
        stage(List.of(11L), true, alpha, true); stage(List.of(22L), true, 255, true);
        MetalPreviousVertexHistory.commitSubmittedFrame();
    }

    private MetalPreviousVertexHistory.DrawToken stage(List<Long> ids, boolean pair, int alpha, boolean realCapture) {
        var token = MetalPreviousVertexHistory.reserveDraw(sample, source); var format = DefaultVertexFormat.PARTICLE;
        var mesh = MetalParticleAlphaFixtures.mesh(ids, pair, alpha);
        if (realCapture) MetalParticleAlphaFixtures.capture(token, format, PrimitiveTopology.QUADS, mesh, ids.size()*4, ids.size()*6);
        else MetalPreviousVertexHistory.stageSnapshot(token, new MetalPreviousVertexHistory.Signature(token.pipelineKey(),
                format.getElements(), format.getVertexSize(), PrimitiveTopology.QUADS, ids.size()*4, ids.size()*6),
                MetalPreviousVertexHistory.extractPositions(format, List.of(mesh), ids.size()*4));
        MetalPreviousVertexHistory.stageParticleIdentities(token, ids);
        MetalPreviousVertexHistory.stageDrawTransform(token, new MetalPreviousVertexHistory.DrawTransform(new Matrix4f(), new Matrix4f()));
        MetalPreviousVertexHistory.stageSourceUniforms(token, MetalSourceUniforms.decode(MetalPreviousVisibilityHistoryTest.dynamic(1), null));
        MetalPreviousVertexHistory.stageSurface(token, new MetalPreviousVertexHistory.SurfaceIdentity(source, Map.of(), true));
        return token;
    }
}
