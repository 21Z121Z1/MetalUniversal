package com.metallum.client.metal.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;

final class MetalSourceDrawHistoryTest {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder().withLocation("test/source_history")
            .withVertexShader("core/entity").withFragmentShader("core/entity")
            .withVertexBinding(0, DefaultVertexFormat.ENTITY).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();
    private static final MetalEntityMotionCapture.Sample SAMPLE = new MetalEntityMotionCapture.Sample(
            801, 4, new Matrix4f(), new Matrix4f());

    @AfterEach
    void reset() { MetalPreviousVertexHistory.reset(); }

    @Test
    void worldUsesThePreviousDrawModelViewAndUnjitteredProjection() {
        Matrix4f oldP = new Matrix4f().perspective(1.1F, 1.7F, .05F, 200);
        Matrix4f oldRasterP = new Matrix4f(oldP).m20(.007F).m21(-.011F);
        Matrix4f oldView = new Matrix4f().rotateY(.2F).translate(.3F, -.2F, -2);
        Matrix4f newP = new Matrix4f().perspective(1.13F, 1.7F, .05F, 200);
        Matrix4f newRasterP = new Matrix4f(newP).m20(-.009F).m21(.003F);
        Matrix4f newView = new Matrix4f().rotateZ(.07F).translate(-.15F, .1F, -2.5F);
        MetalPreviousVertexHistory.beginFrame();
        stage(oldRasterP, oldView, oldP, 17);
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();
        var token = stage(newRasterP, newView, newP, 17);
        var transforms = MetalPreviousVertexReplay.worldTransforms(token);
        assertNotNull(transforms);
        Matrix4f flip = new Matrix4f().scaling(1, -1, 1);
        Matrix4f expectedPrevious = new Matrix4f(flip).mul(oldP).mul(oldView);
        assertTrue(expectedPrevious.equals(transforms.previousFromPosition(), 1E-6F));
        Vector4f point = new Vector4f(.2F, -.3F, -.4F, 1);
        Vector4f raster = new Matrix4f(newRasterP).mul(newView).transform(new Vector4f(point));
        Vector4f actualCurrent = transforms.currentUnjitteredFromRaster().transform(raster);
        Vector4f expectedCurrent = new Matrix4f(flip).mul(newP).mul(newView).transform(new Vector4f(point));
        assertTrue(expectedCurrent.equals(actualCurrent, 2E-6F), "source jitter is removed without dropping the draw's model-view");
        assertTrue(newRasterP.equals(transforms.currentProjection(), 0));
        oldP.zero(); oldView.zero(); newRasterP.zero();
        assertTrue(expectedPrevious.equals(MetalPreviousVertexReplay.worldTransforms(token).previousFromPosition(), 1E-6F));
    }

    @Test
    void textureReallocationBreaksSurfaceContinuityEvenIfGeometryAndLabelMatch() {
        MetalPreviousVertexHistory.beginFrame();
        stage(new Matrix4f(), new Matrix4f(), new Matrix4f(), 17);
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();
        var token = stage(new Matrix4f(), new Matrix4f(), new Matrix4f(), 18);
        assertNotNull(MetalPreviousVertexHistory.matchedPreviousPositions(token));
        assertFalse(MetalPreviousVertexHistory.matchingSurfaceManifest(token));
    }

    @Test
    void failedSourceDoesNotAdvanceMaterialOrTransformHistory() {
        MetalPreviousVertexHistory.beginFrame();
        stage(new Matrix4f(), new Matrix4f().translate(.4F, 0, 0), new Matrix4f(), 17);
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();
        stage(new Matrix4f(), new Matrix4f().translate(9, 0, 0), new Matrix4f(), 18);
        MetalPreviousVertexHistory.discardFrame();
        MetalPreviousVertexHistory.beginFrame();
        var token = stage(new Matrix4f(), new Matrix4f(), new Matrix4f(), 17);
        assertTrue(MetalPreviousVertexHistory.matchingSurfaceManifest(token));
        assertEquals(.4F, MetalPreviousVertexReplay.worldTransforms(token).previousFromPosition().m30(), 1E-6);
    }

    @Test
    void oneMissingSurfaceInvalidatesTheWholeOwnersManifest() {
        MetalPreviousVertexHistory.beginFrame();
        stage(new Matrix4f(), new Matrix4f(), new Matrix4f(), 17);
        stage(new Matrix4f(), new Matrix4f(), new Matrix4f(), 17);
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();
        var first = stage(new Matrix4f(), new Matrix4f(), new Matrix4f(), 17);
        var second = stage(new Matrix4f(), new Matrix4f(), new Matrix4f(), 17);
        assertTrue(MetalPreviousVertexHistory.matchingSurfaceManifest(first));
        MetalPreviousVertexHistory.stageSurface(second, null);
        assertFalse(MetalPreviousVertexHistory.matchingSurfaceManifest(first));
    }

    @Test
    void singularCurrentRasterProjectionCannotProduceWorldMotion() {
        MetalPreviousVertexHistory.beginFrame();
        stage(new Matrix4f(), new Matrix4f(), new Matrix4f(), 17);
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();
        var token = stage(new Matrix4f().zero(), new Matrix4f(), new Matrix4f(), 17);
        assertNull(MetalPreviousVertexReplay.worldTransforms(token));
    }

    private static MetalPreviousVertexHistory.DrawToken stage(Matrix4f rasterP, Matrix4f view, Matrix4f unjitteredP, long allocation) {
        var token = MetalPreviousVertexHistory.reserveDraw(SAMPLE, PIPELINE);
        MetalPreviousVertexHistory.stageSnapshot(token, new MetalPreviousVertexHistory.Signature(
                PIPELINE.getLocation().toString(), DefaultVertexFormat.ENTITY.getElements(),
                DefaultVertexFormat.ENTITY.getVertexSize(), PrimitiveTopology.TRIANGLES, 1, 3), new float[]{.2F, -.3F, -.4F});
        MetalPreviousVertexHistory.stageDrawTransform(token, new MetalPreviousVertexHistory.DrawTransform(rasterP, view, unjitteredP));
        var texture = new MetalPreviousVertexHistory.TextureIdentity(new MetalAllocationIdentity(allocation, 1), 0, 1,
                AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
        MetalPreviousVertexHistory.stageSurface(token, new MetalPreviousVertexHistory.SurfaceIdentity(PIPELINE, Map.of("Sampler0", texture)));
        return token;
    }
}
