package com.metallum.client.metal.render;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalExtendedEntityMotionTest {
    @AfterEach
    void clearCapture() {
        MetalEntityMotionCapture.setEnabled(true);
        MetalEntityMotionCapture.beginFrame();
    }

    @Test
    void itemFramePartsUseSeparateSamplesAndPoseOwners() {
        MetalEntityMotionCapture.beginFrame();
        Object state = new Object();
        PoseStack.Pose basePose = new PoseStack().last();
        PoseStack.Pose contentPose = new PoseStack().last();
        MetalEntityMotionCapture.Sample baseSample = sample(
                11L,
                new Matrix4f().rotateY(0.25F),
                new Matrix4f(),
                MetalEntityMotionCapture.Source.ITEM_FRAME_BASE
        );
        MetalEntityMotionCapture.Sample contentSample = sample(
                12L,
                new Matrix4f().scale(1.25F, 1.0F, 0.75F),
                new Matrix4f(),
                MetalEntityMotionCapture.Source.ITEM_FRAME_CONTENT
        );
        EnumMap<MetalEntityObjectPose.EntityPart, MetalEntityMotionCapture.Sample> parts = new EnumMap<>(
                MetalEntityObjectPose.EntityPart.class
        );
        parts.put(MetalEntityObjectPose.EntityPart.ITEM_FRAME_BASE, baseSample);
        parts.put(MetalEntityObjectPose.EntityPart.ITEM_FRAME_CONTENT, contentSample);
        MetalEntityMotionCapture.attachEntityState(state, null, parts);

        MetalEntityMotionCapture.beginEntitySubmission(state);
        MetalEntityMotionCapture.beginEntityPart(MetalEntityObjectPose.EntityPart.ITEM_FRAME_BASE);
        MetalEntityMotionCapture.captureModelSubmit(new Object(), basePose);
        MetalEntityMotionCapture.endEntityPart();
        MetalEntityMotionCapture.beginEntityPart(MetalEntityObjectPose.EntityPart.ITEM_FRAME_CONTENT);
        MetalEntityMotionCapture.captureModelSubmit(new Object(), contentPose);
        MetalEntityMotionCapture.endEntityPart();
        MetalEntityMotionCapture.endEntitySubmission();

        MetalEntityMotionCapture.beginModelBuildForPose(basePose);
        MetalEntityMotionCapture.endModelBuild();
        MetalEntityMotionCapture.beginModelBuildForPose(contentPose);
        MetalEntityMotionCapture.endModelBuild();

        MetalEntityMotionCapture.Diagnostics diagnostics = MetalEntityMotionCapture.diagnostics();
        assertEquals(1, diagnostics.samplesAttached(MetalEntityMotionCapture.Source.ITEM_FRAME_BASE));
        assertEquals(1, diagnostics.samplesAttached(MetalEntityMotionCapture.Source.ITEM_FRAME_CONTENT));
        assertEquals(2, diagnostics.modelSubmitsCaptured());
        assertEquals(2, diagnostics.poseOwnersCaptured());
        assertEquals(2, diagnostics.poseOwnersMatched());
        assertTrue(baseSample.hasObjectMotion());
        assertTrue(contentSample.hasObjectMotion());
    }

    @Test
    void unownedGeometryCannotAcquireAnEntitySampleThroughAStalePart() {
        MetalEntityMotionCapture.beginFrame();
        Object state = new Object();
        MetalEntityMotionCapture.attachEntityState(state, null, new EnumMap<>(MetalEntityObjectPose.EntityPart.class));

        MetalEntityMotionCapture.beginEntitySubmission(state);
        MetalEntityMotionCapture.beginEntityPart(MetalEntityObjectPose.EntityPart.ITEM_FRAME_BASE);
        PoseStack.Pose pose = new PoseStack().last();
        MetalEntityMotionCapture.captureModelSubmit(new Object(), pose);
        MetalEntityMotionCapture.endEntityPart();
        MetalEntityMotionCapture.endEntitySubmission();
        MetalEntityMotionCapture.beginModelBuildForPose(pose);
        MetalEntityMotionCapture.endModelBuild();

        MetalEntityMotionCapture.Diagnostics diagnostics = MetalEntityMotionCapture.diagnostics();
        assertEquals(0, diagnostics.modelSubmitsCaptured());
        assertEquals(0, diagnostics.poseOwnersCaptured());
        assertEquals(0, diagnostics.poseOwnersMatched());
    }

    @Test
    void historyKeepsRotationAndScaleButRejectsEntityTeleports() {
        MetalMotionStateStore store = new MetalMotionStateStore();
        MetalMotionStateStore.ObjectKey key = new MetalMotionStateStore.ObjectKey(101L, 7L);
        Matrix4f first = new Matrix4f().translate(3.0F, 64.0F, 4.0F);
        Matrix4f turned = new Matrix4f(first).rotateY(0.5F).scale(1.5F, 1.0F, 0.75F);
        Matrix4f teleported = new Matrix4f().translate(30.0F, 64.0F, 4.0F);

        store.beginFrame();
        store.observe(key, first);
        store.commitSubmittedFrame();
        store.beginFrame();
        store.observe(key, turned);
        assertTrue(store.previousIfContinuous(key, turned, 8.0F) != null,
                "rotation and scale must not reset a continuously located object");
        store.commitSubmittedFrame();
        store.beginFrame();
        store.observe(key, teleported);
        assertNull(store.previousIfContinuous(key, teleported, 8.0F));
    }

    @Test
    void sourceDiagnosticsDistinguishDisplayAndRootMotion() {
        MetalEntityMotionCapture.beginFrame();
        MetalEntityMotionCapture.attachState(
                new Object(),
                sample(201L, new Matrix4f().rotateY(0.5F), new Matrix4f(), MetalEntityMotionCapture.Source.DISPLAY)
        );
        MetalEntityMotionCapture.attachState(
                new Object(),
                sample(202L, new Matrix4f().scale(2.0F), new Matrix4f(), MetalEntityMotionCapture.Source.ARMOR_STAND)
        );
        MetalEntityMotionCapture.attachState(
                new Object(),
                sample(203L, new Matrix4f().translate(0.0F, 0.5F, 0.0F), new Matrix4f(), MetalEntityMotionCapture.Source.END_CRYSTAL)
        );

        MetalEntityMotionCapture.Diagnostics diagnostics = MetalEntityMotionCapture.diagnostics();
        assertEquals(1, diagnostics.samplesAttached(MetalEntityMotionCapture.Source.DISPLAY));
        assertEquals(1, diagnostics.samplesAttached(MetalEntityMotionCapture.Source.ARMOR_STAND));
        assertEquals(1, diagnostics.samplesAttached(MetalEntityMotionCapture.Source.END_CRYSTAL));
        assertFalse(diagnostics.samplesAttachedBySource().containsKey(MetalEntityMotionCapture.Source.PAINTING));
    }

    private static MetalEntityMotionCapture.Sample sample(
            final long objectId,
            final Matrix4f current,
            final Matrix4f previous,
            final MetalEntityMotionCapture.Source source
    ) {
        return new MetalEntityMotionCapture.Sample(objectId, 1L, current, previous, source);
    }
}
