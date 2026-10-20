package com.metallum.client.metal.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class MetalParticleBatchMotionTest {
    private static final MetalParticleBatchMotion.LayerSignature AB=signature(false,"particles",List.of(11L,22L));
    private static final MetalParticleBatchMotion.LayerSignature BA=signature(false,"particles",List.of(22L,11L));

    private static MetalParticleBatchMotion.LayerSignature signature(boolean translucent,String atlas,List<Long> ids) {
        return new MetalParticleBatchMotion.LayerSignature(translucent,"minecraft:textures/atlas/"+atlas+".png",
                "minecraft:pipeline/"+(translucent ? "translucent" : "opaque")+"_particle","minecraft:core/particle",ids);
    }

    @AfterEach
    void reset() { MetalParticleBatchMotion.reset(); }

    @Test
    void layerSurvivesParticleBirthDeathAndReordering() {
        Object layer=new Object();
        MetalParticleBatchMotion.beginFrame();
        var first=MetalParticleBatchMotion.beginLayerBatch(layer,AB);
        assertNotNull(first);assertFalse(first.hasPrevious());assertTrue(first.generation()<0);
        MetalParticleBatchMotion.commitSubmittedFrame();
        MetalParticleBatchMotion.beginFrame();
        var reordered=MetalParticleBatchMotion.beginLayerBatch(layer,BA);
        assertTrue(reordered.hasPrevious());assertEquals(first.objectId(),reordered.objectId());assertEquals(first.generation(),reordered.generation());
        MetalParticleBatchMotion.commitSubmittedFrame();
        MetalParticleBatchMotion.beginFrame();
        var changed=MetalParticleBatchMotion.beginLayerBatch(layer,signature(false,"particles",List.of(22L,33L,44L)));
        assertTrue(changed.hasPrevious());assertEquals(first.generation(),changed.generation());
    }

    @Test
    void unrelatedLayerBirthDeathAndGroupOrderCannotResetSurvivors() {
        Object a=new Object(),b=new Object(),c=new Object();
        MetalParticleBatchMotion.beginFrame();
        var oldA=MetalParticleBatchMotion.beginLayerBatch(a,AB);
        var oldB=MetalParticleBatchMotion.beginLayerBatch(b,signature(true,"items",List.of(71L)));
        MetalParticleBatchMotion.commitSubmittedFrame();
        MetalParticleBatchMotion.beginFrame();
        assertFalse(MetalParticleBatchMotion.beginLayerBatch(c,signature(false,"blocks",List.of(91L))).hasPrevious());
        var newB=MetalParticleBatchMotion.beginLayerBatch(b,signature(true,"items",List.of(71L,72L)));
        var newA=MetalParticleBatchMotion.beginLayerBatch(a,BA);
        assertTrue(newA.hasPrevious()&&newB.hasPrevious());
        assertEquals(oldA.generation(),newA.generation());assertEquals(oldB.generation(),newB.generation());
        MetalParticleBatchMotion.commitSubmittedFrame();
        MetalParticleBatchMotion.beginFrame();
        var survivor=MetalParticleBatchMotion.beginLayerBatch(a,AB);
        assertTrue(survivor.hasPrevious());assertEquals(oldA.objectId(),survivor.objectId());
    }

    @Test
    void equalLayerValuesWithDifferentActualIdentityNeverAlias() {
        Object a=new String("same"),b=new String("same");assertEquals(a,b);
        MetalParticleBatchMotion.beginFrame();
        var first=MetalParticleBatchMotion.beginLayerBatch(a,AB);
        var other=MetalParticleBatchMotion.beginLayerBatch(b,AB);
        assertNotEquals(first.objectId(),other.objectId());
        MetalParticleBatchMotion.commitSubmittedFrame();
        MetalParticleBatchMotion.beginFrame();
        assertTrue(MetalParticleBatchMotion.beginLayerBatch(a,BA).hasPrevious());
        assertFalse(MetalParticleBatchMotion.beginLayerBatch(new String("same"),AB).hasPrevious());
    }

    @Test
    void repeatedGroupsUseOneLayerOwnerAndOneAcceptedHistory() {
        Object layer=new Object();
        MetalParticleBatchMotion.beginFrame();
        var first=MetalParticleBatchMotion.beginLayerBatch(layer,AB);
        var second=MetalParticleBatchMotion.beginLayerBatch(layer,BA);
        assertFalse(first.hasPrevious()||second.hasPrevious());
        assertEquals(first.objectId(),second.objectId());assertEquals(first.generation(),second.generation());
        MetalParticleBatchMotion.commitSubmittedFrame();
        MetalParticleBatchMotion.beginFrame();
        assertTrue(MetalParticleBatchMotion.beginLayerBatch(layer,BA).hasPrevious());
        assertTrue(MetalParticleBatchMotion.beginLayerBatch(layer,AB).hasPrevious());
    }

    @Test
    void materialChangesAndSubmittedLayerGapBreakOnlyThatLayer() {
        Object a=new Object(),b=new Object();
        MetalParticleBatchMotion.beginFrame();
        var old=MetalParticleBatchMotion.beginLayerBatch(a,AB);
        MetalParticleBatchMotion.beginLayerBatch(b,AB);
        MetalParticleBatchMotion.commitSubmittedFrame();
        MetalParticleBatchMotion.beginFrame();
        var changed=MetalParticleBatchMotion.beginLayerBatch(a,signature(false,"blocks",AB.particleIds()));
        assertFalse(changed.hasPrevious());assertNotEquals(old.generation(),changed.generation());
        assertTrue(MetalParticleBatchMotion.beginLayerBatch(b,AB).hasPrevious());
        MetalParticleBatchMotion.commitSubmittedFrame();
        MetalParticleBatchMotion.beginFrame();
        MetalParticleBatchMotion.beginLayerBatch(b,AB);
        MetalParticleBatchMotion.commitSubmittedFrame();
        MetalParticleBatchMotion.beginFrame();
        assertFalse(MetalParticleBatchMotion.beginLayerBatch(a,AB).hasPrevious());
        assertTrue(MetalParticleBatchMotion.beginLayerBatch(b,BA).hasPrevious());
    }

    @Test
    void discardedLayerChangeDoesNotReplaceAcceptedHistory() {
        Object layer=new Object();
        MetalParticleBatchMotion.beginFrame();
        var old=MetalParticleBatchMotion.beginLayerBatch(layer,AB);
        MetalParticleBatchMotion.commitSubmittedFrame();
        MetalParticleBatchMotion.beginFrame();
        MetalParticleBatchMotion.beginLayerBatch(layer,signature(true,"blocks",List.of(99L)));
        MetalParticleBatchMotion.discardFrame();
        MetalParticleBatchMotion.beginFrame();
        var recovered=MetalParticleBatchMotion.beginLayerBatch(layer,BA);
        assertTrue(recovered.hasPrevious());assertEquals(old.generation(),recovered.generation());
    }

    @Test
    void invalidLayerCannotBeResurrectedByAnotherGroupAndDoesNotInvalidateOthers() {
        Object a=new Object(),b=new Object();
        MetalParticleBatchMotion.beginFrame();
        MetalParticleBatchMotion.beginLayerBatch(a,AB);MetalParticleBatchMotion.beginLayerBatch(b,AB);
        MetalParticleBatchMotion.commitSubmittedFrame();
        MetalParticleBatchMotion.beginFrame();
        MetalParticleBatchMotion.beginLayerBatch(a,AB);MetalParticleBatchMotion.beginLayerBatch(b,AB);
        MetalParticleBatchMotion.invalidateLayer(a,"bad-source-group");
        assertNull(MetalParticleBatchMotion.beginLayerBatch(a,AB));
        MetalParticleBatchMotion.commitSubmittedFrame();
        MetalParticleBatchMotion.beginFrame();
        assertFalse(MetalParticleBatchMotion.beginLayerBatch(a,AB).hasPrevious());
        assertTrue(MetalParticleBatchMotion.beginLayerBatch(b,AB).hasPrevious());
    }

    @Test
    void changingDefinitionWithinOneSourceFrameInvalidatesAllOccurrences() {
        Object layer=new Object();
        MetalParticleBatchMotion.beginFrame();
        assertNotNull(MetalParticleBatchMotion.beginLayerBatch(layer,AB));
        assertNull(MetalParticleBatchMotion.beginLayerBatch(layer,signature(true,"blocks",List.of(11L))));
        assertNull(MetalParticleBatchMotion.beginLayerBatch(layer,AB));
        MetalParticleBatchMotion.commitSubmittedFrame();
        MetalParticleBatchMotion.beginFrame();
        assertFalse(MetalParticleBatchMotion.beginLayerBatch(layer,AB).hasPrevious());
    }

    @Test
    void emptyGeometryAndResetNeverClaimLayerContinuity() {
        Object layer=new Object();
        MetalParticleBatchMotion.beginFrame();
        assertNull(MetalParticleBatchMotion.beginLayerBatch(layer,signature(false,"particles",List.of())));
        var before=MetalParticleBatchMotion.beginLayerBatch(layer,AB);
        MetalParticleBatchMotion.commitSubmittedFrame();MetalParticleBatchMotion.reset();
        MetalParticleBatchMotion.beginFrame();
        var after=MetalParticleBatchMotion.beginLayerBatch(layer,AB);
        assertFalse(after.hasPrevious());assertNotEquals(before.objectId(),after.objectId());
    }

    @Test
    void particleObjectIdentityAllocatorIsPositiveAndUnique() {
        long first=MetalParticleBatchMotion.allocateParticleIdentity(),second=MetalParticleBatchMotion.allocateParticleIdentity();
        assertTrue(first>0);assertTrue(second>first);
    }
}
