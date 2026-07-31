package com.metallum.client.metal.render;

/** Backend-neutral decisions for entering and retiring Iris's configured-pack lifecycle. */
public final class IrisMetalPackLifecycle {
    public static final String STRICT_PROPERTY = "metallum.iris.strict";
    private static boolean destroyedActiveGeneration;

    private IrisMetalPackLifecycle() {
    }

    public static boolean shouldLoadConfiguredPack(
            final boolean metalPipelineEnabled, final boolean shadersEnabled
    ) {
        return metalPipelineEnabled && shadersEnabled;
    }

    /** Active shader-pack failures must not silently select vanilla rendering. */
    public static boolean strictModeRequested() {
        return Boolean.parseBoolean(System.getProperty(STRICT_PROPERTY, "true"));
    }

    public static synchronized void onSemanticPipelineActivated() {
        destroyedActiveGeneration = false;
    }

    public static synchronized void onSemanticPipelineDestroyed() {
        destroyedActiveGeneration = true;
    }

    /**
     * Lets Iris execute its own CPU-only {@code setShadersDisabled} branch
     * after a live Metal generation has been retired. Startup with shaders
     * disabled remains dormant and does not enter that branch.
     */
    public static synchronized boolean consumeDisabledReloadTransition(
            final boolean metalPipelineEnabled, final boolean shadersEnabled
    ) {
        if (!metalPipelineEnabled || shadersEnabled || !destroyedActiveGeneration) {
            return false;
        }
        destroyedActiveGeneration = false;
        return true;
    }
}
