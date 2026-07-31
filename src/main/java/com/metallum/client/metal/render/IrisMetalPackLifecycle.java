package com.metallum.client.metal.render;

/** Backend-neutral decision for entering Iris's configured-pack lifecycle. */
public final class IrisMetalPackLifecycle {
    private IrisMetalPackLifecycle() {
    }

    public static boolean shouldLoadConfiguredPack(
            final boolean metalPipelineEnabled, final boolean shadersEnabled
    ) {
        return metalPipelineEnabled && shadersEnabled;
    }
}
