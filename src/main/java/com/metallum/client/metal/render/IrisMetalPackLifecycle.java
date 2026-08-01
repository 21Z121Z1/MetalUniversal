package com.metallum.client.metal.render;

/**
 * Backend-neutral decision for entering Iris's configured-pack lifecycle.
 */
public final class IrisMetalPackLifecycle {
    private static final int NO_GENERATION = Integer.MIN_VALUE;
    private static final int LEGACY_GENERATION = Integer.MIN_VALUE + 1;
    private static int selectedGeneration = NO_GENERATION;
    private static int destroyedSelectedGeneration = NO_GENERATION;

    private IrisMetalPackLifecycle() {
    }

    public static boolean shouldLoadConfiguredPack(
            final boolean semanticEnabled, final boolean shadersEnabled
    ) {
        return semanticEnabled && shadersEnabled;
    }

    /** Records the fixed-Iris reload boundary before {@code loadShaderpack}. */
    public static synchronized void onSemanticPipelineActivated() {
        onSemanticPipelineActivated(LEGACY_GENERATION);
    }

    /** Records a newly published generation and clears the prior teardown receipt. */
    public static synchronized void onSemanticPipelineActivated(final int generation) {
        selectedGeneration = generation;
        destroyedSelectedGeneration = NO_GENERATION;
    }

    /** Records Iris selecting an already cached dimension generation. */
    public static synchronized void onSemanticPipelineSelected(final int generation) {
        selectedGeneration = generation;
        destroyedSelectedGeneration = NO_GENERATION;
    }

    public static synchronized void onSemanticPipelineDestroyed() {
        onSemanticPipelineDestroyed(LEGACY_GENERATION);
    }

    /**
     * Records teardown only for the generation that was selected at the time
     * Iris destroyed it. Destroying an inactive cached dimension must not make
     * a later shaders-off reload execute {@code setShadersDisabled()} twice.
     */
    public static synchronized void onSemanticPipelineDestroyed(final int generation) {
        if (selectedGeneration != generation) {
            return;
        }
        selectedGeneration = NO_GENERATION;
        destroyedSelectedGeneration = generation;
    }

    /**
     * Startup with shaders disabled remains dormant for exact non-Iris
     * rendering. A disable reload after a live semantic generation must run
     * Iris's CPU-only {@code setShadersDisabled} branch.
     */
    public static synchronized boolean consumeDisabledReloadTransition(
            final boolean semanticEnabled, final boolean shadersEnabled
    ) {
        if (!semanticEnabled || shadersEnabled || destroyedSelectedGeneration == NO_GENERATION) {
            return false;
        }
        destroyedSelectedGeneration = NO_GENERATION;
        return true;
    }
}
