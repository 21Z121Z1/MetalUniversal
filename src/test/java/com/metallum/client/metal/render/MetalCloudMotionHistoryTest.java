package com.metallum.client.metal.render;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MetalCloudMotionHistoryTest {
    private boolean captureWasEnabled;
    private static final MetalCloudGeometry.FaceKey A=new MetalCloudGeometry.FaceKey(0,0,0),B=new MetalCloudGeometry.FaceKey(1,0,0);
    @BeforeEach void setup() {captureWasEnabled=MetalEntityMotionCapture.isEnabled();MetalEntityMotionCapture.setEnabled(true);}
    @AfterEach void cleanup() {MetalCloudMotion.reset();MetalPreviousVertexHistory.reset();MetalEntityMotionCapture.setEnabled(captureWasEnabled);}

    @Test
    void cpuMeshPreparationCannotSeedHistoryWithoutAnAcceptedSourceDraw() {
        Object renderer=new Object();prepare(renderer);
        MetalCloudMotion.beginFrame();MetalCloudMotion.commitSubmittedFrame();
        assertFalse(MetalCloudMotion.sample(renderer).hasPrevious());
        MetalCloudMotion.beginFrame();var ids=MetalCloudMotion.identities(renderer,List.of(A,B));
        MetalCloudMotion.sourceSubmitted(renderer,ids);MetalCloudMotion.commitSubmittedFrame();
        assertTrue(MetalCloudMotion.sample(renderer).hasPrevious());
        var reordered=MetalCloudMotion.identities(renderer,List.of(B,A));
        assertEquals(ids.get(A),reordered.get(A));assertEquals(ids.get(B),reordered.get(B));
    }

    @Test
    void rejectedFramesKeepOldIdentitiesAndAcceptedAbsenceRetiresThem() {
        Object renderer=new Object();prepare(renderer);
        MetalCloudMotion.beginFrame();var first=MetalCloudMotion.identities(renderer,List.of(A));
        MetalCloudMotion.sourceSubmitted(renderer,first);MetalCloudMotion.commitSubmittedFrame();
        MetalCloudMotion.beginFrame();MetalCloudMotion.sourceSubmitted(renderer,MetalCloudMotion.identities(renderer,List.of(B)));
        MetalCloudMotion.discardFrame();assertEquals(first.get(A),MetalCloudMotion.identities(renderer,List.of(A)).get(A));
        MetalCloudMotion.beginFrame();MetalCloudMotion.commitSubmittedFrame();
        assertFalse(MetalCloudMotion.sample(renderer).hasPrevious());
        assertNotEquals(first.get(A),MetalCloudMotion.identities(renderer,List.of(A)).get(A));
    }

    @Test
    void resetReloadAndRendererReplacementCannotBorrowRetiredCloudFaces() {
        Object first=new Object(),second=new Object();prepare(first);prepare(second);
        assertNotEquals(MetalCloudMotion.sample(first).objectId(),MetalCloudMotion.sample(second).objectId());
        var before=MetalCloudMotion.sample(first);
        MetalCloudMotion.beginFrame();var ids=MetalCloudMotion.identities(first,List.of(A));
        MetalCloudMotion.sourceSubmitted(first,ids);MetalCloudMotion.commitSubmittedFrame();
        MetalCloudMotion.resetHistory();assertNotNull(MetalCloudMotion.sample(first));assertFalse(MetalCloudMotion.sample(first).hasPrevious());
        assertTrue(MetalCloudMotion.hasFaces(first));
        MetalCloudMotion.reloaded(first);assertFalse(MetalCloudMotion.hasFaces(first));assertNull(MetalCloudMotion.sample(first));
        prepare(first);assertNotEquals(before.generation(),MetalCloudMotion.sample(first).generation());
        MetalCloudMotion.closeRenderer(first);assertFalse(MetalCloudMotion.hasFaces(first));assertNull(MetalCloudMotion.sample(first));
    }

    @Test
    void tupleIdentityKeepsFarCoordinatesOrientationAndInsideWindingDistinct() {
        Object renderer=new Object();prepare(renderer);
        var far=new MetalCloudGeometry.FaceKey(1L<<40,-(1L<<39),0);
        var inside=new MetalCloudGeometry.FaceKey(0,0,16);
        var ids=MetalCloudMotion.identities(renderer,List.of(A,far,inside));
        assertEquals(3,new java.util.HashSet<>(ids.values()).size());
        MetalCloudMotion.beginFrame();MetalCloudMotion.sourceSubmitted(renderer,ids);MetalCloudMotion.commitSubmittedFrame();
        ids.clear();assertEquals(3,MetalCloudMotion.identities(renderer,List.of(A,far,inside)).size());
    }

    @Test
    void realCloudQuadAbiRemapsSurvivorsAndMarksNewFacesWithoutWeakeningParticleAdmission() {
        var source=net.minecraft.client.renderer.RenderPipelines.CLOUDS;var format=com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR;
        var sample=new MetalEntityMotionCapture.Sample(9921,-31,new org.joml.Matrix4f(),new org.joml.Matrix4f());
        for(int frame=0;frame<2;frame++) {
            MetalPreviousVertexHistory.beginFrame();var token=MetalPreviousVertexHistory.reserveDraw(sample,source);
            int count=frame==0 ? 2 : 3;float[] positions=new float[count*12];
            java.util.Arrays.fill(positions,0,12,11);java.util.Arrays.fill(positions,12,24,22);
            MetalPreviousVertexHistory.stageSnapshot(token,new MetalPreviousVertexHistory.Signature(token.pipelineKey(),format.getElements(),
                    format.getVertexSize(),com.mojang.renderpearl.api.pipeline.PrimitiveTopology.QUADS,count*4,count*6),positions);
            MetalPreviousVertexHistory.stageQuadIdentities(token,frame==0 ? List.of(1L,2L) : List.of(2L,3L,1L));
            if(frame==0)MetalPreviousVertexHistory.commitSubmittedFrame();
            else {
                var old=MetalPreviousVertexHistory.matchedPreviousPositions(token);assertNotNull(old);
                assertEquals(22,old[0],0);assertTrue(Float.isNaN(old[12]));assertEquals(11,old[24],0);
                MetalPreviousVertexHistory.stageParticleIdentities(token,List.of(2L,3L,1L));
                assertNull(MetalPreviousVertexHistory.matchedPreviousPositions(token));
            }
        }
    }

    @Test
    void failedObservedMeshReplacementCannotReuseOldFacesButKeepsAcceptedIdentity() {
        Object renderer=new Object();prepare(renderer);
        MetalCloudMotion.beginFrame();var ids=MetalCloudMotion.identities(renderer,List.of(A));
        MetalCloudMotion.sourceSubmitted(renderer,ids);MetalCloudMotion.commitSubmittedFrame();
        MetalCloudMotion.beginFrame();MetalCloudMotion.beginMesh(renderer);
        MetalCloudMotion.prepared(renderer,0,0,1,0,256,256,0,0);
        assertFalse(MetalCloudMotion.hasFaces(renderer));assertNull(MetalCloudMotion.sample(renderer));
        MetalCloudMotion.discardFrame();
        prepare(renderer);
        assertTrue(MetalCloudMotion.sample(renderer).hasPrevious());
        assertEquals(ids.get(A),MetalCloudMotion.identities(renderer,List.of(A)).get(A));
    }

    @Test
    void unobservedMeshReplacementRetiresGenerationEvenWhenBackingIsReused() {
        Object renderer=new Object();prepare(renderer);var before=MetalCloudMotion.sample(renderer);
        MetalCloudMotion.beginFrame();var ids=MetalCloudMotion.identities(renderer,List.of(A));
        MetalCloudMotion.sourceSubmitted(renderer,ids);MetalCloudMotion.commitSubmittedFrame();
        MetalEntityMotionCapture.setEnabled(false);MetalCloudMotion.beginMesh(renderer);
        assertFalse(MetalCloudMotion.hasFaces(renderer));
        MetalEntityMotionCapture.setEnabled(true);prepare(renderer);
        var after=MetalCloudMotion.sample(renderer);
        assertNotEquals(before.generation(),after.generation());assertFalse(after.hasPrevious());
        assertNotEquals(ids.get(A),MetalCloudMotion.identities(renderer,List.of(A)).get(A));
    }

    private static void prepare(Object renderer) {MetalCloudMotion.faces(renderer,new byte[3],null);MetalCloudMotion.prepared(renderer,0,0,0,0,256,256,0,0);}

    @Test
    void bulkMeshSnapshotCannotRetainAnEmptyOrFailedEmitterAsAValidCapturedMesh() {
        Object renderer=new Object();prepare(renderer);assertTrue(MetalCloudMotion.hasFaces(renderer));
        MetalCloudMotion.beginMesh(renderer);
        var bytes=java.nio.ByteBuffer.allocateDirect(9);bytes.put(0,(byte)1).put(1,(byte)2).put(2,(byte)0);bytes.position(3);
        MetalCloudMotion.facesFromMesh(renderer,bytes,null);
        assertTrue(MetalCloudMotion.hasFaces(renderer));
        MetalCloudMotion.beginMesh(renderer);bytes.position(0);MetalCloudMotion.facesFromMesh(renderer,bytes,null);
        assertFalse(MetalCloudMotion.hasFaces(renderer));assertNull(MetalCloudMotion.sample(renderer));
    }
}
