package com.metallum.client.performance;

import com.metallum.Metallum;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Writes the bounded recorder state as one self-describing JSON document. */
public final class FrameStutterReportWriter {
    private static final AtomicBoolean HOOK_INSTALLED = new AtomicBoolean();

    private FrameStutterReportWriter() {}

    public static void installRuntimeHook() {
        String configured = System.getProperty(FrameStutterRecorder.REPORT_PROPERTY, "").trim();
        if (configured.isEmpty() || !HOOK_INSTALLED.compareAndSet(false, true)) return;
        Path output = Path.of(configured);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                write(output, FrameStutterRecorder.runtime());
            } catch (Throwable error) {
                Metallum.LOGGER.warn("[metallum] frame stutter report write failed: {}", output, error);
            }
        }, "metallum-frame-stutter-report"));
    }

    public static void write(Path output, FrameStutterRecorder recorder) throws IOException {
        if (output == null || recorder == null) throw new IllegalArgumentException("output and recorder are required");
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        FrameStutterRecorder.Summary summary = recorder.summary();
        List<FrameStutterRecorder.FrameSample> frames = recorder.snapshot();
        StringBuilder json = new StringBuilder(Math.max(4096, frames.size() * 640));
        json.append("{\n  \"schemaVersion\": 1,\n");
        json.append("  \"deadlineEvidence\": \"")
                .append(deadlineEvidence(frames)).append("\",\n");
        json.append("  \"commitTimestampSource\": \"MetalCommandEncoder.submit return\",\n");
        json.append("  \"unavailableMetrics\": [")
                .append("\"drawableAcquireBeginEnd\",\"exactCommandQueueWait\",")
                .append("\"resourceAllocationCountBytes\",\"ffmCallCount\",")
                .append("\"processMetalResidentMemory\",\"attachmentStoreLoadBytes\",")
                .append("\"translucentSortDuration\"],\n");
        appendSummary(json, summary);
        json.append(",\n  \"frames\": [\n");
        for (int index = 0; index < frames.size(); index++) {
            appendFrame(json, frames.get(index));
            if (index + 1 < frames.size()) json.append(',');
            json.append('\n');
        }
        json.append("  ]\n}\n");
        Files.writeString(output, json.toString());
    }

    private static String deadlineEvidence(List<FrameStutterRecorder.FrameSample> frames) {
        if (frames.stream().anyMatch(frame -> frame.deadlineSource() == DisplayDeadlineSnapshot.Source.METAL_DISPLAY_LINK)) {
            return "metal-display-link";
        }
        if (frames.stream().anyMatch(frame -> frame.deadlineSource() == DisplayDeadlineSnapshot.Source.ESTIMATED_CADENCE)) {
            return "estimated-cadence-not-display-link";
        }
        return "unavailable";
    }

    private static void appendSummary(StringBuilder json, FrameStutterRecorder.Summary value) {
        json.append("  \"summary\": {\n")
                .append("    \"frames\": ").append(value.frames()).append(",\n")
                .append("    \"frameTimeP50Ms\": ").append(ms(value.frameP50Nanos())).append(",\n")
                .append("    \"frameTimeP95Ms\": ").append(ms(value.frameP95Nanos())).append(",\n")
                .append("    \"frameTimeP99Ms\": ").append(ms(value.frameP99Nanos())).append(",\n")
                .append("    \"frameTimeP99_9Ms\": ").append(ms(value.frameP999Nanos())).append(",\n")
                .append("    \"onePercentLowFps\": ").append(number(value.onePercentLowFps())).append(",\n")
                .append("    \"pointOnePercentLowFps\": ").append(number(value.pointOnePercentLowFps())).append(",\n")
                .append("    \"framesAboveTwoTimesRollingMedian\": ").append(value.rollingMedianSpikes()).append(",\n")
                .append("    \"framesAbove33_3Ms\": ").append(value.framesAbove33ms()).append(",\n")
                .append("    \"framesAbove50Ms\": ").append(value.framesAbove50ms()).append(",\n")
                .append("    \"framesAbove100Ms\": ").append(value.framesAbove100ms()).append(",\n")
                .append("    \"missedDisplayDeadlines\": ").append(value.missedDeadlines()).append(",\n")
                .append("    \"maxConsecutiveMissedDeadlines\": ").append(value.maxConsecutiveMissedDeadlines()).append(",\n")
                .append("    \"pipelineCompileCount\": ").append(value.pipelineCompileCount()).append(",\n")
                .append("    \"pipelineCompileMs\": ").append(ms(value.pipelineCompileNanos())).append(",\n")
                .append("    \"longestPipelineCompileMs\": ").append(ms(value.longestPipelineCompileNanos())).append(",\n")
                .append("    \"backgroundPipelineCompileCount\": ").append(value.backgroundPipelineCompileCount()).append(",\n")
                .append("    \"commandSubmitCount\": ").append(value.commandSubmitCount()).append(",\n")
                .append("    \"commandSubmitMs\": ").append(ms(value.commandSubmitNanos())).append("\n")
                .append("  }");
    }

    private static void appendFrame(StringBuilder json, FrameStutterRecorder.FrameSample frame) {
        json.append("    {")
                .append("\"frameIndex\":").append(frame.frameIndex()).append(',')
                .append("\"frameBeginNanos\":").append(frame.frameBeginNanos()).append(',')
                .append("\"frameEndNanos\":").append(frame.frameEndNanos()).append(',')
                .append("\"frameTimeMs\":").append(ms(frame.frameNanos())).append(',')
                .append("\"gpuTimeMs\":").append(ms(frame.gpuNanos())).append(',')
                .append("\"commandBufferCommitNanos\":").append(frame.commandBufferCommitNanos()).append(',')
                .append("\"pipelineCompileCount\":").append(frame.pipelineCompileCount()).append(',')
                .append("\"pipelineCompileMs\":").append(ms(frame.pipelineCompileNanos())).append(',')
                .append("\"backgroundPipelineCompileCount\":").append(frame.backgroundPipelineCompileCount()).append(',')
                .append("\"commandSubmitCount\":").append(frame.commandSubmitCount()).append(',')
                .append("\"commandSubmitMs\":").append(ms(frame.commandSubmitNanos())).append(',')
                .append("\"gcPauseMs\":").append(ms(frame.gcPauseNanos())).append(',')
                .append("\"chunkBuildMs\":").append(ms(frame.chunkBuildNanos())).append(',')
                .append("\"chunkUploadMs\":").append(ms(frame.chunkUploadNanos())).append(',')
                .append("\"chunkBuildSubmittedCount\":").append(frame.chunkBuildSubmittedCount()).append(',')
                .append("\"chunkUploadResultCount\":").append(frame.chunkUploadResultCount()).append(',')
                .append("\"deadlineSource\":\"").append(frame.deadlineSource()).append("\",")
                .append("\"displayTargetNanos\":").append(frame.displayTargetNanos()).append(',')
                .append("\"displayDeadlineNanos\":").append(frame.displayDeadlineNanos()).append(',')
                .append("\"missedDeadline\":").append(frame.missedDeadline()).append(',')
                .append("\"rollingMedianSpike\":").append(frame.rollingMedianSpike()).append(',')
                .append("\"primaryCause\":\"").append(frame.primaryCause()).append("\"}");
    }

    private static String ms(long nanos) {
        return number(nanos / 1_000_000.0);
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }
}
