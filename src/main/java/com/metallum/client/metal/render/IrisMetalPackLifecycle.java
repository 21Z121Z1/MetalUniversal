package com.metallum.client.metal.render;

/**
 * Backend-neutral decision for entering Iris's configured-pack lifecycle.
 */
public final class IrisMetalPackLifecycle {
    public static final String STRICT_PROPERTY = "metallum.iris.strict";
    private static boolean destroyedActiveGeneration;

    private IrisMetalPackLifecycle() {
    }

    public static boolean shouldLoadConfiguredPack(
            final boolean semanticEnabled, final boolean shadersEnabled
    ) {
        return semanticEnabled && shadersEnabled;
    }

    /** True when active shader-pack failures must abort instead of selecting native rendering. */
    public static boolean strictModeRequested() {
        return Boolean.parseBoolean(System.getProperty(STRICT_PROPERTY, "false"));
    }

    /** Records the fixed-Iris reload boundary before {@code loadShaderpack}. */
    public static synchronized void onSemanticPipelineActivated() {
        destroyedActiveGeneration = false;
    }

    public static synchronized void onSemanticPipelineDestroyed() {
        destroyedActiveGeneration = true;
    }

    /**
     * Startup with shaders disabled remains dormant for exact non-Iris
     * rendering. A disable reload after a live semantic generation must run
     * Iris's CPU-only {@code setShadersDisabled} branch.
     */
    public static synchronized boolean consumeDisabledReloadTransition(
            final boolean semanticEnabled, final boolean shadersEnabled
    ) {
        if (!semanticEnabled || shadersEnabled || !destroyedActiveGeneration) {
            return false;
        }
        destroyedActiveGeneration = false;
        return true;
    }
}
