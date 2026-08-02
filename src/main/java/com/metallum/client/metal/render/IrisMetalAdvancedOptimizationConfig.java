package com.metallum.client.metal.render;

/**
 * Feature gates for advanced Iris-on-Metal optimizations.
 *
 * <p>Each lane is independently switchable so local validation can isolate
 * regressions. Conservative analysis remains available even when an execution
 * lane is disabled.</p>
 */
public final class IrisMetalAdvancedOptimizationConfig {
    public static final boolean HAZARD_GRAPH = bool("metallum.iris.hazardGraph", true);
    public static final boolean COMPUTE_GROUPING = bool("metallum.iris.computeGrouping", false);
    public static final boolean ATTACHMENT_LIVENESS = bool("metallum.iris.attachmentLiveness", false);
    public static final boolean DEPTH_LIVENESS = bool("metallum.iris.depthLiveness", false);
    public static final boolean FINAL_COLOR_FUSION = bool("metallum.iris.finalColorFusion", false);
    public static final boolean ARGUMENT_TABLES = bool("metallum.iris.argumentTables", false);
    public static final boolean INDIRECT_SUBMISSION = bool("metallum.iris.indirectSubmission", false);

    private IrisMetalAdvancedOptimizationConfig() {
    }

    private static boolean bool(final String property, final boolean fallback) {
        return Boolean.parseBoolean(System.getProperty(property, Boolean.toString(fallback)));
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                HAZARD_GRAPH,
                COMPUTE_GROUPING,
                ATTACHMENT_LIVENESS,
                DEPTH_LIVENESS,
                FINAL_COLOR_FUSION,
                ARGUMENT_TABLES,
                INDIRECT_SUBMISSION
        );
    }

    public record Snapshot(
            boolean hazardGraph,
            boolean computeGrouping,
            boolean attachmentLiveness,
            boolean depthLiveness,
            boolean finalColorFusion,
            boolean argumentTables,
            boolean indirectSubmission
    ) {
    }
}
