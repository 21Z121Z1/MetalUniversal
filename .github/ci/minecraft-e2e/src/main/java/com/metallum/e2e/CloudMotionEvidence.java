package com.metallum.e2e;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Independent pixel census of world/HUD channels, including clouds without opaque scene depth. */
final class CloudMotionEvidence {
    record Pixels(long worldSurfaceOutsideHud,long reactiveWorldOutsideHud,long transparentWorldOutsideHud,
                  long opaqueDepthOutsideHud,long hudCovered) {}

    static Pixels inspect(Path root,int width,int height) throws IOException {
        int count=Math.multiplyExact(width,height);
        if(width<=0 || height<=0)throw new IllegalArgumentException("Invalid raw motion extent");
        byte[] world=read(root.resolve("object-validity.r8"),count);
        byte[] hud=read(root.resolve("hand-exact-validity.r8"),count);
        ByteBuffer wd=ByteBuffer.wrap(read(root.resolve("preserved-world-depth.d32f"),Math.multiplyExact(count,4))).order(ByteOrder.nativeOrder());
        ByteBuffer hd=ByteBuffer.wrap(read(root.resolve("isolated-hand-depth.d32f"),Math.multiplyExact(count,4))).order(ByteOrder.nativeOrder());
        long exact=0,reactive=0,transparent=0,opaque=0,covered=0;
        for(int pixel=0;pixel<count;pixel++) {
            int w=Byte.toUnsignedInt(world[pixel]),h=Byte.toUnsignedInt(hud[pixel]);
            if(!wireTag(w) || !wireTag(h))throw new IllegalStateException("Unknown raw ownership tag");
            float worldDepth=wd.getFloat(pixel*4),hudDepth=hd.getFloat(pixel*4);
            if(!Float.isFinite(worldDepth) || !Float.isFinite(hudDepth)
                    || worldDepth<0 || worldDepth>1 || hudDepth<0 || hudDepth>1)
                throw new IllegalStateException("Invalid raw ownership depth");
            // 159/191 are reactive overlay/ordinary tags in either channel;
            // the separate channel, rather than the tag, owns the camera domain.
            boolean hudPresent=h!=0 || hudDepth>1e-7F;
            if(hudPresent)covered++;
            if(worldDepth>0 && hudDepth==0)opaque++;
            if(hudPresent || !(w==159 || w==191 || w==223 || w==255))continue;
            exact++;
            if(worldDepth==0)transparent++;
            if(w==159 || w==191)reactive++;
        }
        return new Pixels(exact,reactive,transparent,opaque,covered);
    }

    private static byte[] read(Path path,int size) throws IOException {
        if(Files.size(path)!=size)throw new IOException("Raw ownership extent mismatch: "+path);
        return Files.readAllBytes(path);
    }
    private static boolean wireTag(int value) {return switch(value) {case 0,64,96,159,191,223,255->true;default->false;};}
    private CloudMotionEvidence() {}
}
