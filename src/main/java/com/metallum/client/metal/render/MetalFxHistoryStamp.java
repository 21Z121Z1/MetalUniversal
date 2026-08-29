package com.metallum.client.metal.render;

/**
 * Immutable identity for one MetalFX history transaction.
 *
 * <p>The frame id identifies encode order while {@code historyEpoch} identifies
 * the reset generation. A submit callback from an older epoch must never mutate
 * current temporal history after a resize, scene cut, device failure, or other
 * explicit reset.</p>
 */
record MetalFxHistoryStamp(long frameId, long historyEpoch) {
    MetalFxHistoryStamp {
        if (frameId <= 0L) {
            throw new IllegalArgumentException("MetalFX frame id must be positive");
        }
        if (historyEpoch <= 0L) {
            throw new IllegalArgumentException("MetalFX history epoch must be positive");
        }
    }

    boolean canCommit(final long currentFrameId, final long currentHistoryEpoch) {
        return frameId == currentFrameId && historyEpoch == currentHistoryEpoch;
    }

    boolean canReject(final long currentHistoryEpoch) {
        return historyEpoch == currentHistoryEpoch;
    }
}
