package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

final class MetalWeatherCorrespondenceTest {
    @Test
    void fallingRainMapsTheSampledPatternAboveItsCurrentVertices() {
        var old=column(7,0,0,0,0,-.125,0,4);
        var current=column(7,1,0,0,-.125F, -.125,0,4);
        var quad=MetalWeatherCorrespondence.reproject(old,current,1,1);
        assertTrue(quad.hasCorrespondence());
        assertArrayEquals(new float[]{0,4.5F,0,1,4.5F,0,1,.5F,0,0,.5F,0},quad.positions(),1e-6F);
        assertArrayEquals(new float[]{0,-.125F,1,-.125F,1,.875F,0,.875F},quad.previousCoverage(),1e-6F);
        assertTrue(quad.previousCoverage()[1]<0,"New top pixels need fragment-level previous coverage rejection");
        assertTrue(quad.previousCoverage()[5]<1,"Surviving parts retain their old surface rather than invalidating the whole column");
    }

    @Test
    void actualRepeatAddressingUnwrapsTheVanillaThirtyTwoUnitRainPhase() {
        var old=column(7,0,0,0,-31.98F,-.125,0,4);
        var current=column(7,1,0,0,-.105F,-.125,0,4);
        var repeated=MetalWeatherCorrespondence.reproject(old,current,1,1);
        assertTrue(repeated.hasCorrespondence());assertEquals(4.5,repeated.positions()[1],1e-5);
        var clamped=MetalWeatherCorrespondence.reproject(old,current,0,0);
        assertTrue(clamped.hasCorrespondence());assertTrue(clamped.previousCoverage()[1]>30,
                "A nonperiodic sampler cannot claim the same wrapped feature");
        var mirrored=MetalWeatherCorrespondence.reproject(column(7,0,0,0,-1.98F,-.125,0,4),current,2,2);
        assertEquals(4.5,mirrored.positions()[1],1e-5);
    }

    @Test
    void horizontalSnowDriftAndChangingColumnBoundsUseActualUvs() {
        var old=column(7,0,0,0,0,0,1,5);
        var current=new MetalWeatherCorrespondence.Column(7,1,0,.25F,0,.25,0,
                new float[]{0,6,0,1,6,0,1,2,0,0,2,0},new float[]{.25F,.5F,1.25F,.5F,1.25F,1.5F,.25F,1.5F});
        var oldWithDrift=new MetalWeatherCorrespondence.Column(old.id(),old.time(),old.phaseEpoch(),old.uOffset(),old.vOffset(),
                .25,0,old.positions(),old.uv());
        var result=MetalWeatherCorrespondence.reproject(oldWithDrift,current,1,1);
        assertTrue(result.hasCorrespondence());
        assertArrayEquals(new float[]{.25F,4,0,1.25F,4,0,1.25F,0,0,.25F,0,0},result.positions(),1e-6F);
    }

    @Test
    void birthsTimeResetAndSourcePhaseResetNeverBorrowAnUnrelatedColumn() {
        var old=column(7,10,0,0,0,-.125,0,4);
        assertFalse(MetalWeatherCorrespondence.reproject(null,old,1,1).hasCorrespondence());
        assertFalse(MetalWeatherCorrespondence.reproject(old,column(8,11,0,0,0,-.125,0,4),1,1).hasCorrespondence());
        assertFalse(MetalWeatherCorrespondence.reproject(old,column(7,9,0,0,0,-.125,0,4),1,1).hasCorrespondence());
        assertFalse(MetalWeatherCorrespondence.reproject(old,column(7,11,1,0,0,-.125,0,4),1,1).hasCorrespondence());
        assertFalse(MetalWeatherCorrespondence.reproject(old,column(7,11,0,0,0,-.25,0,4),1,1).hasCorrespondence());
    }

    @Test
    void malformedAndMutableSourceArraysCannotChangeAcceptedCorrespondence() {
        var old=column(7,0,0,0,0,-.125,0,4);var current=column(7,1,0,0,-.125F,-.125,0,4);
        old.positions()[0]=999;old.uv()[0]=999;
        assertEquals(0,MetalWeatherCorrespondence.reproject(old,current,1,1).positions()[0],0);
        var bad=new MetalWeatherCorrespondence.Column(7,0,0,0,0,0,-.125,new float[12],new float[8]);
        assertFalse(MetalWeatherCorrespondence.reproject(bad,current,1,1).hasCorrespondence());
        var vertices=old.positions();vertices[0]=Float.NaN;
        var nonfinite=new MetalWeatherCorrespondence.Column(7,0,0,0,0,0,-.125,vertices,old.uv());
        var result=MetalWeatherCorrespondence.reproject(nonfinite,current,1,1);
        assertFalse(result.hasCorrespondence());for(float p:result.positions())assertTrue(Float.isNaN(p));
        var nonaffine=old.positions();nonaffine[8]=.1F;
        assertFalse(MetalWeatherCorrespondence.reproject(new MetalWeatherCorrespondence.Column(
                7,0,0,0,0,0,-.125,nonaffine,old.uv()),current,1,1).hasCorrespondence());
    }

    @Test
    void weatherVertexUploadKeepsFractionalOldBoundsAndNeverUploadsCpuNanSentinels() {
        var quad=MetalWeatherCorrespondence.reproject(column(7,0,0,0,0,-.125,0,4),column(7,1,0,0,-.125F,-.125,0,4),1,1);
        ByteBuffer bytes=ByteBuffer.allocate(4*24).order(ByteOrder.nativeOrder());
        assertTrue(MetalPreviousVertexReplay.writePreviousPositions(quad.positions(),quad.previousCoverage(),bytes));
        assertEquals(1,bytes.getFloat(12),0);assertEquals(-.125,bytes.getFloat(20),0);
        var missing=MetalWeatherCorrespondence.reproject(null,column(7,0,0,0,0,-.125,0,4),1,1);
        bytes.clear();assertFalse(MetalPreviousVertexReplay.writePreviousPositions(missing.positions(),missing.previousCoverage(),bytes));
        for(int offset=0;offset<bytes.capacity();offset+=4)assertEquals(0,bytes.getFloat(offset),0);
        var source=net.minecraft.client.renderer.RenderPipelines.WEATHER;
        var pipeline=MetalEntityMotionPipeline.forPreviousPositionsWithCoverage(source,true);
        assertEquals(24,pipeline.getVertexFormatBinding(1).getVertexSize());
        assertTrue(pipeline.getShaderDefines().flags().contains("METALLUM_PREVIOUS_COVERAGE"));
        assertEquals(16,MetalEntityMotionPipeline.forPreviousPositionsWithDepth(source).getVertexFormatBinding(1).getVertexSize());
    }

    private static MetalWeatherCorrespondence.Column column(long id,double time,long epoch,float u,float v,double rate,int bottom,int top) {
        return new MetalWeatherCorrespondence.Column(id,time,epoch,u,v,0,rate,
                new float[]{0,top,0,1,top,0,1,bottom,0,0,bottom,0},
                new float[]{u,bottom*.25F+v,u+1,bottom*.25F+v,u+1,top*.25F+v,u,top*.25F+v});
    }
}
