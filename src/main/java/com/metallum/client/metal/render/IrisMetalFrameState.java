package com.metallum.client.metal.render;

import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;

/** Render-thread state owned by one Iris Metal world-pipeline generation. */
final class IrisMetalFrameState {
    private final FrameUpdateNotifier updateNotifier = new FrameUpdateNotifier();
    private WorldRenderingPhase phase = WorldRenderingPhase.NONE;
    private WorldRenderingPhase overridePhase;
    private boolean removePhase;
    private boolean renderingWorld;
    private boolean mainBound;

    FrameUpdateNotifier updateNotifier() {
        return this.updateNotifier;
    }

    void beginWorldRendering() {
        this.renderingWorld = true;
        this.mainBound = true;
    }

    void endWorldRendering() {
        this.renderingWorld = false;
        removePhaseIfNeeded();
    }

    WorldRenderingPhase phase() {
        removePhaseIfNeeded();
        return this.overridePhase != null ? this.overridePhase : this.phase;
    }

    void setPhase(final WorldRenderingPhase next) {
        if (next == WorldRenderingPhase.NONE) {
            this.removePhase = true;
            return;
        }
        this.removePhase = false;
        this.phase = next;
    }

    void setOverridePhase(final WorldRenderingPhase overridePhase) {
        this.overridePhase = overridePhase;
    }

    void setMainBound(final boolean mainBound) {
        this.mainBound = mainBound;
    }

    boolean shouldOverrideShaders(final boolean writesMainTarget) {
        return this.renderingWorld && this.mainBound && writesMainTarget;
    }

    private void removePhaseIfNeeded() {
        if (this.removePhase) {
            this.phase = WorldRenderingPhase.NONE;
            this.removePhase = false;
        }
    }
}
