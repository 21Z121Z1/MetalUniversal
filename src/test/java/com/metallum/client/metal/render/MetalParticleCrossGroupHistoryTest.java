package com.metallum.client.metal.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

final class MetalParticleCrossGroupHistoryTest {
    private static final MetalPreviousVertexHistory.DrawTransform OLD = new MetalPreviousVertexHistory.DrawTransform(
            new Matrix4f().scale(2), new Matrix4f().translate(.25F, .5F, .75F));
    private static final MetalPreviousVertexHistory.DrawTransform CURRENT = new MetalPreviousVertexHistory.DrawTransform(
            new Matrix4f().scale(3), new Matrix4f().translate(2, 3, 4));
    private final RenderPipeline pipeline = RenderPipeline.builder().withLocation("test/cross-group-particles")
            .withVertexShader("core/particle").withFragmentShader("core/particle")
            .withVertexBinding(0, DefaultVertexFormat.PARTICLE).withPrimitiveTopology(PrimitiveTopology.QUADS).build();
    private final MetalEntityMotionCapture.Sample sample = new MetalEntityMotionCapture.Sample(44101, -91,
            new Matrix4f(), new Matrix4f(), FrameSynthesisContract.ProducerDomain.PARTICLES_WEATHER);

    @AfterEach void reset() { MetalPreviousVertexHistory.discardFrame(); MetalPreviousVertexHistory.reset(); }

    @Test void mergedGroupsUseIdsAndTheSharedOldSourceProofInsteadOfDrawOrdinals() {
        previousTwo();
        MetalPreviousVertexHistory.beginFrame();
        var token = stage(new long[]{2, 1, 3}, new float[]{99, 99, 99}, CURRENT, true, 2, true);
        float[] old = MetalPreviousVertexHistory.matchedPreviousPositions(token);
        assertNotNull(old); assertEquals(22, old[0]); assertEquals(11, old[12]); assertTrue(Float.isNaN(old[24]));
        assertEquals(1, MetalPreviousVertexHistory.matchingManifestDrawCount(sample.objectId(), sample.generation()));
        assertEquals(OLD, MetalPreviousVertexHistory.matchedPreviousDrawTransform(token));
        assertEquals(1, MetalPreviousVertexHistory.matchedPreviousSourceUniforms(token).gameTime());
        assertTrue(MetalPreviousVertexHistory.matchingSurfaceManifest(token));
    }

    @Test void splitAndReorderedGroupsPreservePreviousGeometryForEachParticle() {
        MetalPreviousVertexHistory.beginFrame();
        stage(new long[]{1, 2}, new float[]{11, 22}, OLD, true, 1, true);
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();
        var b = stage(new long[]{2}, new float[]{99}, CURRENT, true, 2, true);
        var a = stage(new long[]{1}, new float[]{99}, CURRENT, true, 2, true);
        assertEquals(22, MetalPreviousVertexHistory.matchedPreviousPositions(b)[0]);
        assertEquals(11, MetalPreviousVertexHistory.matchedPreviousPositions(a)[0]);
        assertEquals(OLD, MetalPreviousVertexHistory.matchedPreviousDrawTransform(a));
        assertEquals(2, MetalPreviousVertexHistory.matchingManifestDrawCount(sample.objectId(), sample.generation()));
    }

    @Test void differentOldTransformsSurfacesOrSourceUniformsCannotShareOnePreviousDrawProof() {
        for (int mismatch = 0; mismatch < 3; mismatch++) {
            reset(); MetalPreviousVertexHistory.beginFrame();
            stage(new long[]{1}, new float[]{11}, OLD, true, 1, true);
            stage(new long[]{2}, new float[]{22}, mismatch == 0 ? CURRENT : OLD, mismatch != 1, mismatch == 2 ? 9 : 1, true);
            MetalPreviousVertexHistory.commitSubmittedFrame();
            MetalPreviousVertexHistory.beginFrame();
            var merged = stage(new long[]{1, 2}, new float[]{99, 99}, CURRENT, true, 2, true);
            assertNull(MetalPreviousVertexHistory.matchedPreviousPositions(merged));
            assertNull(MetalPreviousVertexHistory.matchedPreviousDrawTransform(merged));
            assertNull(MetalPreviousVertexHistory.matchedPreviousSourceUniforms(merged));
            assertFalse(MetalPreviousVertexHistory.matchingSurfaceManifest(merged));
        }
    }

    @Test void crossGroupCorrespondenceDoesNotWeakenWeatherOrOtherQuadManifests() {
        MetalPreviousVertexHistory.beginFrame();
        stage(new long[]{1}, new float[]{11}, OLD, true, 1, false);
        stage(new long[]{2}, new float[]{22}, OLD, true, 1, false);
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();
        var merged = stage(new long[]{1, 2}, new float[]{99, 99}, CURRENT, true, 2, false);
        assertNull(MetalPreviousVertexHistory.matchedPreviousPositions(merged));
        assertEquals(-1, MetalPreviousVertexHistory.matchingManifestDrawCount(sample.objectId(), sample.generation()));
    }

    @Test void cachedProofIsInvalidatedWhenLaterSourceMetadataChangesOrBecomesUnavailable() {
        previousTwo(); MetalPreviousVertexHistory.beginFrame();
        var merged = stage(new long[]{1, 2}, new float[]{99, 99}, CURRENT, true, 2, true);
        assertNotNull(MetalPreviousVertexHistory.matchedPreviousPositions(merged));
        MetalPreviousVertexHistory.stageSurface(merged, new MetalPreviousVertexHistory.SurfaceIdentity(pipeline, Map.of(), false));
        assertNull(MetalPreviousVertexHistory.matchedPreviousPositions(merged));
        MetalPreviousVertexHistory.stageSurface(merged, new MetalPreviousVertexHistory.SurfaceIdentity(pipeline, Map.of(), true));
        assertNotNull(MetalPreviousVertexHistory.matchedPreviousPositions(merged));
        MetalPreviousVertexHistory.stageSourceUniforms(merged, null);
        assertNull(MetalPreviousVertexHistory.matchedPreviousPositions(merged));
        MetalPreviousVertexHistory.stageSourceUniforms(merged, uniforms(CURRENT, 2));
        assertNotNull(MetalPreviousVertexHistory.matchedPreviousPositions(merged));
    }

    @Test void discardedRepartitionCannotReplaceTheLastAcceptedSourceGeometry() {
        previousTwo(); MetalPreviousVertexHistory.beginFrame();
        stage(new long[]{1, 2}, new float[]{91, 92}, CURRENT, true, 2, true);
        MetalPreviousVertexHistory.discardFrame();
        MetalPreviousVertexHistory.beginFrame();
        var token = stage(new long[]{2, 1}, new float[]{99, 99}, CURRENT, true, 3, true);
        assertEquals(22, MetalPreviousVertexHistory.matchedPreviousPositions(token)[0]);
        assertEquals(11, MetalPreviousVertexHistory.matchedPreviousPositions(token)[12]);
    }

    @Test void failedGeometryReplacementCannotRetainACachedCrossGroupProof() {
        previousTwo(); MetalPreviousVertexHistory.beginFrame();
        var token = stage(new long[]{1, 2}, new float[]{99, 99}, CURRENT, true, 2, true);
        assertNotNull(MetalPreviousVertexHistory.matchedPreviousPositions(token));
        MetalPreviousVertexHistory.stageSnapshot(token, new MetalPreviousVertexHistory.Signature(token.pipelineKey(),
                DefaultVertexFormat.PARTICLE.getElements(), DefaultVertexFormat.PARTICLE.getVertexSize(),
                PrimitiveTopology.QUADS, 8, 12), new float[1]);
        assertNull(MetalPreviousVertexHistory.matchedPreviousPositions(token));
    }

    @Test void sourceProofIgnoresOnlyUnusedPaddingAndPreservesRawIntegerInputs() {
        var original = uniforms(OLD, 1);
        ByteBuffer dynamic = ByteBuffer.allocate(160).order(ByteOrder.nativeOrder()); original.writeDynamic(dynamic);
        ByteBuffer globals = ByteBuffer.allocate(48).order(ByteOrder.nativeOrder()); original.writeGlobals(globals);
        dynamic.putInt(156, 0x7FC00001);
        var padded = MetalSourceUniforms.decode(dynamic.array(), globals.array());
        assertNotNull(padded); assertTrue(original.sameHistoryInputs(padded));
        globals.putInt(0, 0x7FC00001); // CameraBlockPos is an integer, even when its bits resemble NaN.
        var changed = MetalSourceUniforms.decode(dynamic.array(), globals.array());
        assertNotNull(changed); assertFalse(original.sameHistoryInputs(changed));
    }

    private void previousTwo() {
        MetalPreviousVertexHistory.beginFrame();
        stage(new long[]{1}, new float[]{11}, OLD, true, 1, true);
        stage(new long[]{2}, new float[]{22}, OLD, true, 1, true);
        MetalPreviousVertexHistory.commitSubmittedFrame();
    }

    private MetalPreviousVertexHistory.DrawToken stage(long[] ids, float[] values,
            MetalPreviousVertexHistory.DrawTransform transform, boolean depth, float time, boolean particles) {
        var token = MetalPreviousVertexHistory.reserveDraw(sample, pipeline);
        float[] positions = new float[ids.length * 12];
        for (int i = 0; i < ids.length; i++) Arrays.fill(positions, i * 12, (i + 1) * 12, values[i]);
        MetalPreviousVertexHistory.stageSnapshot(token, new MetalPreviousVertexHistory.Signature(token.pipelineKey(),
                DefaultVertexFormat.PARTICLE.getElements(), DefaultVertexFormat.PARTICLE.getVertexSize(),
                PrimitiveTopology.QUADS, ids.length * 4, ids.length * 6), positions);
        List<Long> identity = Arrays.stream(ids).boxed().toList();
        if (particles) MetalPreviousVertexHistory.stageParticleIdentities(token, identity);
        else MetalPreviousVertexHistory.stageQuadIdentities(token, identity);
        MetalPreviousVertexHistory.stageDrawTransform(token, transform);
        MetalPreviousVertexHistory.stageSurface(token, new MetalPreviousVertexHistory.SurfaceIdentity(pipeline, Map.of(), depth));
        MetalPreviousVertexHistory.stageSourceUniforms(token, uniforms(transform, time));
        return token;
    }

    private static MetalSourceUniforms uniforms(MetalPreviousVertexHistory.DrawTransform transform, float time) {
        ByteBuffer dynamic = ByteBuffer.allocate(160).order(ByteOrder.nativeOrder());
        float[] values = new float[16]; transform.modelView().get(values);
        for (int i = 0; i < 16; i++) dynamic.putFloat(i * 4, values[i]);
        for (int i = 0; i < 4; i++) { dynamic.putFloat(64 + i * 20, 1); dynamic.putFloat(128 + i * 4, 1); }
        ByteBuffer globals = ByteBuffer.allocate(48).order(ByteOrder.nativeOrder()); globals.putFloat(28, time);
        return MetalSourceUniforms.decode(dynamic.array(), globals.array());
    }
}
