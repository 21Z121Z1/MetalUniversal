package com.metallum.e2e;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Independent pixel census of world/HUD channels, including clouds without opaque scene depth. */
final class CloudMotionEvidence {
    record Pixels(long worldSurfaceOutsideHud,long reactiveWorldOutsideHud,long transparentWorldOutsideHud,
                  long opaqueDepthOutsideHud,long hudCovered) {}

    static JsonObject sourceFrameEvidence(Path root,String source,long frameId,long epoch) throws IOException {
        var evidence=JsonParser.parseString(Files.readString(root.resolve("source-frame-evidence.json"))).getAsJsonObject();
        var census=object(evidence,"sourceCensus");
        var frame=object(census,"sourceFrame");
        object(evidence,"cloudLayers");
        var commit=evidence.get("sourceCommit");
        if(commit==null || !commit.isJsonPrimitive() || !commit.getAsJsonPrimitive().isString()
                || !commit.getAsString().equals(source)
                || !flag(evidence,"diagnosticOnly")
                || number(evidence,"frameId")!=frameId || number(evidence,"historyEpoch")!=epoch
                || number(frame,"frameId")!=frameId || number(frame,"epoch")!=epoch
                || !flag(frame,"collected") || !flag(frame,"closed")) {
            throw new IllegalStateException("Raw source census does not match the captured source SHA/frame/epoch");
        }
        return evidence;
    }

    private static JsonObject object(JsonObject parent,String name) {
        var value=parent.get(name);
        if(value==null || !value.isJsonObject())throw new IllegalStateException("Missing raw source evidence object "+name);
        return value.getAsJsonObject();
    }

    private static long number(JsonObject object,String name) {
        var value=object.get(name);
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalStateException("Missing or mistyped raw source identity "+name);
        try {return value.getAsBigDecimal().longValueExact();}
        catch(ArithmeticException invalid) {throw new IllegalStateException("Non-integral raw source identity "+name,invalid);}
    }

    private static boolean flag(JsonObject object,String name) {
        var value=object.get(name);
        return value!=null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }

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
