package com.metallum.client.metal.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Zero source alpha is a rejection, never a certificate of complete previous visibility. */
final class MetalPreviousVisibilityHistoryTest {
    private static final MetalEntityMotionCapture.Sample OWNER =
            new MetalEntityMotionCapture.Sample(4601, 17, new Matrix4f(), new Matrix4f());
    private static final float[] POSITIONS = {0, 0, .5F};

    @AfterEach
    void clear() {
        MetalPreviousVertexHistory.discardFrame();
        MetalPreviousVertexHistory.reset();
    }

    @ParameterizedTest
    @ValueSource(strings = {"entity", "item", "block", "particle"})
    void zeroContributionCannotBorrowCoincidentBackgroundAndOnlyAcceptedVisibilityAdvances(String shader) {
        var source = source(shader);
        stage(source, 0);
        MetalPreviousVertexHistory.commitSubmittedFrame();

        var failed = stage(source, 1);
        assertTrue(MetalPreviousVertexHistory.matchingSurfaceManifest(failed), "Material and geometry still match");
        assertArrayEquals(POSITIONS, MetalPreviousVertexHistory.matchedPreviousPositions(failed));
        assertEquals(0, MetalPreviousVertexHistory.matchedPreviousSourceUniforms(failed).colorModulator().w);
        boolean failedWasRejected = MetalPreviousVertexReplay.previousPositionsForSource(source, failed) == null;
        MetalPreviousVertexHistory.discardFrame();

        var accepted = stage(source, 1);
        boolean discardedDidNotAdvance = MetalPreviousVertexReplay.previousPositionsForSource(source, accepted) == null;
        assertNotNull(MetalPreviousVertexReplay.currentOnlyTransforms(accepted, false));
        MetalPreviousVertexHistory.commitSubmittedFrame();

        var next = stage(source, 1);
        assertArrayEquals(POSITIONS, MetalPreviousVertexReplay.previousPositionsForSource(source, next),
                "The accepted current-only source may supply the next continuous visible frame");
        assertTrue(failedWasRejected, "Removing the previous-zero-alpha guard must fail this control");
        assertTrue(discardedDidNotAdvance, "A failed visible source must not replace the accepted invisible source");
    }

    @ParameterizedTest
    @ValueSource(strings = {"entity", "item", "block", "particle"})
    void continuouslyVisibleHistoryRetainsExactPositions(String shader) {
        var source = source(shader);
        stage(source, 1);
        MetalPreviousVertexHistory.commitSubmittedFrame();
        var next = stage(source, 1);
        assertArrayEquals(POSITIONS, MetalPreviousVertexReplay.previousPositionsForSource(source, next));
        assertNotNull(MetalPreviousVertexReplay.worldTransforms(next));
    }

    @ParameterizedTest
    @ValueSource(strings = {"entity", "item", "block", "particle"})
    void absentAcceptedUniformsCannotBeReplacedByCurrentVisibleUniforms(String shader) {
        var source = source(shader);
        var old = stage(source, 1);
        MetalPreviousVertexHistory.stageSourceUniforms(old, null);
        MetalPreviousVertexHistory.commitSubmittedFrame();
        var current = stage(source, 1);
        assertArrayEquals(POSITIONS, MetalPreviousVertexHistory.matchedPreviousPositions(current));
        assertNotNull(MetalPreviousVertexHistory.currentSourceUniforms(current));
        assertNull(MetalPreviousVertexReplay.previousPositionsForSource(source, current));
        assertNotNull(MetalPreviousVertexReplay.currentOnlyTransforms(current, false));
    }

    static RenderPipeline source(String shader) {
        var format = shader.equals("particle") ? DefaultVertexFormat.PARTICLE
                : shader.equals("block") ? DefaultVertexFormat.BLOCK : DefaultVertexFormat.ENTITY;
        return RenderPipeline.builder().withLocation("previous_visibility/" + shader)
                .withVertexShader("core/" + shader).withFragmentShader("core/" + shader)
                .withVertexBinding(0, format).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();
    }

    private static MetalPreviousVertexHistory.DrawToken stage(RenderPipeline source, float alpha) {
        MetalPreviousVertexHistory.beginFrame();
        var token = MetalPreviousVertexHistory.reserveDraw(OWNER, source);
        var format = source.getVertexFormatBinding(0);
        MetalPreviousVertexHistory.stageSnapshot(token, new MetalPreviousVertexHistory.Signature(token.pipelineKey(),
                format.getElements(), format.getVertexSize(), PrimitiveTopology.TRIANGLES, 1, 3), POSITIONS);
        MetalPreviousVertexHistory.stageDrawTransform(token,
                new MetalPreviousVertexHistory.DrawTransform(new Matrix4f(), new Matrix4f()));
        MetalPreviousVertexHistory.stageSurface(token, new MetalPreviousVertexHistory.SurfaceIdentity(source, Map.of()));
        MetalPreviousVertexHistory.stageSourceUniforms(token, MetalSourceUniforms.decode(dynamic(alpha), null));
        return token;
    }

    static byte[] dynamic(float alpha) {
        ByteBuffer b = ByteBuffer.allocateDirect(160).order(ByteOrder.nativeOrder());
        new Matrix4f().get(0, b); new Matrix4f().get(64, b);
        for (int c = 0; c < 3; c++) b.putFloat(128 + c * 4, 1);
        b.putFloat(140, alpha);
        byte[] bytes = new byte[160]; b.get(0, bytes);
        return bytes;
    }
}
