package com.metallum.client.metal.render;

/**
 * Feature gates for advanced Iris-on-Metal optimizations.
 *
 * <p>The stable {@code metallum.iris.*} property names and the earlier
 * {@code metallum.iris.experimental.*} aliases are both accepted. Each lane is
 * independent so local validation can bisect regressions without changing the
 * conservative fallback.</p>
 */
public final class IrisMetalAdvancedOptimizationConfig {
    public static final boolean HAZARD_GRAPH = bool("metallum.iris.hazardGraph", true);
    public static final boolean RENDER_PASS_FUSION = alias(
            "metallum.iris.passFusion", "metallum.iris.experimental.passFusion", false);
    public static final boolean COMPUTE_GROUPING = alias(
            "metallum.iris.computeGrouping", "metallum.iris.experimental.computeGrouping", false);
    public static final boolean ATTACHMENT_LIVENESS = alias(
            "metallum.iris.attachmentLiveness", "metallum.iris.experimental.loadStoreLiveness", false);
    public static final boolean DEPTH_LIVENESS = alias(
            "metallum.iris.depthLiveness", "metallum.iris.experimental.resourcePruning", false);
    public static final boolean FINAL_COLOR_FUSION = alias(
            "metallum.iris.finalColorFusion", "metallum.iris.experimental.finalColorFusion", false);
    public static final boolean ARGUMENT_TABLES = alias(
            "metallum.iris.argumentTables", "metallum.iris.experimental.argumentTables", false);
    public static final boolean INDIRECT_SUBMISSION = alias(
            "metallum.iris.indirectSubmission", "metallum.iris.experimental.icb", false);
    /**
     * Allows the allocator to request true tile-memory-only attachments after
     * the physical lifetime compiler has proved a pass-local lifetime.  This
     * is deliberately independent from the diagnostic attachment-liveness
     * switch: a receipt may be collected without changing allocation policy.
     */
    public static final boolean MEMORYLESS_ATTACHMENTS = alias(
            "metallum.iris.memorylessAttachments",
            "metallum.iris.experimental.memorylessAttachments",
            false
    );
    /** Enables generation-safe placement-heap aliasing on a subsequent target allocation. */
    public static final boolean HEAP_ALIASING = alias(
            "metallum.iris.heapAliasing",
            "metallum.iris.experimental.heapAliasing",
            false
    );

    private IrisMetalAdvancedOptimizationConfig() {
    }

    private static boolean bool(final String property, final boolean fallback) {
        return Boolean.parseBoolean(System.getProperty(property, Boolean.toString(fallback)));
    }

    private static boolean alias(
            final String stableProperty,
            final String legacyProperty,
            final boolean fallback
    ) {
        return resolveAlias(
                System.getProperty(stableProperty),
                System.getProperty(legacyProperty),
                fallback
        );
    }

    /**
     * Resolves one stable/legacy property pair. An explicitly supplied stable
     * value, including {@code false}, always wins over the legacy alias.
     * Keeping this rule in one small pure function makes the alias contract
     * testable without mutating JVM-global system properties.
     */
    static boolean resolveAlias(
            final String stableValue,
            final String legacyValue,
            final boolean fallback
    ) {
        if (stableValue != null) return Boolean.parseBoolean(stableValue);
        return legacyValue == null ? fallback : Boolean.parseBoolean(legacyValue);
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                HAZARD_GRAPH,
                RENDER_PASS_FUSION,
                COMPUTE_GROUPING,
                ATTACHMENT_LIVENESS,
                DEPTH_LIVENESS,
                FINAL_COLOR_FUSION,
                ARGUMENT_TABLES,
                INDIRECT_SUBMISSION,
                MEMORYLESS_ATTACHMENTS,
                HEAP_ALIASING
        );
    }

    public record Snapshot(
            boolean hazardGraph,
            boolean renderPassFusion,
            boolean computeGrouping,
            boolean attachmentLiveness,
            boolean depthLiveness,
            boolean finalColorFusion,
            boolean argumentTables,
            boolean indirectSubmission,
            boolean memorylessAttachments,
            boolean heapAliasing
    ) {
    }
}
