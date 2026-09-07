package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalExactMotionCoverageTest {
    @AfterEach
    void reset() {
        MetalExactMotionCoverage.reset();
        MetalPreviousVertexHistory.discardFrame();
        MetalPreviousVertexHistory.reset();
    }

    @Test
    void noRequiredObjectIsComplete() {
        MetalExactMotionCoverage.beginFrame();
        assertTrue(MetalExactMotionCoverage.complete());
    }

    @Test
    void requiredObjectNeedsWholeManifestAndEveryExactPlan() {
        RenderPipeline pipeline = pipeline("exact_coverage");
        MetalEntityMotionCapture.Sample sample = new MetalEntityMotionCapture.Sample(17L, 4L, new Matrix4f(), null);
        VertexFormat format = pipeline.getVertexFormatBinding(0);
        MetalPreviousVertexHistory.Signature signature = new MetalPreviousVertexHistory.Signature(
                pipeline.getLocation().toString(), format.getElements(), format.getVertexSize(),
                PrimitiveTopology.TRIANGLES, 1, 3
        );

        // Previous successful source frame.
        MetalPreviousVertexHistory.beginFrame();
        MetalPreviousVertexHistory.DrawToken previous = MetalPreviousVertexHistory.reserveDraw(sample, pipeline);
        MetalPreviousVertexHistory.stageSnapshot(previous, signature, new float[] {0.0F, 0.0F, 0.0F});
        MetalPreviousVertexHistory.commitSubmittedFrame();

        // Current frame has a matching manifest but is incomplete until its exact replay is planned.
        MetalPreviousVertexHistory.beginFrame();
        MetalExactMotionCoverage.beginFrame();
        MetalExactMotionCoverage.require(sample);
        MetalPreviousVertexHistory.DrawToken current = MetalPreviousVertexHistory.reserveDraw(sample, pipeline);
        MetalPreviousVertexHistory.stageSnapshot(current, signature, new float[] {1.0F, 0.0F, 0.0F});
        assertFalse(MetalExactMotionCoverage.complete());
        MetalExactMotionCoverage.recordExactPlan(current);
        assertTrue(MetalExactMotionCoverage.complete());
    }

    @Test
    void unsupportedAuxiliaryFailsClosedEvenWithMatchingHistory() {
        MetalEntityMotionCapture.Sample sample = new MetalEntityMotionCapture.Sample(21L, 8L, new Matrix4f(), null);
        MetalPreviousVertexHistory.beginFrame();
        MetalExactMotionCoverage.beginFrame();
        MetalExactMotionCoverage.require(sample);
        MetalExactMotionCoverage.fail(sample, "unsupported-auxiliary");
        assertFalse(MetalExactMotionCoverage.complete());
    }

    private static RenderPipeline pipeline(final String name) {
        Identifier id = Identifier.fromNamespaceAndPath("metallum", name);
        return RenderPipeline.builder()
                .withLocation(id)
                .withVertexShader(id)
                .withFragmentShader(id)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withVertexBinding(0, VertexFormat.builder(0)
                        .addAttribute("Position", GpuFormat.RGB32_FLOAT)
                        .build())
                .build();
    }
}
