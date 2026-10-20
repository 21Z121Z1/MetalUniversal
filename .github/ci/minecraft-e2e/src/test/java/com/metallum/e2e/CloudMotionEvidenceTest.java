package com.metallum.e2e;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class CloudMotionEvidenceTest {
    @TempDir Path root;
    private void fixture(int[] world,int[] hud,float[] worldDepth,float[] hudDepth) throws Exception {
        byte[] w=new byte[world.length],h=new byte[hud.length];
        ByteBuffer wd=ByteBuffer.allocate(world.length*4).order(ByteOrder.nativeOrder());
        ByteBuffer hd=ByteBuffer.allocate(hud.length*4).order(ByteOrder.nativeOrder());
        for(int i=0;i<world.length;i++){w[i]=(byte)world[i];h[i]=(byte)hud[i];wd.putFloat(worldDepth[i]);hd.putFloat(hudDepth[i]);}
        Files.write(root.resolve("object-validity.r8"),w);Files.write(root.resolve("hand-exact-validity.r8"),h);
        Files.write(root.resolve("preserved-world-depth.d32f"),wd.array());Files.write(root.resolve("isolated-hand-depth.d32f"),hd.array());
    }
    @Test void depthlessOrdinaryAndOverlayCloudsDoNotNeedOpaqueSceneDepth() throws Exception {
        fixture(new int[]{191,159},new int[]{0,0},new float[]{0,0},new float[]{0,0});
        var p=CloudMotionEvidence.inspect(root,2,1);
        assertEquals(2,p.worldSurfaceOutsideHud());assertEquals(2,p.reactiveWorldOutsideHud());
        assertEquals(2,p.transparentWorldOutsideHud());assertEquals(0,p.opaqueDepthOutsideHud());
    }
    @Test void mixedChannelsExcludeOpaqueTransparentAndInvalidHistoryHudCoverage() throws Exception {
        fixture(new int[]{191,159,255,64,0,191,191,191},new int[]{0,0,0,0,191,64,0,0},
                new float[]{0,0,.4F,0,0,0,.6F,0},new float[]{0,0,0,0,0,0,.3F,0});
        var p=CloudMotionEvidence.inspect(root,8,1);
        assertEquals(4,p.worldSurfaceOutsideHud());assertEquals(3,p.reactiveWorldOutsideHud());
        assertEquals(3,p.transparentWorldOutsideHud());assertEquals(1,p.opaqueDepthOutsideHud());assertEquals(3,p.hudCovered());
    }
    @Test void hudOnlyReactiveTagsCannotSatisfyCloudOwnership() throws Exception {
        fixture(new int[]{0,0,64},new int[]{159,191,0},new float[]{0,0,0},new float[]{0,0,0});
        var p=CloudMotionEvidence.inspect(root,3,1);
        assertEquals(0,p.worldSurfaceOutsideHud());assertEquals(0,p.reactiveWorldOutsideHud());
    }
    @Test void malformedExtentUnknownTagsAndNonfiniteDepthFailClosed() throws Exception {
        fixture(new int[]{191},new int[]{0},new float[]{0},new float[]{0});
        assertThrows(Exception.class,()->CloudMotionEvidence.inspect(root,2,1));
        fixture(new int[]{127},new int[]{0},new float[]{0},new float[]{0});
        assertThrows(IllegalStateException.class,()->CloudMotionEvidence.inspect(root,1,1));
        fixture(new int[]{191},new int[]{0},new float[]{Float.NaN},new float[]{0});
        assertThrows(IllegalStateException.class,()->CloudMotionEvidence.inspect(root,1,1));
    }

    private com.google.gson.JsonObject sourceFixture() throws Exception {
        var source=new com.google.gson.JsonObject();
        source.addProperty("sourceCommit","abc123");source.addProperty("frameId",7);source.addProperty("historyEpoch",3);
        source.addProperty("diagnosticOnly",true);
        var frame=new com.google.gson.JsonObject();frame.addProperty("frameId",7);frame.addProperty("epoch",3);
        frame.addProperty("collected",true);frame.addProperty("closed",true);
        frame.add("rejectedReasons",com.google.gson.JsonParser.parseString("[\"sky:unlinked-source-draw\"]"));
        var census=new com.google.gson.JsonObject();census.add("sourceFrame",frame);
        source.add("sourceCensus",census);source.add("cloudLayers",new com.google.gson.JsonObject());
        return source;
    }

    private void writeSource(com.google.gson.JsonObject source) throws Exception {
        Files.writeString(root.resolve("source-frame-evidence.json"),source.toString());
    }

    @Test void boundSourceInventoryRetainsPartialAdmissionWithoutReadingTheNextFrame() throws Exception {
        var source=sourceFixture();writeSource(source);
        var frozen=CloudMotionEvidence.sourceFrameEvidence(root,"abc123",7,3);
        assertEquals("sky:unlinked-source-draw",frozen.getAsJsonObject("sourceCensus").getAsJsonObject("sourceFrame")
                .getAsJsonArray("rejectedReasons").get(0).getAsString());
        source.addProperty("frameId",8);writeSource(source);
        assertEquals(7,frozen.get("frameId").getAsLong(),"A later disk snapshot cannot mutate the decoded capture");
        assertThrows(IllegalStateException.class,()->CloudMotionEvidence.sourceFrameEvidence(root,"abc123",7,3));
    }

    @Test void differentSourceFrameEpochAndUnclosedInventoryAreRejected() throws Exception {
        for(int failure=0;failure<7;failure++) {
            var source=sourceFixture();var frame=source.getAsJsonObject("sourceCensus").getAsJsonObject("sourceFrame");
            switch(failure) {
                case 0 -> source.addProperty("sourceCommit","different");
                case 1 -> source.addProperty("frameId",8);
                case 2 -> source.addProperty("historyEpoch",4);
                case 3 -> frame.addProperty("frameId",8);
                case 4 -> frame.addProperty("epoch",4);
                case 5 -> frame.addProperty("collected",false);
                case 6 -> frame.addProperty("closed",false);
            }
            writeSource(source);
            assertThrows(IllegalStateException.class,()->CloudMotionEvidence.sourceFrameEvidence(root,"abc123",7,3),"case "+failure);
        }
    }

    @Test void malformedIdentityCannotUseJsonCoercionOrMissingSnapshotAsAProof() throws Exception {
        for(int failure=0;failure<10;failure++) {
            var source=sourceFixture();var frame=source.getAsJsonObject("sourceCensus").getAsJsonObject("sourceFrame");
            switch(failure) {
                case 0 -> source.addProperty("frameId","7");
                case 1 -> source.addProperty("frameId",7.125);
                case 2 -> source.addProperty("historyEpoch",new java.math.BigInteger("9223372036854775808"));
                case 3 -> frame.addProperty("closed","true");
                case 4 -> source.remove("sourceCensus");
                case 5 -> source.getAsJsonObject("sourceCensus").remove("sourceFrame");
                case 6 -> source.remove("cloudLayers");
                case 7 -> source.addProperty("sourceCommit",123);
                case 8 -> source.addProperty("diagnosticOnly",false);
                case 9 -> source.addProperty("diagnosticOnly","true");
            }
            writeSource(source);
            assertThrows(IllegalStateException.class,()->CloudMotionEvidence.sourceFrameEvidence(root,"abc123",7,3),"case "+failure);
        }
        Files.delete(root.resolve("source-frame-evidence.json"));
        assertThrows(java.io.IOException.class,()->CloudMotionEvidence.sourceFrameEvidence(root,"abc123",7,3));
    }
}
