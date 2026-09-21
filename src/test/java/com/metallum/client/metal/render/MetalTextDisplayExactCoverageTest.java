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

final class MetalTextDisplayExactCoverageTest {
    @AfterEach
    void reset() {
        MetalExactMotionCoverage.reset();
        MetalPreviousVertexHistory.discardFrame();
        MetalPreviousVertexHistory.reset();
    }

    @Test
    void backgroundAndGlyphDrawsMustBothEncodeExactly() {
        RenderPipeline background = pipeline("text_display_background");
        RenderPipeline glyphs = pipeline("text_display_glyphs");
        MetalEntityMotionCapture.Sample sample =
                new MetalEntityMotionCapture.Sample(91L, 3L, new Matrix4f(), new Matrix4f());

        MetalPreviousVertexHistory.beginFrame();
        stage(sample, background, new float[] {-1F, -1F, 0F});
        stage(sample, glyphs, new float[] {0F, 0F, 0F});
        MetalPreviousVertexHistory.commitSubmittedFrame();

        MetalPreviousVertexHistory.beginFrame();
        MetalExactMotionCoverage.beginFrame();
        MetalExactMotionCoverage.require(sample);
        MetalPreviousVertexHistory.DrawToken currentBackground =
                stage(sample, background, new float[] {-1F, -1F, 0F});
        MetalPreviousVertexHistory.DrawToken currentGlyphs =
                stage(sample, glyphs, new float[] {1F, 0F, 0F});

        assertFalse(MetalExactMotionCoverage.complete());
        MetalExactMotionCoverage.recordExactEncoded(currentBackground);
        assertFalse(MetalExactMotionCoverage.complete(), "glyph draw is still missing exact motion");
        MetalExactMotionCoverage.recordExactEncoded(currentGlyphs);
        assertTrue(MetalExactMotionCoverage.complete());
    }

    private static MetalPreviousVertexHistory.DrawToken stage(
            final MetalEntityMotionCapture.Sample sample,
            final RenderPipeline pipeline,
            final float[] positions
    ) {
        VertexFormat format = pipeline.getVertexFormatBinding(0);
        MetalPreviousVertexHistory.DrawToken token = MetalPreviousVertexHistory.reserveDraw(sample, pipeline);
        MetalPreviousVertexHistory.stageSnapshot(
                token,
                new MetalPreviousVertexHistory.Signature(
                        pipeline.getLocation().toString(),
                        format.getElements(),
                        format.getVertexSize(),
                        PrimitiveTopology.TRIANGLES,
                        positions.length / 3,
                        3
                ),
                positions
        );
        return token;
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
