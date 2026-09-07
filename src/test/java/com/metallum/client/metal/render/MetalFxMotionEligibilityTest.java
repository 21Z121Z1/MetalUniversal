package com.metallum.client.metal.render;

import net.minecraft.client.renderer.entity.state.ArrowRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.FallingBlockRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.MinecartRenderState;
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
    void nonRigidAndUnknownFamiliesFailClosed() {
        assertEquals(
                MetalFxMotionEligibility.NON_RIGID_ENTITY,
                MetalFxMotionEligibility.incompleteEntityReason(new LivingEntityRenderState())
        );
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
}
