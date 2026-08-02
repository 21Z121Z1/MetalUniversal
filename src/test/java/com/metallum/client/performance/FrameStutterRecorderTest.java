package com.metallum.client.performance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FrameStutterRecorderTest {
    @Test
    void pipelineCompileWinsPrimaryCauseAttribution() {
        FrameStutterRecorder recorder = new FrameStutterRecorder(true, 16);
        long begin = 1_000_000_000L;
        recorder.beginFrame(begin, new DisplayDeadlineSnapshot(
                1L, begin + 16_666_667L, begin + 15_916_667L, 16_666_667L,
                DisplayDeadlineSnapshot.Source.ESTIMATED_CADENCE
        ));
        recorder.recordPipelineCompile("test/pipeline", 20_000_000L, false);
        recorder.endFrame(begin + 50_000_000L, 10_000_000L, 0L, null);

        FrameStutterRecorder.FrameSample sample = recorder.snapshot().getFirst();
        assertEquals(FrameStutterRecorder.StutterCause.PIPELINE_COMPILE, sample.primaryCause());
        assertTrue(sample.missedDeadline());
        assertEquals(1L, sample.pipelineCompileCount());
        assertEquals(20_000_000L, sample.pipelineCompileNanos());
    }

    @Test
    void summaryCalculatesTailMetricsFromFrameTimes() {
        FrameStutterRecorder recorder = new FrameStutterRecorder(true, 32);
        long now = 1_000_000_000L;
        for (int index = 0; index < 10; index++) {
            recorder.beginFrame(now, DisplayDeadlineSnapshot.unavailable());
            long duration = index == 9 ? 100_000_000L : 10_000_000L;
            recorder.endFrame(now + duration, 5_000_000L, 0L, null);
            now += 200_000_000L;
        }
        FrameStutterRecorder.Summary summary = recorder.summary();
        assertEquals(10, summary.frames());
        assertEquals(10_000_000L, summary.frameP50Nanos());
        assertEquals(100_000_000L, summary.frameP99Nanos());
        assertEquals(10.0, summary.onePercentLowFps(), 0.0001);
        assertEquals(1L, summary.framesAbove50ms());
    }

    @Test
    void reportDeclaresEstimatedDeadlineAndMissingSources(@TempDir Path directory) throws Exception {
        FrameStutterRecorder recorder = new FrameStutterRecorder(true, 4);
        long begin = 1_000_000_000L;
        recorder.beginFrame(begin, new DisplayDeadlineSnapshot(
                1L, begin + 16_666_667L, begin + 15_916_667L, 16_666_667L,
                DisplayDeadlineSnapshot.Source.ESTIMATED_CADENCE
        ));
        recorder.endFrame(begin + 12_000_000L, 6_000_000L, 0L, null);
        Path report = directory.resolve("stutter.json");
        FrameStutterReportWriter.write(report, recorder);

        String json = Files.readString(report);
        assertTrue(json.contains("estimated-cadence-not-display-link"));
        assertTrue(json.contains("ffmCallCount"));
        assertTrue(json.contains("frameTimeP99Ms"));
    }
}
