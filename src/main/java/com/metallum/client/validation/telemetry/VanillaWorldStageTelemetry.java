package com.metallum.client.validation.telemetry;

/** Static process-wide facade used by vanilla stage wrappers. */
public final class VanillaWorldStageTelemetry {
    private static final WorldStageRecorder RECORDER = new WorldStageRecorder();

    private VanillaWorldStageTelemetry() {
    }

    public static WorldStageRecorder recorder() {
        return RECORDER;
    }

    public static long contextId(Object context) {
        return RECORDER.contextId(context);
    }

    public static void record(WorldStageRecorder.Stage stage, long contextId, long startNanos,
                              long endNanos, boolean completed, long queueBefore, long queueAfter,
                              long workCount, int resultCode) {
        RECORDER.record(stage, contextId, startNanos, endNanos, completed, queueBefore, queueAfter,
                workCount, resultCode);
    }

    public static WorldStageRecorder.Report report(String sourceSha, String trialId, String status) {
        return RECORDER.report(sourceSha, trialId, status);
    }
}
