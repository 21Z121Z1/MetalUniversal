package com.metallum.client.sodium;

/**
 * Independent behavioral rule for recovering a Sodium cull invalidation.
 *
 * <p>The caller samples whether a cull task existed on entry to
 * prepareRenderTrees and whether a task is still pending immediately before
 * Sodium consumes completed results. A disappearance in that narrow interval
 * is the cancellation path that would otherwise lose the dirty bit.
 */
public final class SodiumCullRecoveryPolicy {
    private SodiumCullRecoveryPolicy() {
    }

    public static boolean shouldRestoreInvalidation(
            boolean hadPendingAtPrepareStart,
            boolean hasPendingBeforeConsume
    ) {
        return hadPendingAtPrepareStart && !hasPendingBeforeConsume;
    }
}
