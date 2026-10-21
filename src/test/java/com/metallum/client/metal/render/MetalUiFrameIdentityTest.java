package com.metallum.client.metal.render;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MetalUiFrameIdentityTest {
    private static MetalUiDrawCoverage.Snapshot ui(long frame,long epoch) {
        var content=new MetalUiContentProof();content.clear(0,0,0,0,true,true);
        return new MetalUiDrawCoverage.Snapshot(frame,epoch,13,2,true,false,Map.of(),false,false,false,content.snapshot());
    }

    @Test void currentOrCompletedUiMustMatchBothSourceFrameAndEpoch() {
        var current=ui(7,3);var previous=ui(6,3);
        assertSame(current,MetalUiDrawCoverage.forSourceFrame(7,3,current,previous));
        var nextFrame=ui(8,3);
        assertSame(current,MetalUiDrawCoverage.forSourceFrame(7,3,nextFrame,current));
        assertSame(current,MetalUiDrawCoverage.forSourceFrame(7,3,ui(7,4),current));
    }

    @Test void reusedFrameIdFromAnotherEpochCannotSupplyStaleUiProof() {
        var stale=MetalUiDrawCoverage.forSourceFrame(7,3,ui(7,4),ui(7,2));
        assertEquals(0,stale.frameId());assertEquals(0,stale.allocation());
        assertFalse(stale.content().premultipliedClearOnlyProven());assertFalse(stale.premultipliedProven());
        assertFalse(stale.shaderOutputRangeProven());assertFalse(stale.completeWriteCoverageProven());
        assertEquals("ui-not-observed-for-source-frame",stale.content().reason());
        assertEquals(0,MetalUiDrawCoverage.forSourceFrame(7,3,ui(8,3),ui(6,3)).frameId());
        assertEquals(0,MetalUiDrawCoverage.forSourceFrame(0,0,ui(0,0),ui(0,0)).frameId());
    }
}
