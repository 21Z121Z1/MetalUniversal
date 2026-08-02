package com.metallum.client.validation;

import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BackendFrameComparisonClientTest {
    @Test
    void fixedIrisTimeReplaysTheCanonicalTimerAtAStableCadence() {
        SystemTimeUniforms.TIMER.reset();
        SystemTimeUniforms.COUNTER.reset();
        try {
            BackendFrameComparisonClient.applyFixedIrisSystemTime(2, 16L);

            assertEquals(3, SystemTimeUniforms.COUNTER.getAsInt());
            assertEquals(0.016f, SystemTimeUniforms.TIMER.getLastFrameTime(), 0.0f);
            assertEquals(0.032f, SystemTimeUniforms.TIMER.getFrameTimeCounter(), 0.0f);
        } finally {
            SystemTimeUniforms.TIMER.reset();
            SystemTimeUniforms.COUNTER.reset();
        }
    }

    @Test
    void fixedCameraParserPreservesTheRequestedPose() {
        BackendFrameComparisonClient.FixedCamera camera =
                BackendFrameComparisonClient.parseFixedCamera(
                        "579.4938336701937,90.45083448610046,-177.71662902161114,"
                                + "-164.09991455078125,29.249996185302734"
                );

        assertEquals(579.4938336701937, camera.x());
        assertEquals(90.45083448610046, camera.y());
        assertEquals(-177.71662902161114, camera.z());
        assertEquals(-164.09991455078125F, camera.yaw());
        assertEquals(29.249996185302734F, camera.pitch());
    }

    @Test
    void fixedPartialTickAcceptsTheFullRenderInterpolationInterval() {
        assertEquals(1.0F, BackendFrameComparisonClient.parseFixedPartialTick("1.0"));
        assertEquals(0.25F, BackendFrameComparisonClient.parseFixedPartialTick(" 0.25 "));
        assertThrows(
                IllegalArgumentException.class,
                () -> BackendFrameComparisonClient.parseFixedPartialTick("1.01")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> BackendFrameComparisonClient.parseFixedPartialTick("not-a-number")
        );
    }

    @Test
    void absentFixedCameraLeavesTheRuntimeUnchanged() {
        assertNull(BackendFrameComparisonClient.parseFixedCamera(""));
        assertNull(BackendFrameComparisonClient.parseFixedCamera(" \t"));
        assertNull(BackendFrameComparisonClient.parseFixedCamera(null));
    }

    @Test
    void gameDirectoryReceiptCanonicalizesTheRealPath() {
        String canonical = BackendFrameComparisonClient.canonicalGameDirectory(".");

        assertEquals(
                Path.of(".").toAbsolutePath().normalize().toString(),
                canonical
        );
        assertEquals("", BackendFrameComparisonClient.canonicalGameDirectory(""));
        assertEquals("", BackendFrameComparisonClient.canonicalGameDirectory(null));
        assertEquals(
                "8f16930a-42ad-4f9b-9d59-02698f26b145",
                BackendFrameComparisonClient.canonicalUuid(
                        " 8F16930A-42AD-4F9B-9D59-02698F26B145 "
                )
        );
        assertEquals("", BackendFrameComparisonClient.canonicalUuid(""));
        assertThrows(
                IllegalArgumentException.class,
                () -> BackendFrameComparisonClient.canonicalUuid("not-a-uuid")
        );
    }

    @Test
    void fixedWeatherAcceptsOnlyTheExplicitClearScenario() {
        assertEquals(
                BackendFrameComparisonClient.FixedWeather.UNCHANGED,
                BackendFrameComparisonClient.parseFixedWeather("")
        );
        assertEquals(
                BackendFrameComparisonClient.FixedWeather.UNCHANGED,
                BackendFrameComparisonClient.parseFixedWeather(null)
        );
        assertEquals(
                BackendFrameComparisonClient.FixedWeather.CLEAR,
                BackendFrameComparisonClient.parseFixedWeather(" CLEAR ")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> BackendFrameComparisonClient.parseFixedWeather("rain")
        );
    }

    @Test
    void malformedOrNonFiniteFixedCameraFailsClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BackendFrameComparisonClient.parseFixedCamera("1,2,3,4")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> BackendFrameComparisonClient.parseFixedCamera("1,2,3,north,5")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> BackendFrameComparisonClient.parseFixedCamera("1,2,Infinity,4,5")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> BackendFrameComparisonClient.applyFixedIrisSystemTime(-1, 16L)
        );
    }

    @Test
    void scheduledResizeRequiresOneCompletePositiveContract() {
        assertNull(BackendFrameComparisonClient.parseResizeRequest(-1, -1, -1));
        BackendFrameComparisonClient.ResizeRequest request =
                BackendFrameComparisonClient.parseResizeRequest(120, 1280, 720);
        assertEquals(120, request.frame());
        assertEquals(1280, request.width());
        assertEquals(720, request.height());
        assertThrows(
                IllegalArgumentException.class,
                () -> BackendFrameComparisonClient.parseResizeRequest(120, -1, 720)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> BackendFrameComparisonClient.parseResizeRequest(-1, 1280, 720)
        );
    }

    @Test
    void scheduledShaderToggleRequiresDisableBeforeEnable() {
        assertNull(BackendFrameComparisonClient.parseShaderToggleRequest(-1, -1));
        BackendFrameComparisonClient.ShaderToggleRequest request =
                BackendFrameComparisonClient.parseShaderToggleRequest(120, 170);
        assertEquals(120, request.disableFrame());
        assertEquals(170, request.enableFrame());
        assertThrows(
                IllegalArgumentException.class,
                () -> BackendFrameComparisonClient.parseShaderToggleRequest(170, 120)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> BackendFrameComparisonClient.parseShaderToggleRequest(-1, 120)
        );
    }

    @Test
    void scheduledDimensionSwitchRequiresAKnownTarget() {
        assertNull(BackendFrameComparisonClient.parseDimensionSwitchRequest(-1, ""));
        BackendFrameComparisonClient.DimensionSwitchRequest request =
                BackendFrameComparisonClient.parseDimensionSwitchRequest(150, "nether");
        assertEquals(150, request.frame());
        assertEquals(
                BackendFrameComparisonClient.DimensionSwitchTarget.NETHER,
                request.target()
        );
        assertEquals(
                BackendFrameComparisonClient.DimensionSwitchTarget.NETHER,
                BackendFrameComparisonClient.parseDimensionSwitchRequest(
                        150,
                        "minecraft:the_nether"
                ).target()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> BackendFrameComparisonClient.parseDimensionSwitchRequest(-1, "nether")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> BackendFrameComparisonClient.parseDimensionSwitchRequest(150, "moon")
        );
    }

    @Test
    void dimensionSwitchSequenceRequiresStrictlyIncreasingKnownSteps() {
        List<BackendFrameComparisonClient.DimensionSwitchRequest> requests =
                BackendFrameComparisonClient.parseDimensionSwitchSequence(
                        "150:nether,450:minecraft:overworld,750:end"
                );
        assertEquals(3, requests.size());
        assertEquals(150, requests.get(0).frame());
        assertEquals(
                BackendFrameComparisonClient.DimensionSwitchTarget.OVERWORLD,
                requests.get(1).target()
        );
        assertEquals(
                BackendFrameComparisonClient.DimensionSwitchTarget.END,
                requests.get(2).target()
        );
        assertTrue(
                BackendFrameComparisonClient.parseDimensionSwitchSequence(" ").isEmpty()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> BackendFrameComparisonClient.parseDimensionSwitchSequence(
                        "150:nether,150:overworld"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> BackendFrameComparisonClient.parseDimensionSwitchSequence("nether")
        );
    }

    @Test
    void sceneReadinessRequiresStableTerrainChunksAndEntitiesForBothThresholds() {
        BackendFrameComparisonClient.SceneStabilityTracker tracker =
                new BackendFrameComparisonClient.SceneStabilityTracker(3, 1_000L);
        BackendFrameComparisonClient.SceneReadinessSample stable =
                new BackendFrameComparisonClient.SceneReadinessSample(
                        2_048,
                        1_024,
                        true,
                        65,
                        "stable"
                );

        assertFalse(tracker.observe(stable, 0L));
        assertFalse(tracker.observe(stable, 500_000_000L));
        assertTrue(tracker.observe(stable, 1_000_000_000L));

        BackendFrameComparisonClient.SceneReadinessSample changedEntity =
                new BackendFrameComparisonClient.SceneReadinessSample(
                        2_048,
                        1_024,
                        true,
                        65,
                        "changed"
                );
        assertFalse(tracker.observe(changedEntity, 2_000_000_000L));
        assertEquals(1, tracker.stableFrames());

        BackendFrameComparisonClient.SceneReadinessSample pendingTerrain =
                new BackendFrameComparisonClient.SceneReadinessSample(
                        2_048,
                        1_024,
                        false,
                        65,
                        "changed"
                );
        assertFalse(tracker.observe(pendingTerrain, 3_000_000_000L));
        assertEquals(0, tracker.stableFrames());
    }
}
