package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector2f;

/** Owns motion history and the begin/finalize/submit frame transaction. */
@Environment(EnvType.CLIENT)
final class MotionVectorPipeline {
    private enum State {
        IDLE,
        OPEN,
        FINALIZED
    }

    private final MetalMotionStateStore stateStore = new MetalMotionStateStore();
    private final ReactiveMaskPipeline reactiveMasks = new ReactiveMaskPipeline();
    private State state = State.IDLE;
    private long nextFrameId = 1L;
    private long historyEpoch = 1L;
    private FrameStamp currentStamp;
    private FinalizedMotionFrame finalizedFrame;
    private FrameStamp lastAcceptedStamp;

    FrameStamp beginFrame() {
        if (state != State.IDLE) {
            discardFrame(currentStamp);
        }
        currentStamp = new FrameStamp(nextFrameId++, historyEpoch);
        finalizedFrame = null;
        state = State.OPEN;
        stateStore.beginFrame();
        reactiveMasks.beginFrame(currentStamp);
        return currentStamp;
    }

    MetalMotionStateStore stateStore() {
        return stateStore;
    }

    ReactiveMaskPipeline reactiveMasks() {
        return reactiveMasks;
    }

    FrameStamp currentStamp() {
        requireState(State.OPEN, State.FINALIZED);
        return currentStamp;
    }

    FinalizedMotionFrame finalizeFrame(
            final MetalGpuTexture depth,
            final MetalGpuTexture motion,
            final MetalGpuTexture reactive,
            final int inputWidth,
            final int inputHeight,
            final Vector2f jitterPixels,
            final boolean reset
    ) {
        requireState(State.OPEN);
        finalizedFrame = new FinalizedMotionFrame(
                currentStamp,
                depth,
                motion,
                reactive,
                inputWidth,
                inputHeight,
                jitterPixels,
                MetalMotionContract.motionVectorScale(inputWidth, inputHeight),
                MotionConvention.PREVIOUS_MINUS_CURRENT_NDC_TOP_LEFT,
                DepthConvention.REVERSED_Z,
                reset,
                reactiveMasks.finish(currentStamp)
        );
        state = State.FINALIZED;
        return finalizedFrame;
    }

    boolean isCurrentFinalized(final FinalizedMotionFrame frame) {
        return state == State.FINALIZED
                && frame != null
                && frame == finalizedFrame
                && currentStamp != null
                && frame.stamp().equals(currentStamp);
    }

    boolean acceptSubmittedFrame(final FinalizedMotionFrame frame) {
        if (!isCurrentFinalized(frame)) {
            return false;
        }
        stateStore.commitSubmittedFrame();
        lastAcceptedStamp = frame.stamp();
        finalizedFrame = null;
        currentStamp = null;
        reactiveMasks.reset();
        state = State.IDLE;
        return true;
    }

    boolean wasSubmittedFrameAccepted(final FrameStamp stamp) {
        return stamp != null
                && stamp.equals(lastAcceptedStamp)
                && stamp.historyEpoch() == historyEpoch;
    }

    void discardFrame(final FrameStamp stamp) {
        if (state == State.IDLE) {
            return;
        }
        if (stamp != null && currentStamp != null && !stamp.equals(currentStamp)) {
            throw new IllegalStateException("Cannot discard motion history for a different frame");
        }
        stateStore.discardFrame();
        finalizedFrame = null;
        currentStamp = null;
        reactiveMasks.reset();
        state = State.IDLE;
        historyEpoch++;
    }

    boolean rejectSubmittedFrame(final FrameStamp submittedStamp) {
        if (submittedStamp == null || submittedStamp.historyEpoch() != historyEpoch) {
            return false;
        }
        historyEpoch++;
        stateStore.reset();
        finalizedFrame = null;
        if (state == State.IDLE) {
            currentStamp = null;
            reactiveMasks.reset();
        } else {
            currentStamp = new FrameStamp(currentStamp.frameId(), historyEpoch);
            reactiveMasks.beginFrame(currentStamp);
            state = State.OPEN;
        }
        return true;
    }

    /**
     * Returns the exact successor consumer that must be released before an
     * older submitted frame poisons this history epoch. A FINALIZED frame has
     * encoded its native consumers but its command buffer has not yet reached
     * the submit-accepted callback; aborting any other state would risk
     * treating already-submitted GPU work as uncommitted.
     */
    FrameStamp uncommittedSuccessorForRejectedFrame(final FrameStamp submittedStamp) {
        if (submittedStamp == null || state != State.FINALIZED
                || currentStamp == null
                || submittedStamp.historyEpoch() != historyEpoch
                || currentStamp.historyEpoch() != submittedStamp.historyEpoch()
                || currentStamp.frameId() == submittedStamp.frameId()) {
            return null;
        }
        return currentStamp;
    }

    void resetHistory() {
        historyEpoch++;
        stateStore.reset();
        finalizedFrame = null;
        if (state == State.IDLE) {
            return;
        }
        currentStamp = new FrameStamp(currentStamp.frameId(), historyEpoch);
        reactiveMasks.beginFrame(currentStamp);
        state = State.OPEN;
    }

    long historyEpoch() {
        return historyEpoch;
    }

    private void requireState(final State... allowed) {
        for (State candidate : allowed) {
            if (state == candidate) {
                return;
            }
        }
        throw new IllegalStateException("Motion pipeline state is " + state);
    }
}
