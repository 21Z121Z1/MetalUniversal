package com.metallum.client.metal.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.oit.OitStage;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class MetalTemporalSourceSafetyTest {
    private final FrameSynthesisContract.FrameStamp source=new FrameSynthesisContract.FrameStamp(81,7);
    private final MetalEntityMotionCapture.Sample owner=new MetalEntityMotionCapture.Sample(91,4,new Matrix4f(),new Matrix4f());
    private final MetalPreviousVertexReplay.SourceState state=new MetalPreviousVertexReplay.SourceState(
            MetalParticlePartialAlphaCases.pipeline(true),Map.of(),null);

    @BeforeEach void begin() {
        MetalEntityMotionCapture.setEnabled(true);MetalEntityMotionCapture.beginFrame();
        MetalMotionDrawCoverage.bindSourceFrame(source);MetalMotionDrawCoverage.beginScene();
    }
    @AfterEach void end() {MetalMotionDrawCoverage.endScene();MetalEntityMotionCapture.beginFrame();MetalPreviousVertexHistory.reset();}
    private void actual() {MetalMotionDrawCoverage.observeOpaqueActual(state.pipeline(),1,false);}
    private boolean admitted() {return MetalFxManager.temporalSourceFrameAdmitted(source,false);}
    private static StagedVertexBuffer.ExecuteInfo info() {
        return new StagedVertexBuffer.ExecuteInfo(null,null,IndexType.SHORT,0,0,6,PrimitiveTopology.QUADS);
    }

    @Test void noOpaqueDrawDoesNotRequireFullFgManifestOrAnyExactDraw() {
        assertTrue(admitted());assertFalse(MetalMotionDrawCoverage.sourceFrameLinked(source));
        assertFalse(MetalFxManager.temporalSourceFrameAdmitted(source,true));
    }
    @Test void actualWithoutAnyHookIsRejected() {actual();assertFalse(admitted());}
    @Test void hookCannotInventAnActualSourceExecution() {
        MetalMotionDrawCoverage.hookedDraw(state,null,owner);assertFalse(admitted());
    }
    @Test void ownedInvalidIsSafeWithoutPreviousExactCoverage() {
        actual();MetalMotionDrawCoverage.hookedDraw(state,null,owner);
        MetalMotionDrawCoverage.ownedInvalidEncoded(owner,state.pipeline());
        assertTrue(admitted());assertEquals(0,MetalMotionDrawCoverage.snapshot().encodedReplays());
        assertEquals(1,MetalMotionDrawCoverage.snapshot().encodedDisocclusionReplays());
    }
    @Test void missingCaptureAndLateWriteAfterPreparationRevokeTheCurrentFrameOnly() {
        actual();MetalMotionDrawCoverage.hookedDraw(state,null,owner);assertTrue(admitted());
        // A prepared V4 result cannot cache the earlier decision across a later real source draw.
        actual();assertNull(MetalEntityMotionCapture.takeExecute(info(),null,state));assertFalse(admitted());
        assertEquals(1,MetalMotionDrawCoverage.opaqueTemporalSource().missingCapture());
        MetalEntityMotionCapture.beginFrame();
        var next=new FrameSynthesisContract.FrameStamp(82,7);MetalMotionDrawCoverage.bindSourceFrame(next);
        assertTrue(MetalFxManager.temporalSourceFrameAdmitted(next,false));assertFalse(admitted());
    }
    @Test void duplicateColorIsDistinguishedFromMissingCaptureAndIsConservative() {
        MetalEntityMotionCapture.beginParticleBatchBuild(owner);
        try(var staged=new StagedVertexBuffer(()->"owned source",64)) {
            var draw=staged.appendDraw(DefaultVertexFormat.PARTICLE,PrimitiveTopology.QUADS);
            MetalEntityMotionCapture.attachDraw(draw,state.pipeline());
            var first=info();MetalEntityMotionCapture.transferExecute(draw,first);actual();
            assertSame(owner,MetalEntityMotionCapture.takeExecute(first,null,state));assertTrue(admitted());
            var duplicate=info();MetalEntityMotionCapture.transferExecute(draw,duplicate);actual();
            assertNull(MetalEntityMotionCapture.takeExecute(duplicate,null,state));assertFalse(admitted());
            var read=MetalMotionDrawCoverage.opaqueTemporalSource();assertEquals(1,read.duplicateColor());assertEquals(0,read.missingCapture());
        } finally {MetalEntityMotionCapture.endModelBuild();}
    }
    @Test void endedStagedLifetimeCannotReuseAnOldOwner() {
        MetalEntityMotionCapture.beginParticleBatchBuild(owner);
        try(var staged=new StagedVertexBuffer(()->"retired source",64)) {
            var draw=staged.appendDraw(DefaultVertexFormat.PARTICLE,PrimitiveTopology.QUADS);
            MetalEntityMotionCapture.attachDraw(draw,state.pipeline());var pending=info();
            MetalEntityMotionCapture.transferExecute(draw,pending);MetalEntityMotionCapture.endStagedDraws(List.of(draw));actual();
            assertNull(MetalEntityMotionCapture.takeExecute(pending,null,state));assertFalse(admitted());
            assertEquals(1,MetalMotionDrawCoverage.opaqueTemporalSource().missingCapture());
        } finally {MetalEntityMotionCapture.endModelBuild();}
    }
    @Test void oitAndOtherFamiliesDoNotAcquireTheNewOpaqueRequirement() {
        var oit=new MetalPreviousVertexReplay.SourceState(MetalParticlePartialAlphaCases.pipeline(false),Map.of(),null);
        MetalMotionDrawCoverage.observeOpaqueActual(oit.pipeline(),1,false);
        MetalMotionDrawCoverage.hookedDraw(oit,OitStage.ACCUMULATE,null);
        assertTrue(admitted());assertEquals(0,MetalMotionDrawCoverage.opaqueTemporalSource().actual());
    }
    @Test void replayDrawsAndNonSceneDrawsAreNotOriginalDenominators() {
        MetalMotionDrawCoverage.beginMotionReplay();actual();MetalMotionDrawCoverage.hookedDraw(state,null,null);
        MetalMotionDrawCoverage.endMotionReplay();MetalMotionDrawCoverage.endScene();actual();
        MetalMotionDrawCoverage.hookedDraw(state,null,null);assertTrue(admitted());
    }
    @Test void nullOldFrameResizeReloadAndMissingBindingAreRejected() {
        assertFalse(MetalFxManager.temporalSourceFrameAdmitted(null,false));
        assertFalse(MetalFxManager.temporalSourceFrameAdmitted(new FrameSynthesisContract.FrameStamp(80,7),false));
        assertFalse(MetalFxManager.temporalSourceFrameAdmitted(new FrameSynthesisContract.FrameStamp(81,8),false));
        MetalEntityMotionCapture.beginFrame();assertFalse(admitted());
    }
    @Test void sourceRejectionDiscardAndLaterSuccessfulSubmitRetainActualHistoryTransactions() {
        var store=new MetalMotionStateStore();var key=new MetalMotionStateStore.ObjectKey(91,4);
        try {
            store.beginFrame();store.observe(key,new Matrix4f().translation(1,0,0));store.commitSubmittedFrame();
            store.beginFrame();store.observe(key,new Matrix4f().translation(2,0,0));actual();
            MetalEntityMotionCapture.takeExecute(info(),null,state);assertFalse(admitted());store.discardFrame();
            assertEquals(1,store.previous(key).m30());
            MetalEntityMotionCapture.beginFrame();var next=new FrameSynthesisContract.FrameStamp(82,7);
            MetalMotionDrawCoverage.bindSourceFrame(next);MetalMotionDrawCoverage.beginScene();
            store.beginFrame();store.observe(key,new Matrix4f().translation(3,0,0));actual();MetalMotionDrawCoverage.hookedDraw(state,null,owner);
            assertTrue(MetalFxManager.temporalSourceFrameAdmitted(next,false));
            store.discardFrame();assertEquals(1,store.previous(key).m30(),"Failed submission cannot advance accepted history");
            store.beginFrame();store.observe(key,new Matrix4f().translation(4,0,0));store.commitSubmittedFrame();
            assertEquals(4,store.previous(key).m30());
        } finally {store.discardFrame();store.reset();}
    }
    @Test void indirectAndCounterOverflowFailClosedWithoutAliasingTheNextStamp() {
        var safety=new MetalTemporalSourceSafety();safety.begin(1,2);
        safety.actual(1,true);safety.hook(true,"owned");assertFalse(safety.snapshot().admits(1,2));
        safety.reset();safety.begin(2,2);safety.actual(Long.MAX_VALUE,false);safety.actual(1,false);
        assertTrue(safety.snapshot().overflow());assertFalse(safety.snapshot().admits(2,2));
        assertThrows(IllegalStateException.class,()->safety.begin(3,2));
        safety.reset();safety.begin(3,3);assertTrue(safety.snapshot().admits(3,3));
    }
}
