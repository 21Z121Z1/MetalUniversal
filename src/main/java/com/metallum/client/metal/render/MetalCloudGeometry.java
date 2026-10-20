package com.metallum.client.metal.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Decoder of Minecraft 26.3's actual signed RGB8 cloud face stream. */
final class MetalCloudGeometry {
    static final int INFO_BYTES=44;
    private static final int[] PACKED={0xF03CC3,0x6666F0,0xC3F066};
    record Origin(long x,long z) {}
    record FaceKey(long x,long z,int flags) {}
    record Mesh(float[] positions,byte[] vertices,List<FaceKey> faceKeys) {
        Mesh {positions=positions.clone();vertices=vertices.clone();faceKeys=List.copyOf(faceKeys);}
        @Override public float[] positions() {return positions.clone();}
        @Override public byte[] vertices() {return vertices.clone();}
    }
    private MetalCloudGeometry() {}

    /** Full-time cell identity survives both texture torus wrapping and mesh rebuilds. */
    static @Nullable Origin origin(double cameraX,double cameraZ,long time,float partial,int textureWidth,int textureHeight,
            int sourceCellX,int sourceCellZ) {
        if(textureWidth<=0 || textureHeight<=0 || sourceCellX<0 || sourceCellX>textureWidth || sourceCellZ<0 || sourceCellZ>textureHeight
                || !Double.isFinite(cameraX) || !Double.isFinite(cameraZ) || !Float.isFinite(partial))return null;
        long ticksPerPeriod=textureWidth*400L;
        float phase=(float)(time%ticksPerPeriod)+partial;
        double x=Math.floor((cameraX+phase*0.030000001F)/(textureWidth*12.0));
        double z=Math.floor((cameraZ+3.96F)/(textureHeight*12.0));
        try {
            if(x<Long.MIN_VALUE || x>=Long.MAX_VALUE || z<Long.MIN_VALUE || z>=Long.MAX_VALUE)return null;
            long fullX=Math.addExact(Math.multiplyExact((long)x,(long)textureWidth),sourceCellX);
            return new Origin(Math.addExact(fullX,Math.multiplyExact(time/ticksPerPeriod,(long)textureWidth)),
                    Math.addExact(Math.multiplyExact((long)z,(long)textureHeight),sourceCellZ));
        } catch(ArithmeticException invalid) {return null;}
    }

    static @Nullable Mesh decode(byte[] faces,byte[] sourceInfo,Origin origin) {
        if(faces.length==0 || faces.length%3!=0 || sourceInfo.length!=INFO_BYTES || origin==null)return null;
        ByteBuffer info=ByteBuffer.wrap(sourceInfo).order(ByteOrder.nativeOrder());
        for(int offset:new int[]{0,4,8,12,16,20,24,32,36,40})if(!Float.isFinite(info.getFloat(offset)))return null;
        float alpha=info.getFloat(12);
        int encodedAlpha=Math.round(alpha*255);
        // The real source colour is ARGB. Reject an unproven custom floating alpha.
        if(encodedAlpha<0 || encodedAlpha>255 || Math.abs(encodedAlpha/255F-alpha)>1e-7F)return null;
        float[] offsets={info.getFloat(16),info.getFloat(20),info.getFloat(24)};
        float[] size={info.getFloat(32),info.getFloat(36),info.getFloat(40)};
        // Fixed integer vanilla dimensions make source float arithmetic exactly reproducible.
        if(size[0]!=12 || size[1]!=4 || size[2]!=12)return null;
        int count=faces.length/3;
        int stride=com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR.getVertexSize();
        byte[] vertices=new byte[Math.multiplyExact(count,4*stride)];
        ByteBuffer output=ByteBuffer.wrap(vertices).order(ByteOrder.nativeOrder());
        float[] positions=new float[Math.multiplyExact(count,12)];
        var keys=new ArrayList<FaceKey>(count);var unique=new HashSet<FaceKey>();
        for(int face=0;face<count;face++) {
            int flags=Byte.toUnsignedInt(faces[face*3+2]),direction=flags&7;
            if(direction>5 || (flags&8)!=0)return null;
            int x=(faces[face*3]<<1)|((flags>>>7)&1),z=(faces[face*3+1]<<1)|((flags>>>6)&1);
            long worldX,worldZ;
            try {worldX=Math.addExact(origin.x,x);worldZ=Math.addExact(origin.z,z);}catch(ArithmeticException invalid){return null;}
            // Full tuple equality, without a lossy hash/coordinate bit budget.
            var key=new FaceKey(worldX,worldZ,flags&0x37);
            if(!unique.add(key))return null;
            keys.add(key);
            for(int vertex=0;vertex<4;vertex++) {
                int index=direction*4+((flags&16)!=0 ? 3-vertex : vertex);
                int out=(face*4+vertex)*stride;
                for(int axis=0;axis<3;axis++) {
                    int corner=(PACKED[axis]>>>index)&1;
                    int cell=axis==0 ? x : axis==2 ? z : 0;
                    float position=(corner*size[axis]+cell*size[axis])+offsets[axis];
                    if(!Float.isFinite(position))return null;
                    output.putFloat(out+axis*4,position);positions[face*12+vertex*3+axis]=position;
                }
                // Only alpha affects cloud visibility; RGB remains renderer-owned source colour.
                output.putInt(out+12,0x00FFFFFF|(encodedAlpha<<24));
            }
        }
        return new Mesh(positions,vertices,keys);
    }
}
