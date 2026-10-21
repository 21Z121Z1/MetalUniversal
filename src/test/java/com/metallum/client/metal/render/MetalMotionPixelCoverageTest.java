package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MetalMotionPixelCoverageTest {
    @Test void depthlessOitWorldAndPrivateOverlayHaveTheirOwnReactiveCorrespondence() {
        assertTrue(MetalMotionPixelCoverage.exactWorldOutsideHud(191,0,0));
        assertTrue(MetalMotionPixelCoverage.exactWorldOutsideHud(159,0,0));
        assertTrue(MetalMotionPixelCoverage.reactiveWorldOutsideHud(191,0,0));
        assertTrue(MetalMotionPixelCoverage.reactiveWorldOutsideHud(159,0,0));
        assertTrue(MetalMotionPixelCoverage.exactWorldOutsideHud(255,0,0));
        assertFalse(MetalMotionPixelCoverage.reactiveWorldOutsideHud(255,0,0));
    }
    @Test void hudChannelIncludingTransparentOrInvalidHistoryCannotSatisfyWorldVisibility() {
        for(int hud:new int[]{64,96,159,191,223,255}) {
            assertTrue(MetalMotionPixelCoverage.hudCovered(hud,0));
            assertFalse(MetalMotionPixelCoverage.exactWorldOutsideHud(191,hud,0));
            assertFalse(MetalMotionPixelCoverage.reactiveWorldOutsideHud(159,hud,0));
        }
        assertFalse(MetalMotionPixelCoverage.exactWorldOutsideHud(191,0,.25F));
        assertFalse(MetalMotionPixelCoverage.exactWorldOutsideHud(0,191,0));
        assertFalse(MetalMotionPixelCoverage.exactWorldOutsideHud(64,0,0));
        assertFalse(MetalMotionPixelCoverage.exactWorldOutsideHud(96,0,0));
    }
}
