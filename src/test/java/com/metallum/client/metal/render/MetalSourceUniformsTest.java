package com.metallum.client.metal.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

final class MetalSourceUniformsTest {
    private static final RenderPipeline SOURCE=RenderPipeline.builder().withLocation("test/source_uniforms")
            .withVertexShader("core/entity").withFragmentShader("core/entity").withVertexBinding(0,DefaultVertexFormat.ENTITY)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();
    private static final MetalEntityMotionCapture.Sample SAMPLE=new MetalEntityMotionCapture.Sample(511,7,new Matrix4f(),new Matrix4f());

    @AfterEach
    void reset() {MetalPreviousVertexHistory.discardFrame();MetalPreviousVertexHistory.reset();}

    @Test
    void readsActualStd140OffsetsAndOwnsImmutableSourceBytes() {
        byte[] dynamic=dynamic(3),globals=globals(.4F,.125F);
        var originalDynamic=dynamic.clone();var originalGlobals=globals.clone();
        var captured=MetalSourceUniforms.decode(dynamic,globals);assertNotNull(captured);
        assertEquals(3,captured.modelView().m30(),0);
        assertEquals(.3F,captured.textureMatrix().m30(),0);
        assertEquals(.25F,captured.colorModulator().w,0);
        assertEquals(new Vector3f(3,4,5),captured.modelOffset());
        assertEquals(.4F,captured.glintAlpha(),0);assertEquals(.125F,captured.gameTime(),0);
        java.util.Arrays.fill(dynamic,(byte)0);java.util.Arrays.fill(globals,(byte)0);
        captured.modelView().zero();captured.textureMatrix().zero();captured.colorModulator().zero();captured.modelOffset().zero();
        ByteBuffer d=ByteBuffer.allocate(160),g=ByteBuffer.allocate(48);
        captured.writeDynamic(d);captured.writeGlobals(g);
        assertArrayEquals(originalDynamic,d.array());assertArrayEquals(originalGlobals,g.array());
        assertEquals(.4F,captured.glintAlpha(),0);
    }

    @Test
    void validatesOnlyUsedFloatFieldsAndRejectsTruncatedOrNonFiniteBindings() {
        assertNull(MetalSourceUniforms.decode(new byte[159],null));
        assertNull(MetalSourceUniforms.decode(dynamic(1),new byte[47]));
        for(int offset:new int[]{0,64,128,140,144,152}) {
            byte[] malformed=dynamic(1);bytes(malformed).putFloat(offset,Float.NaN);
            assertNull(MetalSourceUniforms.decode(malformed,null));
        }
        for(int offset:new int[]{12,16,28,32,36}) {
            byte[] malformed=globals(.4F,.1F);bytes(malformed).putFloat(offset,Float.POSITIVE_INFINITY);
            assertNull(MetalSourceUniforms.decode(dynamic(1),malformed));
        }
        byte[] padded=dynamic(1);bytes(padded).putFloat(156,Float.NaN);
        byte[] integers=globals(.4F,.1F);bytes(integers).putInt(0,0x7FC00000).putInt(40,0x7F800000);
        assertNotNull(MetalSourceUniforms.decode(padded,integers),"std140 padding and integer bit patterns are not float fields");
        var noGlobals=MetalSourceUniforms.decode(dynamic(1),null);assertNotNull(noGlobals);assertFalse(noGlobals.hasGlobals());
        assertThrows(IllegalStateException.class,noGlobals::gameTime);
    }

    @Test
    void blockPreviousPositionsUseTheAcceptedOffsetAndNeverTheCurrentOrFailedOffset() {
        var source=RenderPipeline.builder().withLocation("test/block_offset_history")
                .withVertexShader("core/block").withFragmentShader("core/block").withVertexBinding(0,DefaultVertexFormat.BLOCK)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();
        MetalPreviousVertexHistory.DrawToken token=null;
        for(int frame=0;frame<3;frame++) {
            MetalPreviousVertexHistory.beginFrame();token=MetalPreviousVertexHistory.reserveDraw(SAMPLE,source);
            MetalPreviousVertexHistory.stageSnapshot(token,new MetalPreviousVertexHistory.Signature(token.pipelineKey(),
                    DefaultVertexFormat.BLOCK.getElements(),DefaultVertexFormat.BLOCK.getVertexSize(),PrimitiveTopology.TRIANGLES,1,3),
                    new float[]{2,3,4});
            MetalPreviousVertexHistory.stageDrawTransform(token,new MetalPreviousVertexHistory.DrawTransform(new Matrix4f(),new Matrix4f()));
            MetalPreviousVertexHistory.stageSourceUniforms(token,MetalSourceUniforms.decode(dynamic(frame==0 ? 1 : frame==1 ? 99 : 7),null));
            if(frame==0)MetalPreviousVertexHistory.commitSubmittedFrame();
            if(frame==1)MetalPreviousVertexHistory.discardFrame();
        }
        assertArrayEquals(new float[]{3,7,9},MetalPreviousVertexReplay.previousPositionsForSource(source,token));
        assertArrayEquals(new float[]{2,3,4},MetalPreviousVertexHistory.matchedPreviousPositions(token),
                "Offset adjustment must not mutate accepted source geometry");
        assertArrayEquals(new float[]{3,7,9},MetalPreviousVertexReplay.previousPositionsForSource(source,token));
    }

    @Test
    void materialTimeAndOffsetAdvanceWithTheAcceptedGeometryTransaction() {
        MetalPreviousVertexHistory.beginFrame();var first=stage(1,.1F);
        assertNull(MetalPreviousVertexHistory.matchedPreviousSourceUniforms(first));
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();stage(99,.9F);MetalPreviousVertexHistory.discardFrame();
        MetalPreviousVertexHistory.beginFrame();var current=stage(2,.2F);
        var previous=MetalPreviousVertexHistory.matchedPreviousSourceUniforms(current);assertNotNull(previous);
        assertEquals(.1F,previous.gameTime(),0);assertEquals(1,previous.modelOffset().x,0);
        assertEquals(.2F,MetalPreviousVertexHistory.currentSourceUniforms(current).gameTime(),0);
        MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();var next=stage(3,.3F);
        assertEquals(.2F,MetalPreviousVertexHistory.matchedPreviousSourceUniforms(next).gameTime(),0);
        MetalPreviousVertexHistory.reset();assertNull(MetalPreviousVertexHistory.currentSourceUniforms(next));
    }

    @Test
    void previousMaterialNeedsTheWholeOwnerManifestAndEverySourceBinding() {
        MetalPreviousVertexHistory.beginFrame();stage(1,.1F);stage(1,.1F);MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();var first=stage(2,.2F);
        assertNull(MetalPreviousVertexHistory.matchedPreviousSourceUniforms(first));
        var second=stage(2,.2F);assertNotNull(MetalPreviousVertexHistory.matchedPreviousSourceUniforms(first));
        MetalPreviousVertexHistory.stageSourceUniforms(second,null);
        assertNull(MetalPreviousVertexHistory.matchedPreviousSourceUniforms(first));
        MetalPreviousVertexHistory.stageSourceUniforms(second,MetalSourceUniforms.decode(dynamic(2),globals(.4F,.2F)));
        assertNotNull(MetalPreviousVertexHistory.matchedPreviousSourceUniforms(first));
        MetalPreviousVertexHistory.stageDrawTransform(second,null);
        assertNull(MetalPreviousVertexHistory.currentSourceUniforms(second));
        assertNull(MetalPreviousVertexHistory.matchedPreviousSourceUniforms(first));
    }

    private static MetalPreviousVertexHistory.DrawToken stage(float offset,float time) {
        var token=MetalPreviousVertexHistory.reserveDraw(SAMPLE,SOURCE);var format=DefaultVertexFormat.ENTITY;
        MetalPreviousVertexHistory.stageSnapshot(token,new MetalPreviousVertexHistory.Signature(token.pipelineKey(),format.getElements(),
                format.getVertexSize(),PrimitiveTopology.TRIANGLES,1,3),new float[]{0,0,-1});
        MetalPreviousVertexHistory.stageDrawTransform(token,new MetalPreviousVertexHistory.DrawTransform(new Matrix4f(),new Matrix4f()));
        MetalPreviousVertexHistory.stageSourceUniforms(token,MetalSourceUniforms.decode(dynamic(offset),globals(.4F,time)));
        return token;
    }

    private static byte[] dynamic(float offset) {
        byte[] value=new byte[160];ByteBuffer b=ByteBuffer.allocateDirect(160).order(ByteOrder.nativeOrder());
        new Matrix4f().translation(offset,0,0).get(0,b);new Matrix4f().translation(.3F,0,0).get(64,b);
        b.putFloat(128,1).putFloat(132,.5F).putFloat(136,.75F).putFloat(140,.25F);
        b.putFloat(144,offset).putFloat(148,4).putFloat(152,5);b.get(0,value);return value;
    }
    private static byte[] globals(float alpha,float time) {
        byte[] value=new byte[48];ByteBuffer b=bytes(value);
        b.putInt(0,22).putInt(4,70).putInt(8,-33).putFloat(12,alpha);
        b.putFloat(16,.1F).putFloat(20,.2F).putFloat(24,.3F).putFloat(28,time);
        b.putFloat(32,854).putFloat(36,480).putInt(40,5).putInt(44,1);return value;
    }
    private static ByteBuffer bytes(byte[] value) {return ByteBuffer.wrap(value).order(ByteOrder.nativeOrder());}
}
