package com.metallum.client.sodium;

import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SodiumCullReusePolicyTest {
    @Test
    void identicalTransformPreservesDistanceClassification() {
        CameraTransform camera = new CameraTransform(160.25, 128.25, 160.25);
        assertTrue(SodiumCullReusePolicy.sameDistanceClassification(
                camera,
                new CameraTransform(160.25, 128.25, 160.25),
                SectionPos.of(10, 8, 10),
                256.0f,
                -4,
                19
        ));
    }

    @Test
    void smallMovementCanRemainInTheSameDistanceClasses() {
        assertTrue(SodiumCullReusePolicy.sameDistanceClassification(
                new CameraTransform(160.25, 128.25, 160.25),
                new CameraTransform(160.50, 128.25, 160.25),
                SectionPos.of(10, 8, 10),
                256.0f,
                -4,
                19
        ));
    }

    @Test
    void largeInSectionMovementRejectsWhenCutoffMembershipChanges() {
        assertFalse(SodiumCullReusePolicy.sameDistanceClassification(
                new CameraTransform(160.10, 128.25, 160.25),
                new CameraTransform(175.90, 128.25, 160.25),
                SectionPos.of(10, 8, 10),
                256.0f,
                -4,
                19
        ));
    }
}
