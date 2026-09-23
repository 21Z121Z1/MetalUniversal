package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class FramePacingPolicyTest {
    final FramePacingPolicy.Config config = new FramePacingPolicy.Config(true, 120, 30, 10, 20, 60_000);

    @Test void foregroundBackgroundIdleAndRestoreUseOneLimiterAndRespectLowerVanillaLimit() {
        assertEquals(120, decide(true, false, 0, 260).effectiveFps());
        assertEquals(30, decide(false, false, 0, 260).effectiveFps());
        assertEquals(10, decide(false, true, 0, 260).effectiveFps());
        assertEquals(20, decide(true, false, 60_000, 260).effectiveFps());
        assertEquals(120, decide(true, false, 0, 260).effectiveFps());
        assertEquals(10, decide(true, false, 0, 10).effectiveFps());
        assertEquals(8_333_333L, decide(true, false, 0, 260).targetIntervalNanos());
    }

    @Test void disabledAndExternalOwnersRetainTheirResultAndDoNotClaimATarget() {
        var disabled = new FramePacingPolicy.Config(false, 120, 30, 10, 20, 60_000);
        assertEquals(260, FramePacingPolicy.decide(disabled, 260, false, true, 0, false).effectiveFps());
        var external = FramePacingPolicy.decide(config, 75, false, true, 0, true);
        assertEquals(75, external.effectiveFps());
        assertEquals("dynamic-fps", external.owner());
        assertEquals(-1, external.targetIntervalNanos());
    }

    @Test void inheritNeverRemovesMinecraftThrottlingAndUnsupportedSentinelIsRejected() {
        var inherited = new FramePacingPolicy.Config(true, 0, 0, 0, 0, 1000);
        assertEquals(10, FramePacingPolicy.decide(inherited, 10, true, false, 0, false).effectiveFps());
        assertThrows(IllegalArgumentException.class, () -> new FramePacingPolicy.Config(true, 260, 0, 0, 0, 1000));
    }

    @Test void batteryRuleNeedsExplicitOptInAndObservedBatteryState() {
        var battery = new FramePacingPolicy.Config(true, 120, 30, 10, 20, 60_000, 60);
        assertEquals(60, FramePacingPolicy.decide(battery, 260, true, false, 0, false, 1).effectiveFps());
        assertEquals(120, FramePacingPolicy.decide(battery, 260, true, false, 0, false, -1).effectiveFps());
        assertEquals(120, FramePacingPolicy.decide(config, 260, true, false, 0, false, 1).effectiveFps());
        assertEquals(120, FramePacingPolicy.decide(battery, 260, true, false, 0, false, 0).effectiveFps());
    }

    private FramePacingPolicy.Decision decide(boolean focused, boolean minimized, long idle, int vanilla) {
        return FramePacingPolicy.decide(config, vanilla, focused, minimized, idle, false);
    }
}
