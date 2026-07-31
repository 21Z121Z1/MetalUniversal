package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers shadow feature filtering that is independent of a live client. */
final class IrisMetalShadowFeatureSubmitterTest {
    @Test
    void excludesSpectatorGeneralEntities() {
        assertTrue(IrisMetalShadowFeatureSubmitter.shouldExtractGeneralEntity(false));
        assertFalse(IrisMetalShadowFeatureSubmitter.shouldExtractGeneralEntity(true));
    }

    @Test
    void excludesSpectatorAndInvisiblePlayers() {
        assertTrue(IrisMetalShadowFeatureSubmitter.shouldExtractPlayer(false, false));
        assertFalse(IrisMetalShadowFeatureSubmitter.shouldExtractPlayer(true, false));
        assertFalse(IrisMetalShadowFeatureSubmitter.shouldExtractPlayer(false, true));
    }

    @Test
    void onlyLightEmittingBlockEntitiesSurviveLightOnlyMode() {
        assertTrue(IrisMetalShadowFeatureSubmitter.shouldRenderLightBlockEntity(1));
        assertFalse(IrisMetalShadowFeatureSubmitter.shouldRenderLightBlockEntity(0));
    }
}
