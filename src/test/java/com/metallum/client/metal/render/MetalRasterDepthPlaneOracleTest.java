package com.metallum.client.metal.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MetalRasterDepthPlaneOracleTest {
    @Test
    void independentRasterPlaneRecoversBothGradientsFromAnIrregularVisibleMask() {
        int width=13,height=9;byte[] color=new byte[width*height*4];
        ByteBuffer depth=ByteBuffer.allocate(width*height*4).order(ByteOrder.nativeOrder());
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            int p=y*width+x;boolean covered=(x+y)%3!=0&&x>1&&y<7;
            color[p*4+3]=covered ? (byte)255 : 0;
            depth.putFloat(p*4,covered ? (float)(.4+.003*(x+.5)-.007*(y+.5)) : 1);
        }
        var plane=MetalMotionProducerGpuTest.fitRasterDepthPlane(color,depth.array(),width,height);
        assertEquals(.003,plane.xSlope(),1e-8);assertEquals(-.007,plane.ySlope(),1e-8);
        assertEquals(.4,plane.constant(),1e-8);assertTrue(plane.maxResidual()<2e-8);
        assertEquals(.4+.003*3.2-.007*5.7,plane.at(3.2,5.7),1e-8);
        assertTrue(Math.abs(plane.at(3.2,5.7)-(.4+.007*3.2-.003*5.7))>1e-3,"Swapped current-plane slopes must fail");
    }

    @Test
    void emptyOrCollinearCoverageCannotClaimAnIndependentDepthPlane() {
        assertThrows(IllegalArgumentException.class,()->MetalMotionProducerGpuTest.fitRasterDepthPlane(new byte[16],new byte[16],2,2));
        byte[] row=new byte[16];for(int p=0;p<4;p++)row[p*4+3]=(byte)255;
        assertThrows(IllegalArgumentException.class,()->MetalMotionProducerGpuTest.fitRasterDepthPlane(row,new byte[16],4,1));
    }
}
