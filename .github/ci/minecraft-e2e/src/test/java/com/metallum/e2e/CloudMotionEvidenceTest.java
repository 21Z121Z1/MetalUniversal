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
}
