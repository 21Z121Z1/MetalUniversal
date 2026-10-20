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

final class MetalParticleVertexCorrespondenceTest {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder().withLocation("test/particle_correspondence")
            .withVertexShader("core/particle").withFragmentShader("core/particle")
            .withVertexBinding(0, DefaultVertexFormat.PARTICLE).withPrimitiveTopology(PrimitiveTopology.QUADS).build();
    private static final MetalEntityMotionCapture.Sample SAMPLE = new MetalEntityMotionCapture.Sample(903, -4,
            new Matrix4f(), new Matrix4f(), FrameSynthesisContract.ProducerDomain.PARTICLES_WEATHER);

    @AfterEach
    void reset() { MetalPreviousVertexHistory.reset(); }

    @Test
    void reorderedQuadsUseTheirOwnPreviousVertices() {
        seed(List.of(11L, 22L), 10, 20);
        var token = stage(List.of(22L, 11L), 200, 100);
        assertArrayEquals(quads(20, 10), MetalPreviousVertexHistory.matchedPreviousPositions(token));
        assertNotNull(MetalPreviousVertexReplay.worldTransforms(token));
        assertTrue(MetalPreviousVertexHistory.matchingSurfaceManifest(token));
    }

    @Test
    void birthsDeathsAndMembershipCountChangesDoNotInvalidateSurvivors() {
        seed(List.of(11L, 22L, 44L), 10, 20, 40);
        var token = stage(List.of(22L, 33L), 200, 300);
        float[] previous = MetalPreviousVertexHistory.matchedPreviousPositions(token);
        assertNotNull(previous);
        assertQuad(previous, 0, 20);
        assertInvalidQuad(previous, 1);
        assertTrue(MetalPreviousVertexHistory.objectManifestMatches(token.key().object()));
        assertTrue(MetalPreviousVertexHistory.matchingSurfaceManifest(token));
    }

    @Test
    void replacingOneParticleAtTheSameAggregateCountNeverAliasesHistory() {
        seed(List.of(11L, 22L), 10, 20);
        float[] previous = MetalPreviousVertexHistory.matchedPreviousPositions(stage(List.of(33L, 22L), 300, 200));
        assertInvalidQuad(previous, 0);
        assertQuad(previous, 1, 20);
    }

    @Test
    void stableMultipleEmissionsRetainTheirPerParticleOrder() {
        seed(List.of(11L, 11L, 22L), 10, 15, 20);
        var token = stage(List.of(11L, 22L, 11L), 100, 200, 150);
        assertArrayEquals(quads(10, 20, 15), MetalPreviousVertexHistory.matchedPreviousPositions(token));
    }

    @Test
    void ambiguousEmissionCountInvalidatesOnlyThatParticle() {
        seed(List.of(11L, 11L, 22L), 10, 15, 20);
        float[] previous = MetalPreviousVertexHistory.matchedPreviousPositions(stage(List.of(11L, 22L), 100, 200));
        assertInvalidQuad(previous, 0);
        assertQuad(previous, 1, 20);
    }

    @Test
    void rejectedSubmissionCannotPublishItsNewMembershipOrPositions() {
        seed(List.of(11L, 22L), 10, 20);
        stage(List.of(33L, 22L), 300, 200);
        MetalPreviousVertexHistory.discardFrame();
        MetalPreviousVertexHistory.beginFrame();
        float[] previous = MetalPreviousVertexHistory.matchedPreviousPositions(stage(List.of(22L, 33L), 201, 301));
        assertQuad(previous, 0, 20);
        assertInvalidQuad(previous, 1);
    }

    @Test
    void incompleteIdentityMetadataCannotRelaxTheManifest() {
        seed(List.of(11L, 22L), 10, 20);
        var token = stage(List.of(22L, 11L), 200, 100);
        MetalPreviousVertexHistory.stageParticleIdentities(token, List.of(22L));
        assertNull(MetalPreviousVertexHistory.matchedPreviousPositions(token));
        assertFalse(MetalPreviousVertexHistory.matchingSurfaceManifest(token));
    }

    @Test
    void submittedAbsenceRetiresTheParticleHistory() {
        seed(List.of(11L, 22L), 10, 20);
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();
        assertNull(MetalPreviousVertexHistory.matchedPreviousPositions(stage(List.of(11L, 22L), 100, 200)));
    }

    private static void seed(List<Long> ids, float... markers) {
        MetalPreviousVertexHistory.beginFrame();
        stage(ids, markers);
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();
    }

    private static MetalPreviousVertexHistory.DrawToken stage(List<Long> ids, float... markers) {
        var token = MetalPreviousVertexHistory.reserveDraw(SAMPLE, PIPELINE);
        var format = DefaultVertexFormat.PARTICLE;
        MetalPreviousVertexHistory.stageSnapshot(token, new MetalPreviousVertexHistory.Signature(PIPELINE.getLocation().toString(),
                format.getElements(), format.getVertexSize(), PrimitiveTopology.QUADS, markers.length*4, markers.length*6), quads(markers));
        MetalPreviousVertexHistory.stageParticleIdentities(token, ids);
        MetalPreviousVertexHistory.stageDrawTransform(token, new MetalPreviousVertexHistory.DrawTransform(new Matrix4f(), new Matrix4f()));
        MetalPreviousVertexHistory.stageSurface(token, new MetalPreviousVertexHistory.SurfaceIdentity(PIPELINE, Map.of()));
        return token;
    }

    private static float[] quads(float... markers) {
        float[] positions = new float[markers.length*12];
        for (int i = 0; i < markers.length; i++) for (int j = 0; j < 12; j++) positions[i*12+j] = markers[i]+j/16F;
        return positions;
    }

    private static void assertQuad(float[] positions, int quad, float marker) {
        assertNotNull(positions);
        assertArrayEquals(quads(marker), java.util.Arrays.copyOfRange(positions, quad*12, quad*12+12));
    }

    private static void assertInvalidQuad(float[] positions, int quad) {
        assertNotNull(positions);
        for (int i = quad*12; i < quad*12+12; i++) assertTrue(Float.isNaN(positions[i]), "a new emission needs explicit invalid correspondence");
    }
}
