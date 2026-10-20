package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.metallum.client.metal.render.MetalMotionFrameManifest.Event.*;

final class MetalMotionFrameManifestTest {
    private static void complete(MetalMotionFrameManifest manifest,String program) {
        for(var event:new MetalMotionFrameManifest.Event[]{ACTUAL,HOOKED,OWNED,EXACT})manifest.record(program,true,event,1);
    }
    @Test void exactLinkageRequiresTheSameFrameAndEpochAndCannotBeVacuous() {
        var m=new MetalMotionFrameManifest();assertThrows(IllegalArgumentException.class,()->m.begin(1,0));assertFalse(m.snapshot().linkageComplete());
        m.begin(2,3);assertFalse(m.snapshot().linkageComplete());
        m.record("depth",false,ACTUAL,1);assertFalse(m.snapshot().linkageComplete());complete(m,"entity#variantA");
        assertFalse(m.snapshot().linkageComplete());m.closeObservation();
        var accepted=m.snapshot();assertTrue(accepted.matches(2,3));assertFalse(accepted.matches(2,4));assertFalse(accepted.matches(3,3));
        m.reset();m.begin(3,3);m.record("entity#variantA",true,ACTUAL,1);
        assertFalse(m.snapshot().linkageComplete());assertTrue(accepted.matches(2,3));
    }
    @Test void newCustomShaderOrOwnerCannotHideBehindCumulativeSuccessOrAnAliasedLocation() {
        String[] reasons={"unlinked-source-draw","unowned-source-draw","exact-motion-not-encoded"};
        for(int stage=0;stage<3;stage++) {
            var m=new MetalMotionFrameManifest();m.begin(1,1);complete(m,"same/location#ordinary");
            m.record("same/location#custom",true,ACTUAL,1);
            if(stage>0)m.record("same/location#custom",true,HOOKED,1);
            if(stage>1)m.record("same/location#custom",true,OWNED,1);
            m.closeObservation();var snapshot=m.snapshot();
            assertFalse(snapshot.linkageComplete());assertFalse(snapshot.overflow());assertTrue(snapshot.closed());
            assertEquals(2,snapshot.programs().size());
            assertTrue(snapshot.rejectedReasons().contains("same/location#custom:"+reasons[stage]));
        }
    }
    @Test void plannedMotionInvalidOwnershipOrIndirectCommandsDoNotProveCompleteMotion() {
        var m=new MetalMotionFrameManifest();m.begin(1,1);complete(m,"particle");
        m.record("particle",true,DISOCCLUSION_ONLY,1);m.closeObservation();assertFalse(m.snapshot().linkageComplete());
        assertTrue(m.snapshot().rejectedReasons().contains("particle:disocclusion-only"));
        m.reset();m.begin(2,1);complete(m,"terrain");m.record("terrain",true,INDIRECT,1);m.closeObservation();
        assertTrue(m.snapshot().rejectedReasons().contains("terrain:indirect-command-correspondence-unproven"));
        assertFalse(m.snapshot().linkageComplete());
    }
    @Test void internalReplaysAreNotSourceDrawsAndDepthOnlyPassesNeedNoColorOwnership() {
        var m=new MetalMotionFrameManifest();m.begin(1,1);complete(m,"entity");
        m.record("depth-only",false,ACTUAL,1);m.closeObservation();assertTrue(m.snapshot().linkageComplete());
        assertThrows(UnsupportedOperationException.class,()->m.snapshot().programs().clear());
        assertThrows(IllegalStateException.class,()->m.begin(2,1));
    }
    @Test void sourceEventsAfterObservationClosedInvalidateTheLiveProof() {
        var m=new MetalMotionFrameManifest();m.begin(1,1);complete(m,"entity");m.closeObservation();
        assertTrue(m.snapshot().matches(1,1));
        m.record("entity",true,EXACT,1);
        assertFalse(m.snapshot().closed());assertTrue(m.snapshot().overflow());assertFalse(m.snapshot().matches(1,1));
        m.closeObservation();assertFalse(m.snapshot().linkageComplete(),"Closing again cannot repair a late source event");
    }
    @Test void boundedInventoryAndConflictingColorSemanticsFailClosed() {
        var m=new MetalMotionFrameManifest();m.begin(1,1);complete(m,"entity");
        m.record("entity",false,ACTUAL,1);assertFalse(m.snapshot().linkageComplete());
        m.reset();m.begin(2,1);for(int i=0;i<1025;i++)complete(m,"custom#"+i);
        assertEquals(1024,m.snapshot().programs().size());assertTrue(m.snapshot().overflow());assertFalse(m.snapshot().linkageComplete());
    }
}
