package com.metallum.client.validation.telemetry;

/** Static process-wide facade used by vanilla stage wrappers. */
public final class VanillaWorldStageTelemetry {
    private static final WorldStageRecorder RECORDER = new WorldStageRecorder();

    private VanillaWorldStageTelemetry() {
    }

    public static WorldStageRecorder recorder() {
        return RECORDER;
    }

    public static long contextId(final Object context) {
        return RECORDER.contextId(context);
    }

    public static long begin(final WorldStageRecorder.Stage stage, final long contextId,
                             final long startNanos, final long queueBefore) {
        return RECORDER.begin(stage, contextId, startNanos, queueBefore);
    }

    public static void end(final long token, final long endNanos, final boolean completed,
                           final long queueAfter, final long workCount, final int resultCode) {
        RECORDER.end(token, endNanos, completed, queueAfter, workCount, resultCode);
    }

    public static void beginWindow(final String id, final long startFrameInclusive,
                                   final long nowNanos) {
        RECORDER.beginWindow(id, startFrameInclusive, nowNanos);
    }

    public static void endWindow(final long endFrameExclusive, final long nowNanos) {
        RECORDER.endWindow(endFrameExclusive, nowNanos);
    }

    public static WorldStageRecorder.Report report(final String sourceSha, final String trialId,
                                                   final String status) {
        return RECORDER.report(sourceSha, trialId, status);
    }
}
