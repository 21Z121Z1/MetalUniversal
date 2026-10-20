package com.metallum.client.metal.render;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MetalRawMotionEvidenceTest {
    private static MetalMotionDrawCoverage.Census census(long frame,long epoch,boolean closed) {
        var source=new MetalMotionFrameManifest.Snapshot(frame,epoch,true,closed,false,
                Map.of("sky",new MetalMotionFrameManifest.Draws(true,1,0,0,0,0,0)),List.of("sky:unlinked-source-draw"));
        return new MetalMotionDrawCoverage.Census(true,Map.of(),Map.of(),Map.of(),null,null,source,null);
    }

    @Test void lateGpuCompletionKeepsTheOriginalSourceFrameAndUnprovenProducer() {
        var pending=new MetalRawMotionEvidence("source-A",new FrameSynthesisContract.FrameStamp(7,3));
        assertThrows(IllegalStateException.class,pending::requireSnapshot);
        var clouds=new java.util.HashMap<String,MetalCloudMotion.LayerDiagnostics>();
        clouds.put("cloud",new MetalCloudMotion.LayerDiagnostics(2,16,true,true,true,"none"));
        var live=new java.util.concurrent.atomic.AtomicReference<>(census(7,3,true));
        pending.freeze(live.get(),clouds);
        live.set(census(8,4,true));clouds.clear();
        var captured=pending.requireSnapshot();
        assertEquals("source-A",captured.sourceCommit());assertEquals(7,captured.frameId());assertEquals(3,captured.historyEpoch());
        assertTrue(captured.diagnosticOnly());
        assertEquals(7,captured.sourceCensus().sourceFrame().frameId());assertEquals(1,captured.cloudLayers().size());
        assertFalse(captured.sourceCensus().sourceFrame().linkageComplete(),"Diagnostic capture does not grant full motion admission");
        assertThrows(UnsupportedOperationException.class,()->captured.cloudLayers().clear());
        assertThrows(IllegalStateException.class,()->pending.freeze(census(7,3,true),Map.of()));
        assertSame(captured,pending.requireSnapshot());
    }

    @Test void wrongFrameEpochOrOpenObservationNeverReplaceTheCapture() {
        var pending=new MetalRawMotionEvidence("source-A",new FrameSynthesisContract.FrameStamp(7,3));
        for(var source:List.of(census(8,3,true),census(7,4,true),census(7,3,false))) {
            assertThrows(IllegalStateException.class,()->pending.freeze(source,Map.of()));
            assertThrows(IllegalStateException.class,pending::requireSnapshot);
        }
        pending.freeze(census(7,3,true),Map.of());
        assertEquals(7,pending.requireSnapshot().frameId());
    }
}
