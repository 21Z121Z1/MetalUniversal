package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MetalSourceFrameIdentityTest {
    @Test void sourceFramesKeepAdvancingAfterOptionalCaptureNumberingStops() {
        long previous=8;var seen=new java.util.HashSet<Long>();
        for(int i=0;i<20;i++) {
            var next=FrameSynthesisContract.nextSourceFrame(previous,23);
            assertEquals(9+i,next.frameId());assertEquals(23,next.historyEpoch());
            assertTrue(seen.add(next.frameId()));previous=next.frameId();
        }
    }

    @Test void exhaustionStartsANewHistoryEpochAndInvalidInputsCannotCreateAStamp() {
        assertEquals(new FrameSynthesisContract.FrameStamp(1,24),FrameSynthesisContract.nextSourceFrame(Long.MAX_VALUE,23));
        assertEquals(new FrameSynthesisContract.FrameStamp(1,1),FrameSynthesisContract.nextSourceFrame(Long.MAX_VALUE,Long.MAX_VALUE));
        assertEquals(new FrameSynthesisContract.FrameStamp(1,23),FrameSynthesisContract.nextSourceFrame(0,23));
        assertThrows(IllegalArgumentException.class,()->FrameSynthesisContract.nextSourceFrame(-1,23));
        assertThrows(IllegalArgumentException.class,()->FrameSynthesisContract.nextSourceFrame(8,0));
    }
}
