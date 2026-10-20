package com.metallum.client.metal.render;

import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class MetalEntityMotionCaptureSubmissionScopeTest {
    @AfterEach
    void reset() {
        MetalEntityMotionCapture.setEnabled(false);
        MetalEntityMotionCapture.setEnabled(true);
    }

    @Test
    void nestedSubmissionRestoresOuterOwner() {
        MetalEntityMotionCapture.setEnabled(true);
        MetalEntityMotionCapture.beginFrame();
        Object outerState = new Object();
        Object innerState = new Object();
        Object outerBefore = new Object();
        Object inner = new Object();
        Object outerAfter = new Object();
        MetalEntityMotionCapture.Sample outerSample = sample(101L, 1L);
        MetalEntityMotionCapture.Sample innerSample = sample(202L, 2L);
        MetalEntityMotionCapture.attachState(outerState, outerSample);
        MetalEntityMotionCapture.attachState(innerState, innerSample);

        MetalEntityMotionCapture.beginEntitySubmission(outerState);
        MetalEntityMotionCapture.captureModelSubmit(outerBefore);
        MetalEntityMotionCapture.beginEntitySubmission(innerState);
        MetalEntityMotionCapture.captureModelSubmit(inner);
        MetalEntityMotionCapture.endEntitySubmission();
        MetalEntityMotionCapture.captureModelSubmit(outerAfter);
        MetalEntityMotionCapture.endEntitySubmission();

        assertEquals(outerSample.objectId(), MetalEntityMotionCapture.sampleForSubmit(outerBefore).objectId());
        assertEquals(innerSample.objectId(), MetalEntityMotionCapture.sampleForSubmit(inner).objectId());
        assertEquals(outerSample.objectId(), MetalEntityMotionCapture.sampleForSubmit(outerAfter).objectId());
    }

    @Test
    void unsupportedNestedSubmissionDoesNotInheritOuterOwner() {
        MetalEntityMotionCapture.setEnabled(true);
        MetalEntityMotionCapture.beginFrame();
        Object outerState = new Object();
        Object unsupportedState = new Object();
        Object leaked = new Object();
        Object restored = new Object();
        MetalEntityMotionCapture.Sample outerSample = sample(303L, 3L);
        MetalEntityMotionCapture.attachState(outerState, outerSample);

        MetalEntityMotionCapture.beginEntitySubmission(outerState);
        MetalEntityMotionCapture.beginEntitySubmission(unsupportedState);
        MetalEntityMotionCapture.captureModelSubmit(leaked);
        MetalEntityMotionCapture.endEntitySubmission();
        MetalEntityMotionCapture.captureModelSubmit(restored);
        MetalEntityMotionCapture.endEntitySubmission();

        assertNull(MetalEntityMotionCapture.sampleForSubmit(leaked));
        assertEquals(outerSample.objectId(), MetalEntityMotionCapture.sampleForSubmit(restored).objectId());
    }

    @Test
    void customSubmitMustActivateItsOwnerBeforeTheVertexBuilderAllocatesADraw() {
        MetalEntityMotionCapture.setEnabled(true);
        MetalEntityMotionCapture.beginFrame();
        MetalPreviousVertexHistory.beginFrame();
        Object state = new Object(), owned = new Object(), unowned = new Object();
        var owner = sample(407, 8);
        MetalEntityMotionCapture.attachState(state, owner);
        MetalEntityMotionCapture.beginEntitySubmission(state);
        MetalEntityMotionCapture.captureModelSubmit(owned);
        MetalEntityMotionCapture.endEntitySubmission();
        var pipeline = com.mojang.renderpearl.api.pipeline.RenderPipeline.builder().withLocation("test/custom_owner")
                .withVertexShader("core/entity").withFragmentShader("core/entity")
                .withVertexBinding(0, com.mojang.blaze3d.vertex.DefaultVertexFormat.ENTITY)
                .withPrimitiveTopology(com.mojang.renderpearl.api.pipeline.PrimitiveTopology.QUADS).build();
        assertFalse(MetalEntityMotionCapture.shouldSplitEntityDraw(pipeline),
                "the old callback-only scope has not started when Minecraft allocates the builder");
        var submits = MetalEntityMotionCapture.activateBuildSampleOnAccess(java.util.List.of(owned, unowned));
        assertEquals(owned, submits.get(0));
        assertTrue(MetalEntityMotionCapture.shouldSplitEntityDraw(pipeline), "the owner must exist before allocation");
        MetalEntityMotionCapture.endModelBuild();
        assertEquals(unowned, submits.get(1));
        assertFalse(MetalEntityMotionCapture.shouldSplitEntityDraw(pipeline), "unowned custom geometry cannot inherit the preceding submit");
        MetalEntityMotionCapture.endModelBuild();
        MetalPreviousVertexHistory.reset();
    }

    private static MetalEntityMotionCapture.Sample sample(final long objectId, final long generation) {
        Matrix4f identity = new Matrix4f();
        return new MetalEntityMotionCapture.Sample(objectId, generation, identity, identity);
    }
}
