package com.metallum.client.metal.render;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MetalWeatherMotionHistoryTest {
    @AfterEach void reset() {MetalWeatherMotion.reset();}

    @Test
    void preparedGeometryIsNotPreviousHistoryUntilItsRealSourceFrameIsAccepted() {
        Object renderer=new Object();var rain=MetalWeatherMotion.Kind.RAIN;
        stage(renderer,rain,1);MetalWeatherMotion.beginFrame();
        assertFalse(MetalWeatherMotion.sample(renderer,rain).hasPrevious());
        MetalWeatherMotion.commitSubmittedFrame(); // CPU preparation without a real draw cannot seed history.
        assertNull(MetalWeatherMotion.previousColumn(renderer,rain,7));
        MetalWeatherMotion.beginFrame();MetalWeatherMotion.sourceSubmitted(renderer,rain);MetalWeatherMotion.commitSubmittedFrame();
        assertEquals(1,MetalWeatherMotion.previousColumn(renderer,rain,7).time(),0);
        stage(renderer,rain,2);MetalWeatherMotion.beginFrame();assertTrue(MetalWeatherMotion.sample(renderer,rain).hasPrevious());
    }

    @Test
    void failedSubmittedFramePreservesTheLastAcceptedAnimationAndGeometry() {
        Object renderer=new Object();var rain=MetalWeatherMotion.Kind.RAIN;
        stage(renderer,rain,1);MetalWeatherMotion.beginFrame();MetalWeatherMotion.sourceSubmitted(renderer,rain);MetalWeatherMotion.commitSubmittedFrame();
        stage(renderer,rain,99);MetalWeatherMotion.beginFrame();MetalWeatherMotion.sourceSubmitted(renderer,rain);MetalWeatherMotion.discardFrame();
        stage(renderer,rain,3);MetalWeatherMotion.beginFrame();
        assertEquals(1,MetalWeatherMotion.previousColumn(renderer,rain,7).time(),0);
        MetalWeatherMotion.sourceSubmitted(renderer,rain);MetalWeatherMotion.commitSubmittedFrame();
        assertEquals(3,MetalWeatherMotion.previousColumn(renderer,rain,7).time(),0);
    }

    @Test
    void rainSnowAndRendererInstancesHaveIndependentOwnersAndDisappearWithRealFrames() {
        Object first=new Object(),second=new Object();var rain=MetalWeatherMotion.Kind.RAIN;var snow=MetalWeatherMotion.Kind.SNOW;
        stage(first,rain,1);stage(first,snow,1);stage(second,rain,1);MetalWeatherMotion.beginFrame();
        long id=MetalWeatherMotion.sample(first,rain).objectId();
        assertNotEquals(id,MetalWeatherMotion.sample(first,snow).objectId());assertNotEquals(id,MetalWeatherMotion.sample(second,rain).objectId());
        MetalWeatherMotion.sourceSubmitted(first,rain);MetalWeatherMotion.sourceSubmitted(first,snow);MetalWeatherMotion.commitSubmittedFrame();
        MetalWeatherMotion.beginFrame();MetalWeatherMotion.sourceSubmitted(first,snow);MetalWeatherMotion.commitSubmittedFrame();
        assertNull(MetalWeatherMotion.previousColumn(first,rain,7));assertNotNull(MetalWeatherMotion.previousColumn(first,snow,7));
        assertNull(MetalWeatherMotion.previousColumn(second,rain,7));
    }

    @Test
    void historyResetKeepsTheActualPreparedSourceButDropsItsPredecessorAndCloseReleasesIt() {
        Object renderer=new Object();var rain=MetalWeatherMotion.Kind.RAIN;
        stage(renderer,rain,1);MetalWeatherMotion.beginFrame();MetalWeatherMotion.sourceSubmitted(renderer,rain);MetalWeatherMotion.commitSubmittedFrame();
        long id=MetalWeatherMotion.sample(renderer,rain).objectId();
        MetalWeatherMotion.resetHistory();MetalWeatherMotion.beginFrame();
        assertNotNull(MetalWeatherMotion.sample(renderer,rain));assertFalse(MetalWeatherMotion.sample(renderer,rain).hasPrevious());
        assertEquals(id,MetalWeatherMotion.sample(renderer,rain).objectId());
        MetalWeatherMotion.closeRenderer(renderer);assertNull(MetalWeatherMotion.sample(renderer,rain));
    }

    @Test
    void quadHistoryAcceptsOriginAndNegativeCoordinateKeysWithoutRelaxingParticleIds() {
        var source=net.minecraft.client.renderer.RenderPipelines.WEATHER;
        var format=com.mojang.blaze3d.vertex.DefaultVertexFormat.PARTICLE;
        var sample=new MetalEntityMotionCapture.Sample(817,-17,new org.joml.Matrix4f(),new org.joml.Matrix4f());
        try {
            for(int frame=0;frame<2;frame++) {
                MetalPreviousVertexHistory.beginFrame();var token=MetalPreviousVertexHistory.reserveDraw(sample,source);
                var signature=new MetalPreviousVertexHistory.Signature(token.pipelineKey(),format.getElements(),format.getVertexSize(),
                        com.mojang.renderpearl.api.pipeline.PrimitiveTopology.QUADS,8,12);
                float[] positions=new float[24];java.util.Arrays.fill(positions,frame==0 ? 3 : 7);
                MetalPreviousVertexHistory.stageSnapshot(token,signature,positions);
                MetalPreviousVertexHistory.stageQuadIdentities(token,List.of(0L,-1L));
                if(frame==0)MetalPreviousVertexHistory.commitSubmittedFrame();
                else {
                    assertEquals(3,MetalPreviousVertexHistory.matchedPreviousPositions(token)[0],0);
                    MetalPreviousVertexHistory.stageParticleIdentities(token,List.of(0L,-1L));
                    assertNull(MetalPreviousVertexHistory.matchedPreviousPositions(token));
                }
            }
        } finally {MetalPreviousVertexHistory.discardFrame();MetalPreviousVertexHistory.reset();}
    }

    @Test
    void malformedUploadCannotReuseFormerSourceBytesOrUnmatchedColumnRates() {
        Object renderer=new Object();var rain=MetalWeatherMotion.Kind.RAIN;var snow=MetalWeatherMotion.Kind.SNOW;
        boolean enabled=MetalEntityMotionCapture.isEnabled();
        try {
            MetalEntityMotionCapture.setEnabled(true);
            var state=new net.minecraft.client.renderer.state.level.WeatherRenderState();
            state.rainColumns.add(new net.minecraft.client.renderer.WeatherEffectRenderer.ColumnInstance(0,0,0,4,0,0,0));
            stage(renderer,rain,1);stage(renderer,snow,1);
            MetalWeatherMotion.prepare(renderer,state,java.nio.ByteBuffer.allocate(1),java.util.Map.of());
            assertNull(MetalWeatherMotion.sample(renderer,rain));assertNull(MetalWeatherMotion.sample(renderer,snow));
            stage(renderer,rain,1);
            int bytes=4*com.mojang.blaze3d.vertex.DefaultVertexFormat.PARTICLE.getVertexSize();
            MetalWeatherMotion.prepare(renderer,state,java.nio.ByteBuffer.allocate(bytes),java.util.Map.of());
            assertNull(MetalWeatherMotion.sample(renderer,rain),"New vertices without their actual random rates cannot inherit an older prepared mesh");
        } finally {MetalEntityMotionCapture.setEnabled(enabled);}
    }

    @Test
    void uploadWhileDisabledInvalidatesPreparedBytesBeforeCaptureCanBeEnabledAgain() {
        Object renderer=new Object();var rain=MetalWeatherMotion.Kind.RAIN;var snow=MetalWeatherMotion.Kind.SNOW;
        boolean enabled=MetalEntityMotionCapture.isEnabled();
        try {
            stage(renderer,rain,1);stage(renderer,snow,1);
            assertNotNull(MetalWeatherMotion.sample(renderer,rain));assertNotNull(MetalWeatherMotion.sample(renderer,snow));
            MetalEntityMotionCapture.setEnabled(false);
            assertFalse(MetalWeatherMotion.collecting());
            // This is a real replacement upload whose bytes/rates were not
            // observed. Keeping either old prepared range would invent source geometry.
            MetalWeatherMotion.prepare(renderer,new net.minecraft.client.renderer.state.level.WeatherRenderState(),
                    java.nio.ByteBuffer.allocate(0),java.util.Map.of());
            MetalEntityMotionCapture.setEnabled(true);
            assertNull(MetalWeatherMotion.sample(renderer,rain));assertNull(MetalWeatherMotion.sample(renderer,snow));
            stage(renderer,rain,2);
            assertNotNull(MetalWeatherMotion.sample(renderer,rain),"Only a newly observed upload can restore a prepared source");
        } finally {MetalEntityMotionCapture.setEnabled(enabled);}
    }

    @Test
    void cameraCellUsesAFiniteDegenerateQuadWithoutChangingOtherWeatherDirections() {
        float[] x=new float[1024],z=new float[1024];
        for(int row=0;row<32;row++)for(int column=0;column<32;column++) {
            float dx=column-16,dz=row-16,distance=(float)Math.sqrt(dx*dx+dz*dz);
            x[row*32+column]=-dz/distance;z[row*32+column]=dx/distance;
        }
        float[] originalX=x.clone(),originalZ=z.clone();assertTrue(Float.isNaN(x[528]));
        MetalWeatherMotion.repairZeroRadiusColumn(x,z);
        assertEquals(0,x[528],0);assertEquals(0,z[528],0);
        for(int p=0;p<1024;p++)if(p!=528) {assertEquals(originalX[p],x[p],0);assertEquals(originalZ[p],z[p],0);}
    }

    private static void stage(Object renderer,MetalWeatherMotion.Kind kind,double time) {
        var column=new MetalWeatherCorrespondence.Column(7,time,0,0,(float)(-.125*time),0,-.125,
                new float[]{0,4,0,1,4,0,1,0,0,0,0,0},new float[]{0,0,1,0,1,1,0,1});
        MetalWeatherMotion.stagePrepared(renderer,kind,List.of(column),kind==MetalWeatherMotion.Kind.RAIN ? 0 : 1,new byte[4*28]);
    }
}
