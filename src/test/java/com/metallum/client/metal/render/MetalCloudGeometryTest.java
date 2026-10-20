package com.metallum.client.metal.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MetalCloudGeometryTest {
    @Test
    void packedSignedCoordinatesParityAndInsideWindingReproduceTheDocumentedSourceFaces() {
        // Explicit independent corner table from core/clouds.vsh's documented cube faces.
        float[][][] corners={{{1,0,0},{1,0,1},{0,0,1},{0,0,0}},{{0,1,0},{0,1,1},{1,1,1},{1,1,0}},
                {{0,0,0},{0,1,0},{1,1,0},{1,0,0}},{{1,0,1},{1,1,1},{0,1,1},{0,0,1}},
                {{0,0,1},{0,1,1},{0,1,0},{0,0,0}},{{1,0,0},{1,1,0},{1,1,1},{1,0,1}}};
        for(int direction=0;direction<6;direction++)for(boolean inside:new boolean[]{false,true})for(int cell:new int[]{-17,-1,0,1,18}) {
            byte[] face=face(cell,-cell,direction|(inside ? 16 : 0));
            var mesh=MetalCloudGeometry.decode(face,info(),new MetalCloudGeometry.Origin(0,0));assertNotNull(mesh);
            for(int vertex=0;vertex<4;vertex++) {
                float[] c=corners[direction][inside ? 3-vertex : vertex];
                assertArrayEquals(new float[]{(c[0]+cell)*12-.25F,c[1]*4+2,(c[2]-cell)*12-.5F},
                        java.util.Arrays.copyOfRange(mesh.positions(),vertex*3,vertex*3+3),0);
            }
        }
    }

    @Test
    void stableFaceIdentitySurvivesCameraCellAndTexturePeriodTransitions() {
        var first=MetalCloudGeometry.origin(11.99,0,0,0,256,256,0,0);
        var moved=MetalCloudGeometry.origin(12.01,0,0,0,256,256,1,0);
        assertEquals(0,first.x());assertEquals(1,moved.x());
        assertEquals(MetalCloudGeometry.decode(face(1,0,0),info(),first).faceKeys(),
                MetalCloudGeometry.decode(face(0,0,0),info(),moved).faceKeys());
        var before=MetalCloudGeometry.origin(0,0,102399,0,256,256,255,0);
        var after=MetalCloudGeometry.origin(0,0,102400,0,256,256,0,0);
        assertEquals(255,before.x());assertEquals(256,after.x());
        assertEquals(MetalCloudGeometry.decode(face(1,0,0),info(),before).faceKeys(),
                MetalCloudGeometry.decode(face(0,0,0),info(),after).faceKeys());
        assertNotEquals(MetalCloudGeometry.decode(face(0,0,0),info(),after).faceKeys(),
                MetalCloudGeometry.decode(face(0,0,16),info(),after).faceKeys());
        assertEquals(-1,MetalCloudGeometry.origin(0,0,-1,0,256,256,255,0).x());
        // Source subtraction can round a tiny negative camera coordinate to the
        // texture's upper boundary. Preserve its actual cell, without re-dividing by 12.
        assertEquals(0,MetalCloudGeometry.origin(-1e-14,0,0,0,256,256,256,0).x());
    }

    @Test
    void malformedOrAmbiguousFacesAndUnprovenUniformValuesCannotClaimHistory() {
        var origin=new MetalCloudGeometry.Origin(0,0);byte[] info=info();
        assertNull(MetalCloudGeometry.decode(new byte[2],info,origin));
        assertNull(MetalCloudGeometry.decode(new byte[]{0,0,6},info,origin));
        assertNull(MetalCloudGeometry.decode(new byte[]{0,0,8},info,origin));
        assertNull(MetalCloudGeometry.decode(new byte[6],info,origin));
        assertNull(MetalCloudGeometry.decode(face(1,0,0),info,new MetalCloudGeometry.Origin(Long.MAX_VALUE,0)));
        ByteBuffer.wrap(info).order(ByteOrder.nativeOrder()).putFloat(12,.5001F);
        assertNull(MetalCloudGeometry.decode(face(0,0,0),info,origin));
        info=info();ByteBuffer.wrap(info).order(ByteOrder.nativeOrder()).putFloat(24,Float.NaN);
        assertNull(MetalCloudGeometry.decode(face(0,0,0),info,origin));
    }

    @Test
    void immutableGeometryRetainsActualAlphaAndNeverExposesSourceArrays() {
        byte[] info=info(),faces=face(0,0,0);var mesh=MetalCloudGeometry.decode(faces,info,new MetalCloudGeometry.Origin(0,0));
        assertNotNull(mesh);float p=mesh.positions()[0];info[0]=0;faces[0]=127;mesh.positions()[0]=999;mesh.vertices()[0]=99;
        assertEquals(p,mesh.positions()[0],0);
        assertEquals(153,Byte.toUnsignedInt(mesh.vertices()[15]));
    }

    static byte[] face(int x,int z,int flags) {return new byte[]{(byte)(x>>1),(byte)(z>>1),(byte)(flags|((x&1)<<7)|((z&1)<<6))};}
    static byte[] info() {
        ByteBuffer b=ByteBuffer.allocate(MetalCloudGeometry.INFO_BYTES).order(ByteOrder.nativeOrder());
        for(int i=0;i<3;i++)b.putFloat(i*4,1);b.putFloat(12,.6F).putFloat(16,-.25F).putFloat(20,2).putFloat(24,-.5F);
        b.putFloat(32,12).putFloat(36,4).putFloat(40,12);return b.array();
    }
}
