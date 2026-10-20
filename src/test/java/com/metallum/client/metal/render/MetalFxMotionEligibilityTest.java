package com.metallum.client.metal.render;

import net.minecraft.client.renderer.entity.state.ArrowRenderState;
import net.minecraft.client.renderer.entity.state.BoatRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.EvokerFangsRenderState;
import net.minecraft.client.renderer.entity.state.FallingBlockRenderState;
import net.minecraft.client.renderer.entity.state.FireworkRocketRenderState;
import net.minecraft.client.renderer.entity.state.ItemClusterRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.LlamaSpitRenderState;
import net.minecraft.client.renderer.entity.state.MinecartRenderState;
import net.minecraft.client.renderer.entity.state.MinecartTntRenderState;
import net.minecraft.client.renderer.entity.state.PaintingRenderState;
import net.minecraft.client.renderer.entity.state.ThrownItemRenderState;
import net.minecraft.client.renderer.entity.state.ThrownTridentRenderState;
import net.minecraft.client.renderer.entity.state.TntRenderState;
import net.minecraft.client.renderer.entity.state.WitherSkullRenderState;
import net.minecraft.world.entity.EntityTypeIds;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalFxMotionEligibilityTest {
    @Test
    void rigidCapturedFamiliesAreAdmitted() {
        assertEquals(0, MetalFxMotionEligibility.incompleteEntityReason(new ItemEntityRenderState()));
        assertEquals(0, MetalFxMotionEligibility.incompleteEntityReason(new MinecartRenderState()));
        assertEquals(0, MetalFxMotionEligibility.incompleteEntityReason(new ArrowRenderState()));
    }

    @Test
    void boatIsAnExactStagedGeometryCandidate() {
        assertEquals(0, MetalFxMotionEligibility.incompleteEntityReason(new BoatRenderState()));
    }

    @Test
    void livingEntityIsAnExactStagedGeometryCandidate() {
        assertEquals(0, MetalFxMotionEligibility.incompleteEntityReason(new LivingEntityRenderState()));
    }

    @Test
    void stagedItemAndModelProjectileFamiliesAreExactCandidates() {
        EntityRenderState[] candidates = {
                new ThrownItemRenderState(),
                new FireworkRocketRenderState(),
                new ItemClusterRenderState(),
                new ThrownTridentRenderState(),
                new WitherSkullRenderState(),
                new LlamaSpitRenderState(),
                new EvokerFangsRenderState()
        };
        for (EntityRenderState candidate : candidates) {
            assertEquals(0, MetalFxMotionEligibility.incompleteEntityReason(candidate));
            assertTrue(MetalFxMotionEligibility.requiresExactPreviousPositions(candidate));
        }
    }

    @Test
    void itemFrameIsWholeObjectExactCandidate() {
        ItemFrameRenderState frame = new ItemFrameRenderState();
        assertEquals(0, MetalFxMotionEligibility.incompleteEntityReason(frame));
        assertTrue(MetalFxMotionEligibility.requiresExactPreviousPositions(frame));
    }

    @Test
    void tntAndTntMinecartAreExactInsteadOfRigidCandidates() {
        EntityRenderState[] candidates = {new TntRenderState(), new MinecartTntRenderState()};
        for (EntityRenderState candidate : candidates) {
            assertEquals(0, MetalFxMotionEligibility.incompleteEntityReason(candidate));
            assertTrue(MetalFxMotionEligibility.requiresExactPreviousPositions(candidate));
        }
    }

    @Test
    void paintingIsAnExactCustomGeometryCandidate() {
        PaintingRenderState painting = new PaintingRenderState();
        assertEquals(0, MetalFxMotionEligibility.incompleteEntityReason(painting));
        assertTrue(MetalFxMotionEligibility.requiresExactPreviousPositions(painting));
    }

    @Test
    void genericStateAdmissionUsesRegistryKeysWithoutBootstrappingEntityTypes() {
        assertTrue(MetalFxMotionEligibility.isExactGenericEntityTypeKey(EntityTypeIds.DRAGON_FIREBALL));
        assertTrue(MetalFxMotionEligibility.isExactGenericEntityTypeKey(EntityTypeIds.LEASH_KNOT));
        assertTrue(MetalFxMotionEligibility.isExactGenericEntityTypeKey(EntityTypeIds.WIND_CHARGE));
        assertTrue(MetalFxMotionEligibility.isExactGenericEntityTypeKey(EntityTypeIds.BREEZE_WIND_CHARGE));
        assertFalse(MetalFxMotionEligibility.isExactGenericEntityTypeKey(EntityTypeIds.LIGHTNING_BOLT));
    }

    @Test
    void crystalBeamOrbBillboardAndBulletOverlayRequireCompleteExactHistory() {
        for (var state : java.util.List.of(new net.minecraft.client.renderer.entity.state.EndCrystalRenderState(),
                new net.minecraft.client.renderer.entity.state.ExperienceOrbRenderState(),
                new net.minecraft.client.renderer.entity.state.ShulkerBulletRenderState(),
                new net.minecraft.client.renderer.entity.state.LightningBoltRenderState(),
                new net.minecraft.client.renderer.entity.state.EnderDragonRenderState())) {
            assertTrue(MetalFxMotionEligibility.requiresExactPreviousPositions(state));
            assertEquals(0, MetalFxMotionEligibility.incompleteEntityReason(state));
        }
    }

    @Test
    void newLightningSeedBreaksSurfaceCorrespondenceWithoutRejectingOrdinaryCpuDeformation() {
        var bolt=new net.minecraft.client.renderer.entity.state.LightningBoltRenderState();bolt.seed=Long.MIN_VALUE;
        var accepted=new MetalFxManager.EntityMotionGeneration(41,bolt.getClass(),bolt.seed);
        assertTrue(accepted.matches(bolt));
        assertEquals(Long.MIN_VALUE,MetalFxMotionEligibility.geometryDiscontinuity(bolt));
        bolt.seed=19;assertEquals(19,MetalFxMotionEligibility.geometryDiscontinuity(bolt));
        assertFalse(accepted.matches(bolt));bolt.seed=Long.MIN_VALUE;assertTrue(accepted.matches(bolt));
        assertFalse(accepted.matches(new EntityRenderState()));
        assertEquals(0,MetalFxMotionEligibility.geometryDiscontinuity(new net.minecraft.client.renderer.entity.state.EnderDragonRenderState()));
    }

    @Test
    void unknownEntityFamiliesStillFailClosed() {
        assertEquals(
                MetalFxMotionEligibility.UNKNOWN_ENTITY,
                MetalFxMotionEligibility.incompleteEntityReason(new EntityRenderState())
        );
    }

    @Test
    void frameInterpolationRequiresARealPreviousState() {
        Object state = new Object();
        MetalEntityMotionCapture.beginFrame();
        assertFalse(MetalEntityMotionCapture.hasPreviousState(state));

        MetalEntityMotionCapture.attachState(
                state,
                new MetalEntityMotionCapture.Sample(1L, 1L, new Matrix4f(), null)
        );
        assertFalse(MetalEntityMotionCapture.hasPreviousState(state));

        MetalEntityMotionCapture.attachState(
                state,
                new MetalEntityMotionCapture.Sample(1L, 1L, new Matrix4f(), new Matrix4f())
        );
        assertTrue(MetalEntityMotionCapture.hasPreviousState(state));

        MetalEntityMotionCapture.beginFrame();
        assertFalse(MetalEntityMotionCapture.hasPreviousState(state));
    }

    @Test
    void buildSampleListPreservesOrderAndSize() {
        MetalEntityMotionCapture.beginFrame();
        java.util.List<String> source = java.util.List.of("a", "b", "c");
        java.util.List<String> wrapped = MetalEntityMotionCapture.activateBuildSampleOnAccess(source);
        assertEquals(source.size(), wrapped.size());
        assertEquals(source, wrapped);
    }

    @Test
    void rejectionIsMonotonicWithinFrameAndResetsAtBoundary() {
        MetalFxMotionEligibility eligibility = new MetalFxMotionEligibility();
        assertTrue(eligibility.eligible());

        eligibility.reject(MetalFxMotionEligibility.PARTICLE);
        eligibility.reject(MetalFxMotionEligibility.FIRST_PERSON);
        assertFalse(eligibility.eligible());
        assertEquals(
                MetalFxMotionEligibility.PARTICLE | MetalFxMotionEligibility.FIRST_PERSON,
                eligibility.rejectedReasons()
        );

        eligibility.beginFrame();
        assertTrue(eligibility.eligible());
        assertEquals(0, eligibility.rejectedReasons());
    }

    @Test
    void unresolvedSharedAuxiliaryIsAWholeFrameRejectionReason() {
        MetalFxMotionEligibility eligibility = new MetalFxMotionEligibility();
        eligibility.beginFrame();
        eligibility.reject(MetalFxMotionEligibility.SHARED_AUXILIARY);
        assertFalse(eligibility.eligible());
        assertEquals(MetalFxMotionEligibility.SHARED_AUXILIARY, eligibility.rejectedReasons());
    }
}
