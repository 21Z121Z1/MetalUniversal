package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalPreviousVertexHistoryTest {
    @AfterEach
    void clearHistory() {
        MetalPreviousVertexHistory.discardFrame();
        MetalPreviousVertexHistory.reset();
    }

    @Test
    void extractsPositionAtItsDeclaredOffsetAcrossSlices() {
        VertexFormat format = VertexFormat.builder(0)
                .addAttribute("Color", GpuFormat.RGBA8_UNORM)
                .addAttribute("Position", GpuFormat.RGB32_FLOAT)
                .build();
        ByteBuffer first = vertex(format, 0x01020304, 1.0F, 2.0F, 3.0F);
        ByteBuffer second = vertex(format, 0x05060708, -4.0F, 5.5F, 6.0F);

        assertArrayEquals(
                new float[] { 1.0F, 2.0F, 3.0F, -4.0F, 5.5F, 6.0F },
                MetalPreviousVertexHistory.extractPositions(format, List.of(first, second), 2)
        );
    }

    @Test
    void malformedOrNonFiniteVertexStreamsFailClosed() {
        VertexFormat format = VertexFormat.builder(0)
                .addAttribute("Position", GpuFormat.RGB32_FLOAT)
                .build();
        ByteBuffer partial = ByteBuffer.allocate(format.getVertexSize() - 1).order(ByteOrder.nativeOrder());
        assertNull(MetalPreviousVertexHistory.extractPositions(format, List.of(partial), 1));

        ByteBuffer nonFinite = ByteBuffer.allocate(format.getVertexSize()).order(ByteOrder.nativeOrder());
        nonFinite.putFloat(Float.NaN).putFloat(0.0F).putFloat(0.0F).flip();
        assertNull(MetalPreviousVertexHistory.extractPositions(format, List.of(nonFinite), 1));
    }

    @Test
    void compactPreviousPositionBindingMatchesConfirmedMinecraftAbi() {
        VertexFormat format = MetalEntityMotionPipeline.previousPositionFormat();
        assertTrue(format.getStepRate() == 0);
        assertTrue(format.getVertexSize() == 12);
        assertTrue(format.getElements().size() == 1);
        assertTrue(format.contains("PreviousPosition"));
    }

    @Test
    void surfaceDepthVariantHasAnExplicitValidityAttributeAndFullPrecisionDepthPair() {
        var source = RenderPipeline.builder().withLocation("test/surface_depth_abi")
                .withVertexShader("core/particle").withFragmentShader("core/particle")
                .withVertexBinding(0, com.mojang.blaze3d.vertex.DefaultVertexFormat.PARTICLE)
                .withPrimitiveTopology(PrimitiveTopology.QUADS).build();
        var variant = MetalEntityMotionPipeline.forPreviousPositionsWithDepth(source);
        assertTrue(variant.getVertexFormatBinding(1).getVertexSize() == 16);
        assertTrue(variant.getVertexFormatBinding(1).contains("PreviousValidity"));
        assertTrue(variant.getColorTargetStates().get(2).format() == GpuFormat.RG32_FLOAT);
        assertTrue(variant.getShaderDefines().flags().contains("METALLUM_PREVIOUS_VALIDITY"));
        assertTrue(variant.getShaderDefines().flags().contains("METALLUM_MOTION_DEPTH"));
    }

    @Test
    void changingSourceDepthAttachmentParticipationInvalidatesSurfaceHistory() {
        var source=pipeline("source_attachment_history");var format=source.getVertexFormatBinding(0);
        var sample=new MetalEntityMotionCapture.Sample(81,9,new Matrix4f(),new Matrix4f());
        for(int frame=0;frame<2;frame++) {
            MetalPreviousVertexHistory.beginFrame();
            var token=MetalPreviousVertexHistory.reserveDraw(sample,source);
            MetalPreviousVertexHistory.stageSnapshot(token,new MetalPreviousVertexHistory.Signature(token.pipelineKey(),
                    format.getElements(),format.getVertexSize(),PrimitiveTopology.TRIANGLES,1,3),new float[]{0,0,.5F});
            MetalPreviousVertexHistory.stageSurface(token,new MetalPreviousVertexHistory.SurfaceIdentity(source,java.util.Map.of(),frame==0));
            if(frame==0)MetalPreviousVertexHistory.commitSubmittedFrame();
            else assertFalse(MetalPreviousVertexHistory.matchingSurfaceManifest(token));
        }
    }

    @Test
    void sourceViewportAndHardwareBiasParticipateInTheAcceptedSurfaceManifest() {
        var source=pipeline("raster_history");var format=source.getVertexFormatBinding(0);
        var sample=new MetalEntityMotionCapture.Sample(82,9,new Matrix4f(),new Matrix4f());
        var accepted=new MetalPreviousVertexReplay.RasterState(640,480,2,3);
        for (var candidate : List.of(accepted,new MetalPreviousVertexReplay.RasterState(1280,960,2,3),
                new MetalPreviousVertexReplay.RasterState(640,480,-2,-3))) {
            MetalPreviousVertexHistory.reset();
            for(int frame=0;frame<2;frame++) {
                MetalPreviousVertexHistory.beginFrame();
                var token=MetalPreviousVertexHistory.reserveDraw(sample,source);
                MetalPreviousVertexHistory.stageSnapshot(token,new MetalPreviousVertexHistory.Signature(token.pipelineKey(),
                        format.getElements(),format.getVertexSize(),PrimitiveTopology.TRIANGLES,1,3),new float[]{0,0,.5F});
                MetalPreviousVertexHistory.stageSurface(token,new MetalPreviousVertexHistory.SurfaceIdentity(
                        source,java.util.Map.of(),true,frame==0 ? accepted : candidate));
                if(frame==0)MetalPreviousVertexHistory.commitSubmittedFrame();
                else assertTrue(MetalPreviousVertexHistory.matchingSurfaceManifest(token)==candidate.equals(accepted));
            }
        }
    }

    @Test
    void previousD32BiasRequiresKnownViewportAndABoundedConstant() {
        assertTrue(new MetalPreviousVertexReplay.RasterState(640,480,2,-10).reconstructible());
        assertFalse(new MetalPreviousVertexReplay.RasterState(0,480,2,3).reconstructible());
        assertFalse(new MetalPreviousVertexReplay.RasterState(640,480,Float.NaN,3).reconstructible());
        assertFalse(new MetalPreviousVertexReplay.RasterState(640,480,2,17).reconstructible());
        assertFalse(new MetalPreviousVertexReplay.RasterState(640,480,2,Float.POSITIVE_INFINITY).reconstructible());
    }

    @Test
    void historyAdvancesOnlyOnSuccessfulCommit() {
        RenderPipeline pipeline = pipeline("previous_vertex_transaction");
        MetalEntityMotionCapture.Sample sample = new MetalEntityMotionCapture.Sample(
                41L, 7L, new Matrix4f(), null
        );
        VertexFormat format = pipeline.getVertexFormatBinding(0);
        MetalPreviousVertexHistory.Signature signature = new MetalPreviousVertexHistory.Signature(
                pipeline.getLocation().toString(),
                format.getElements(),
                format.getVertexSize(),
                PrimitiveTopology.TRIANGLES,
                1,
                3
        );

        MetalPreviousVertexHistory.beginFrame();
        MetalPreviousVertexHistory.DrawToken first = MetalPreviousVertexHistory.reserveDraw(sample, pipeline);
        MetalPreviousVertexHistory.stageSnapshot(first, signature, new float[] { 1.0F, 2.0F, 3.0F });
        assertNull(MetalPreviousVertexHistory.matchedPreviousPositions(first));
        MetalPreviousVertexHistory.commitSubmittedFrame();

        MetalPreviousVertexHistory.beginFrame();
        MetalPreviousVertexHistory.DrawToken failed = MetalPreviousVertexHistory.reserveDraw(sample, pipeline);
        MetalPreviousVertexHistory.stageSnapshot(failed, signature, new float[] { 9.0F, 9.0F, 9.0F });
        assertArrayEquals(
                new float[] { 1.0F, 2.0F, 3.0F },
                MetalPreviousVertexHistory.matchedPreviousPositions(failed)
        );
        MetalPreviousVertexHistory.discardFrame();

        MetalPreviousVertexHistory.beginFrame();
        MetalPreviousVertexHistory.DrawToken afterFailure = MetalPreviousVertexHistory.reserveDraw(sample, pipeline);
        MetalPreviousVertexHistory.stageSnapshot(afterFailure, signature, new float[] { 4.0F, 5.0F, 6.0F });
        assertArrayEquals(
                new float[] { 1.0F, 2.0F, 3.0F },
                MetalPreviousVertexHistory.matchedPreviousPositions(afterFailure),
                "a discarded source frame must not replace the previous successful positions"
        );
    }

    @Test
    void wholeObjectManifestMustMatchBeforeAnyDrawCanReuseHistory() {
        RenderPipeline pipeline = pipeline("previous_vertex_manifest");
        MetalEntityMotionCapture.Sample sample = new MetalEntityMotionCapture.Sample(
                9L, 3L, new Matrix4f(), null
        );
        VertexFormat format = pipeline.getVertexFormatBinding(0);
        MetalPreviousVertexHistory.Signature signature = new MetalPreviousVertexHistory.Signature(
                pipeline.getLocation().toString(), format.getElements(), format.getVertexSize(),
                PrimitiveTopology.TRIANGLES, 1, 3
        );

        MetalPreviousVertexHistory.beginFrame();
        MetalPreviousVertexHistory.DrawToken previous = MetalPreviousVertexHistory.reserveDraw(sample, pipeline);
        MetalPreviousVertexHistory.stageSnapshot(previous, signature, new float[] { 0.0F, 0.0F, 0.0F });
        MetalPreviousVertexHistory.commitSubmittedFrame();

        MetalPreviousVertexHistory.beginFrame();
        MetalPreviousVertexHistory.DrawToken currentFirst = MetalPreviousVertexHistory.reserveDraw(sample, pipeline);
        MetalPreviousVertexHistory.DrawToken currentExtra = MetalPreviousVertexHistory.reserveDraw(sample, pipeline);
        MetalPreviousVertexHistory.stageSnapshot(currentFirst, signature, new float[] { 1.0F, 0.0F, 0.0F });
        MetalPreviousVertexHistory.stageSnapshot(currentExtra, signature, new float[] { 2.0F, 0.0F, 0.0F });

        assertFalse(MetalPreviousVertexHistory.objectManifestMatches(currentFirst.key().object()));
        assertNull(MetalPreviousVertexHistory.matchedPreviousPositions(currentFirst));
    }

    private static ByteBuffer vertex(
            final VertexFormat format,
            final int color,
            final float x,
            final float y,
            final float z
    ) {
        ByteBuffer bytes = ByteBuffer.allocate(format.getVertexSize()).order(ByteOrder.nativeOrder());
        bytes.putInt(color).putFloat(x).putFloat(y).putFloat(z).flip();
        return bytes;
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
