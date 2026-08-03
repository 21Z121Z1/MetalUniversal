package com.metallum.client.metal.render;

import org.jspecify.annotations.Nullable;

/**
 * Immutable result of resolving the user-facing Metal 4 switches against the
 * device capability and the dependencies between migration stages.
 *
 * <p>Property reading remains at the device boundary. This type contains only
 * deterministic policy, which keeps invalid combinations out of the native ABI
 * and makes every implication independently testable.</p>
 */
record Metal4RuntimeConfiguration(
        boolean requested,
        boolean supported,
        boolean available,
        boolean compiler,
        boolean present,
        boolean mainQueuePilot,
        boolean mainRenderer,
        boolean barrier,
        boolean residency,
        @Nullable String rejectionReason
) {
    static Metal4RuntimeConfiguration resolve(
            final boolean requested,
            final boolean supported,
            final boolean compilerRequested,
            final boolean presentRequested,
            final boolean mainQueuePilotRequested,
            final boolean mainRendererRequested,
            final boolean barrierRequested,
            final boolean residencyRequested
    ) {
        boolean available = requested && supported;
        boolean mainRenderer = available && mainRendererRequested;
        boolean compiler = available && (compilerRequested || mainRenderer);
        boolean present = compiler && (presentRequested || mainRenderer);
        boolean mainQueuePilot = available && mainQueuePilotRequested;

        // MTLResidencySet is also useful on the Metal 3 queue, so the explicit
        // residency pilot stays independent of the Metal 4 master switch. The
        // main renderer, however, cannot exist without it.
        boolean residency = residencyRequested || mainRenderer;

        String rejectionReason = null;
        if (requested && !supported) {
            rejectionReason = "device or runtime does not support Metal 4";
        } else if (!requested && (
                compilerRequested
                        || presentRequested
                        || mainQueuePilotRequested
                        || mainRendererRequested
        )) {
            rejectionReason = "Metal 4 sub-feature requested while master switch is disabled";
        } else if (presentRequested && available && !compilerRequested && !mainRenderer) {
            rejectionReason = "Metal 4 present requires the compiler path";
        }

        return new Metal4RuntimeConfiguration(
                requested,
                supported,
                available,
                compiler,
                present,
                mainQueuePilot,
                mainRenderer,
                barrierRequested,
                residency,
                rejectionReason
        );
    }
}
