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
        var accepted=m.snapshot();assertTrue(accepted.matches(2,3));assertFalse(accepted.matches(2,4));assertFalse(accepted.matches(3,3));
        m.reset();m.begin(3,3);m.record("entity#variantA",true,ACTUAL,1);
        assertFalse(m.snapshot().linkageComplete());assertTrue(accepted.matches(2,3));
    }
    @Test void newCustomShaderOrOwnerCannotHideBehindCumulativeSuccessOrAnAliasedLocation() {
        var m=new MetalMotionFrameManifest();m.begin(1,1);complete(m,"same/location#ordinary");
        m.record("same/location#custom",true,ACTUAL,1);
        assertFalse(m.snapshot().linkageComplete());assertEquals(2,m.snapshot().programs().size());
        m.record("same/location#custom",true,HOOKED,1);
        assertTrue(m.snapshot().rejectedReasons().stream().anyMatch(x->x.endsWith(":unowned-source-draw")));
        m.record("same/location#custom",true,OWNED,1);
        assertTrue(m.snapshot().rejectedReasons().stream().anyMatch(x->x.endsWith(":exact-motion-not-encoded")));
    }
    @Test void plannedMotionInvalidOwnershipOrIndirectCommandsDoNotProveCompleteMotion() {
        var m=new MetalMotionFrameManifest();m.begin(1,1);complete(m,"particle");
        m.record("particle",true,DISOCCLUSION_ONLY,1);assertFalse(m.snapshot().linkageComplete());
        m.reset();m.begin(2,1);complete(m,"terrain");m.record("terrain",true,INDIRECT,1);
        assertFalse(m.snapshot().linkageComplete());
    }
    @Test void internalReplaysAreNotSourceDrawsAndDepthOnlyPassesNeedNoColorOwnership() {
        var m=new MetalMotionFrameManifest();m.begin(1,1);complete(m,"entity");
        m.record("depth-only",false,ACTUAL,1);assertTrue(m.snapshot().linkageComplete());
        assertThrows(UnsupportedOperationException.class,()->m.snapshot().programs().clear());
        assertThrows(IllegalStateException.class,()->m.begin(2,1));
    }
    @Test void boundedInventoryAndConflictingColorSemanticsFailClosed() {
        var m=new MetalMotionFrameManifest();m.begin(1,1);complete(m,"entity");
        m.record("entity",false,ACTUAL,1);assertFalse(m.snapshot().linkageComplete());
        m.reset();m.begin(2,1);for(int i=0;i<1025;i++)complete(m,"custom#"+i);
        assertEquals(1024,m.snapshot().programs().size());assertTrue(m.snapshot().overflow());assertFalse(m.snapshot().linkageComplete());
    }
}
