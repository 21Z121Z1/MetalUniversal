package com.metallum.client.terrain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        assertEquals(2_000_000L, first.decision().buildBudgetNanos());
        assertEquals(2_000_000L, first.decision().uploadBudgetNanos());
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
    void pacingSnapshotIsCarriedAsEvidenceWithoutChangingBudgetInputs() {
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
        assertEquals(2_000_000L, controller.decision().buildBudgetNanos());
        assertEquals(2_000_000L, controller.decision().uploadBudgetNanos());
    }

    @Test
    void disabledControllerIsANoOp() {
        TerrainSchedulingController controller = new TerrainSchedulingController(false, 0);
        runFrame(controller, 1L, 0.0, 0.0, 1.0, input(10_000_000L, 128, 30_000_000L, 3, 1.0));

        assertTrue(controller.decision().usesSodiumDefaults());
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
        assertEquals(33, TerrainSchedulingTelemetry.CSV_HEADER.split(",").length);
        assertTrue(lines.get(1).contains(",16666667,false,conservative-60hz-fallback,"));
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
        return new TerrainSchedulingController.FrameInputs(
                frameNanos,
                frameNanos,
                gpuNanos,
                backlog,
                1,
                4,
                thermal,
                memory
        );
    }
}
