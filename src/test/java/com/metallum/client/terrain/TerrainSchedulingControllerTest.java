package com.metallum.client.terrain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerrainSchedulingControllerTest {
    @Test
    void startupUsesSodiumDefaultsUntilWarmupCompletes() {
        TerrainSchedulingController controller = new TerrainSchedulingController(true, 2);

        runFrame(controller, 1L, 0.0, 0.0, 1.0, input(16_000_000L, 0, -1, -1, 0.0));
        assertTrue(controller.decision().usesSodiumDefaults());
        assertEquals(123L, controller.overrideBuildBudget(123L));

        runFrame(controller, 2L, 0.0, 0.0, 1.0, input(16_000_000L, 0, -1, -1, 0.0));
        assertTrue(controller.decision().usesSodiumDefaults());

        runFrame(controller, 3L, 0.0, 0.0, 1.0, input(16_000_000L, 0, -1, -1, 0.0));
        assertTrue(controller.decision().adaptive());
        assertTrue(controller.decision().buildBudgetNanos() > 0L);
        assertTrue(controller.decision().uploadBudgetNanos() > 0L);
        assertNotEquals(123L, controller.overrideBuildBudget(123L));
    }

    @Test
    void steadyStateBudgetsAreDeterministicFromFrameDuration() {
        TerrainSchedulingController first = new TerrainSchedulingController(true, 0);
        TerrainSchedulingController second = new TerrainSchedulingController(true, 0);
        TerrainSchedulingController.FrameInputs inputs = input(20_000_000L, 0, -1, -1, 0.0);

        runFrame(first, 1L, 0.0, 0.0, 1.0, inputs);
        runFrame(second, 1L, 0.0, 0.0, 1.0, inputs);

        assertEquals(first.decision(), second.decision());
        assertEquals(1_666_667L, first.decision().buildBudgetNanos());
        assertEquals(1_333_333L, first.decision().uploadBudgetNanos());
    }

    @Test
    void meaningfulTurnBoostsOnlyForwardSectionsWithinDistanceWindow() {
        TerrainSchedulingController controller = new TerrainSchedulingController(true, 0);
        runFrame(controller, 1L, 0.0, 0.0, 1.0, input(16_000_000L, 0, -1, -1, 0.0));
        runFrame(controller, 2L, 0.0, 0.0, 1.0, input(16_000_000L, 0, -1, -1, 0.0));
        runFrame(controller, 3L, 0.0, 0.0, 1.0, input(16_000_000L, 0, -1, -1, 0.0));
        runFrame(controller, 4L, 1.0, 0.0, 0.0, input(16_000_000L, 0, -1, -1, 0.0));

        assertTrue(controller.decision().turnDetected());
        assertTrue(controller.decision().forwardBoost());
        assertTrue(controller.shouldBoostForward(20.0, 0.0, 0.0, 256.0F, 8));
        assertFalse(controller.shouldBoostForward(-20.0, 0.0, 0.0, 256.0F, 8));
        assertFalse(controller.shouldBoostForward(200.0, 0.0, 0.0, 256.0F, 8));
        assertFalse(controller.shouldBoostForward(20.0, 0.0, 0.0, 256.0F, 0));
    }

    @Test
    void backlogEscalatesPressureAndReducesBudgets() {
        TerrainSchedulingController controller = new TerrainSchedulingController(true, 0);
        runFrame(controller, 1L, 0.0, 0.0, 1.0, input(16_000_000L, 48, -1, -1, 0.0));
        assertEquals(0, controller.decision().pressureLevel());
        runFrame(controller, 2L, 0.0, 0.0, 1.0, input(16_000_000L, 48, -1, -1, 0.0));
        long constrainedBuild = controller.decision().buildBudgetNanos();
        assertEquals(1, controller.decision().pressureLevel());

        runFrame(controller, 3L, 0.0, 0.0, 1.0, input(16_000_000L, 128, -1, -1, 0.0));
        runFrame(controller, 4L, 0.0, 0.0, 1.0, input(16_000_000L, 128, -1, -1, 0.0));
        assertEquals(2, controller.decision().pressureLevel());
        assertTrue(controller.decision().buildBudgetNanos() < constrainedBuild);
    }

    @Test
    void thermalAndMemoryPressureUseTheSameBackpressureState() {
        TerrainSchedulingController thermal = new TerrainSchedulingController(true, 0);
        runFrame(thermal, 1L, 0.0, 0.0, 1.0, input(16_000_000L, 0, -1, 2, 0.0));
        runFrame(thermal, 2L, 0.0, 0.0, 1.0, input(16_000_000L, 0, -1, 2, 0.0));
        assertEquals(1, thermal.decision().pressureLevel());

        TerrainSchedulingController memory = new TerrainSchedulingController(true, 0);
        runFrame(memory, 1L, 0.0, 0.0, 1.0, input(16_000_000L, 0, -1, -1, 0.85));
        runFrame(memory, 2L, 0.0, 0.0, 1.0, input(16_000_000L, 0, -1, -1, 0.85));
        assertEquals(1, memory.decision().pressureLevel());
    }

    @Test
    void pressureRecoveryHasHysteresis() {
        TerrainSchedulingController controller = new TerrainSchedulingController(true, 0);
        for (long frame = 1; frame <= 2; frame++) {
            runFrame(controller, frame, 0.0, 0.0, 1.0, input(16_000_000L, 48, -1, -1, 0.0));
        }
        assertEquals(1, controller.decision().pressureLevel());

        for (long frame = 3; frame <= 10; frame++) {
            runFrame(controller, frame, 0.0, 0.0, 1.0, input(16_000_000L, 0, -1, -1, 0.0));
        }
        assertEquals(0, controller.decision().pressureLevel());
    }

    @Test
    void cpuAndGpuPressureEscalateIndependently() {
        TerrainSchedulingController controller = new TerrainSchedulingController(true, 0);
        runFrame(controller, 1L, 0.0, 0.0, 1.0, input(30_000_000L, 0, 30_000_000L, -1, 0.0));
        runFrame(controller, 2L, 0.0, 0.0, 1.0, input(30_000_000L, 0, 30_000_000L, -1, 0.0));
        assertEquals(2, controller.decision().pressureLevel());
    }

    @Test
    void pacingSnapshotDrivesRefreshTargetBudgets() {
        TerrainSchedulingController controller = new TerrainSchedulingController(true, 0);
        PresentationPacingSnapshot pacing = PresentationPacingSnapshot.capture(
                1L, 120, 20_000_000L, 4_000_000L
        );
        TerrainSchedulingController.FrameInputs inputs = new TerrainSchedulingController.FrameInputs(
                20_000_000L,
                20_000_000L,
                4_000_000L,
                0,
                1,
                4,
                -1,
                0.0,
                pacing
        );

        runFrame(controller, 1L, 0.0, 0.0, 1.0, inputs);

        assertEquals(pacing, controller.lastSnapshot().presentationPacing());
        assertEquals(1_500_000L, controller.decision().buildBudgetNanos());
        assertEquals(1_000_000L, controller.decision().uploadBudgetNanos());
    }

    @Test
    void adaptiveBudgetUsesRefreshTargetInsteadOfObservedFrameDuration() {
        TerrainSchedulingController sixty = new TerrainSchedulingController(true, 0);
        TerrainSchedulingController ninety = new TerrainSchedulingController(true, 0);
        TerrainSchedulingController oneTwenty = new TerrainSchedulingController(true, 0);

        runFrame(sixty, 1L, 0.0, 0.0, 1.0,
                withCpu(input(40_000_000L, 0, -1, -1, 0.0,
                        PresentationPacingSnapshot.capture(1L, 60, -1L, -1L)), 0L));
        runFrame(ninety, 1L, 0.0, 0.0, 1.0,
                withCpu(input(40_000_000L, 0, -1, -1, 0.0,
                        PresentationPacingSnapshot.capture(1L, 90, -1L, -1L)), 0L));
        runFrame(oneTwenty, 1L, 0.0, 0.0, 1.0,
                withCpu(input(40_000_000L, 0, -1, -1, 0.0,
                        PresentationPacingSnapshot.capture(1L, 120, -1L, -1L)), 0L));

        assertEquals(16_666_667L, sixty.decision().budgetTargetFrameNanos());
        assertEquals(11_111_111L, ninety.decision().budgetTargetFrameNanos());
        assertEquals(8_333_333L, oneTwenty.decision().budgetTargetFrameNanos());
        assertEquals("display-derived", oneTwenty.decision().budgetTargetSource());
        assertTrue(ninety.decision().buildBudgetNanos() <= sixty.decision().buildBudgetNanos());
        assertTrue(oneTwenty.decision().buildBudgetNanos() <= ninety.decision().buildBudgetNanos());
        assertTrue(ninety.decision().uploadBudgetNanos() <= sixty.decision().uploadBudgetNanos());
        assertTrue(oneTwenty.decision().uploadBudgetNanos() < sixty.decision().uploadBudgetNanos());
    }

    @Test
    void warmupDoesNotActuatePacingTarget() {
        TerrainSchedulingController controller = new TerrainSchedulingController(true, 2);
        TerrainSchedulingController.FrameInputs input = input(
                40_000_000L,
                0,
                -1L,
                -1,
                0.0,
                PresentationPacingSnapshot.capture(1L, 120, -1L, -1L)
        );

        runFrame(controller, 1L, 0.0, 0.0, 1.0, input);

        assertTrue(controller.decision().usesSodiumDefaults());
        assertEquals(16_666_667L, controller.decision().budgetTargetFrameNanos());
        assertEquals("sodium-default", controller.decision().budgetTargetSource());
        assertEquals(777L, controller.overrideBuildBudget(777L));
    }

    @Test
    void unavailablePacingTargetFallsBackToSixtyHz() {
        TerrainSchedulingController controller = new TerrainSchedulingController(true, 0);

        runFrame(controller, 1L, 0.0, 0.0, 1.0,
                input(40_000_000L, 0, -1L, -1, 0.0, unavailablePacing()));

        assertEquals(16_666_667L, controller.decision().budgetTargetFrameNanos());
        assertEquals("unavailable-fallback", controller.decision().budgetTargetSource());
    }

    @Test
    void cpuPressureUsesTheSameTargetAsTheBudget() {
        TerrainSchedulingController sixty = new TerrainSchedulingController(true, 0);
        TerrainSchedulingController oneTwenty = new TerrainSchedulingController(true, 0);
        TerrainSchedulingController.FrameInputs sixtyInput = input(
                40_000_000L,
                0,
                -1L,
                -1,
                0.0,
                PresentationPacingSnapshot.capture(1L, 60, -1L, -1L)
        );
        TerrainSchedulingController.FrameInputs oneTwentyInput = input(
                40_000_000L,
                0,
                -1L,
                -1,
                0.0,
                PresentationPacingSnapshot.capture(1L, 120, -1L, -1L)
        );

        for (long frame = 1L; frame <= 2L; frame++) {
            runFrame(sixty, frame, 0.0, 0.0, 1.0, withCpu(sixtyInput, 12_000_000L));
            runFrame(oneTwenty, frame, 0.0, 0.0, 1.0, withCpu(oneTwentyInput, 12_000_000L));
        }

        assertEquals(0, sixty.decision().pressureLevel());
        assertEquals(1, oneTwenty.decision().pressureLevel());
    }

    @Test
    void frameInputsRemainImmutableWhenQueueSignalsAreUpdated() {
        TerrainSchedulingController controller = new TerrainSchedulingController(true, 0);
        TerrainSchedulingController.FrameInputs initial = input(
                16_000_000L, 0, -1L, -1, 0.0,
                PresentationPacingSnapshot.capture(1L, 60, -1L, -1L)
        );

        controller.beginFrame(1_000_000_000L, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, initial);
        controller.updateBacklog(48, 2, 4);
        controller.endFrame(1_000_000_500L);

        assertEquals(0, initial.backlogJobs());
        assertEquals(1, initial.busyThreads());
        assertEquals(48, controller.lastSnapshot().inputs().backlogJobs());
        assertEquals(2, controller.lastSnapshot().inputs().busyThreads());
    }

    @Test
    void pacingEvidenceSnapshotIsCachedBetweenBoundedSamples() {
        TerrainSchedulingController controller = new TerrainSchedulingController(true, 0);
        PresentationPacingSnapshot first = controller.pacingSnapshot(1L, 60, 1L, 2L);
        PresentationPacingSnapshot second = controller.pacingSnapshot(2L, 60, 3L, 4L);
        assertSame(first, second);

        PresentationPacingSnapshot refreshed = controller.pacingSnapshot(
                1L + TerrainSchedulingController.PACING_SNAPSHOT_CADENCE_FRAMES,
                60,
                5L,
                6L
        );
        assertNotEquals(first, refreshed);
    }

    @Test
    void disabledControllerIsANoOp() {
        TerrainSchedulingController controller = new TerrainSchedulingController(false, 0);
        runFrame(controller, 1L, 0.0, 0.0, 1.0, input(10_000_000L, 128, 30_000_000L, 3, 1.0));

        assertTrue(controller.decision().usesSodiumDefaults());
        assertEquals("sodium-default", controller.decision().budgetTargetSource());
        assertEquals(777L, controller.overrideBuildBudget(777L));
        assertEquals(888L, controller.overrideUploadBudget(888L));
        assertFalse(controller.shouldBoostForward(20.0, 0.0, 0.0, 16.0F, 8));
    }

    @Test
    void csvOutputHasStableHeaderAndBoundedRows(@TempDir final Path temporaryDirectory) throws Exception {
        Path output = temporaryDirectory.resolve("terrain.csv");
        TerrainSchedulingTelemetry telemetry = new TerrainSchedulingTelemetry(output);
        TerrainSchedulingController controller = new TerrainSchedulingController(true, 0, telemetry);
        runFrame(controller, 1L, 0.0, 0.0, 1.0, input(16_000_000L, 0, -1, -1, 0.0));
        telemetry.close();

        var lines = Files.readAllLines(output);
        assertEquals(TerrainSchedulingTelemetry.CSV_HEADER, lines.get(0));
        assertEquals(2, lines.size());
        assertEquals(36, TerrainSchedulingTelemetry.CSV_HEADER.split(",").length);
        assertTrue(lines.get(1).contains(",16666667,conservative-fallback,"));
    }

    @Test
    void csvOutputStopsAtTheSessionBound(@TempDir final Path temporaryDirectory) throws Exception {
        Path output = temporaryDirectory.resolve("bounded-terrain.csv");
        TerrainSchedulingTelemetry telemetry = new TerrainSchedulingTelemetry(output);
        TerrainSchedulingController controller = new TerrainSchedulingController(true, 0, telemetry);
        runFrame(controller, 1L, 0.0, 0.0, 1.0, input(16_000_000L, 0, -1, -1, 0.0));
        for (int row = 0; row < TerrainSchedulingTelemetry.MAX_ROWS + 10; row++) {
            telemetry.append(controller.lastSnapshot());
        }
        telemetry.close();

        assertEquals(TerrainSchedulingTelemetry.MAX_ROWS, telemetry.rowsWritten());
        assertEquals(TerrainSchedulingTelemetry.MAX_ROWS + 1, Files.readAllLines(output).size());
    }

    private static void runFrame(
            final TerrainSchedulingController controller,
            final long frame,
            final double forwardX,
            final double forwardY,
            final double forwardZ,
            final TerrainSchedulingController.FrameInputs inputs
    ) {
        controller.beginFrame(
                frame * 1_000_000_000L,
                0.0,
                0.0,
                0.0,
                forwardX,
                forwardY,
                forwardZ,
                inputs
        );
        controller.updateBacklog(inputs.backlogJobs(), inputs.busyThreads(), inputs.totalThreads());
        controller.beginBuildStage(frame * 1_000_000_000L + 100L);
        controller.endBuildStage(frame * 1_000_000_000L + 200L, 1);
        controller.beginUploadStage(frame * 1_000_000_000L + 300L);
        controller.recordUploadResults(1);
        controller.endUploadStage(frame * 1_000_000_000L + 400L);
        controller.endFrame(frame * 1_000_000_000L + 500L);
    }

    private static TerrainSchedulingController.FrameInputs input(
            final long frameNanos,
            final int backlog,
            final long gpuNanos,
            final int thermal,
            final double memory
    ) {
        return input(
                frameNanos,
                backlog,
                gpuNanos,
                thermal,
                memory,
                PresentationPacingSnapshot.neutral()
        );
    }

    private static TerrainSchedulingController.FrameInputs input(
            final long frameNanos,
            final int backlog,
            final long gpuNanos,
            final int thermal,
            final double memory,
            final PresentationPacingSnapshot pacing
    ) {
        return new TerrainSchedulingController.FrameInputs(
                frameNanos,
                frameNanos,
                gpuNanos,
                backlog,
                1,
                4,
                thermal,
                memory,
                pacing
        );
    }

    private static TerrainSchedulingController.FrameInputs withCpu(
            final TerrainSchedulingController.FrameInputs input,
            final long cpuNanos
    ) {
        return new TerrainSchedulingController.FrameInputs(
                input.frameDurationNanos(),
                cpuNanos,
                input.gpuFrameNanos(),
                input.backlogJobs(),
                input.busyThreads(),
                input.totalThreads(),
                input.thermalState(),
                input.memoryPressure(),
                input.presentationPacing()
        );
    }

    private static PresentationPacingSnapshot unavailablePacing() {
        return new PresentationPacingSnapshot(
                1L,
                PresentationPacingSnapshot.UNAVAILABLE_REFRESH_RATE_HZ,
                PresentationPacingSnapshot.Value.unavailableNanos("test.target", "target-missing"),
                PresentationPacingSnapshot.Value.unavailableNanos("test.present", "present-missing"),
                PresentationPacingSnapshot.Value.unavailableNanos("test.cpu", "cpu-missing"),
                PresentationPacingSnapshot.Value.unavailableNanos("test.gpu", "gpu-missing"),
                PresentationPacingSnapshot.Value.unavailableNanos("test.drawable", "drawable-missing"),
                PresentationPacingSnapshot.Value.unavailableCount("test.in-flight", "in-flight-missing"),
                "test",
                "target-missing"
        );
    }
}
