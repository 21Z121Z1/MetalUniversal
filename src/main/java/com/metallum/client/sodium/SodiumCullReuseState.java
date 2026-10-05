package com.metallum.client.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.CullType;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.SectionTree;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;

import java.util.Map;

/**
 * Per-RenderSectionManager state for the verify-only cull-reuse experiment.
 *
 * <p>Ordinary Sodium culling always runs. An eligible old REGULAR/WIDE pair is
 * retained only long enough to compare it with the newly produced oracle pair.
 */
public final class SodiumCullReuseState {
    private record ReferencePass(
            SodiumCullReusePolicy.Inputs inputs,
            SectionTree regular,
            SectionTree wide,
            boolean taskListEmpty
    ) {
    }

    private long graphGeneration;
    private ReferencePass reference;
    private SodiumCullReusePolicy.Inputs pendingInput;
    private ReferencePass pendingCandidate;

    public void graphDirty() {
        this.graphGeneration++;
    }

    public void beforeSchedule(
            Viewport viewport,
            float regularDistance,
            float localDistance,
            boolean occlusion,
            boolean cameraChanged,
            boolean needsGraphUpdate,
            Map<CullType, SectionTree> currentTrees,
            SectionStorage sections,
            int minSectionY,
            int maxSectionY
    ) {
        SodiumCullReusePolicy.Inputs current = new SodiumCullReusePolicy.Inputs(
                viewport,
                regularDistance,
                localDistance,
                occlusion,
                this.graphGeneration
        );

        boolean referenceTreesStillCurrent = this.reference != null
                && currentTrees.get(CullType.REGULAR) == this.reference.regular()
                && currentTrees.get(CullType.WIDE) == this.reference.wide();

        SodiumCullReusePolicy.AdmissionReason reason = SodiumCullReusePolicy.evaluate(
                this.reference == null ? null : this.reference.inputs(),
                current,
                cameraChanged,
                needsGraphUpdate,
                referenceTreesStillCurrent,
                this.reference != null && this.reference.taskListEmpty(),
                sections,
                minSectionY,
                maxSectionY
        );
        SodiumCullReuseTelemetry.recordAdmission(reason);

        this.pendingInput = current;
        this.pendingCandidate = reason == SodiumCullReusePolicy.AdmissionReason.ELIGIBLE
                ? this.reference
                : null;
    }

    public void afterConsume(
            SectionTree oracleRegular,
            SectionTree oracleWide,
            DeferredTaskList oracleTasks,
            int minSectionY,
            int maxSectionY
    ) {
        if (this.pendingInput == null) {
            return;
        }

        boolean generationStable = this.pendingInput.graphGeneration() == this.graphGeneration;
        boolean oracleTaskListEmpty = oracleTasks == null || oracleTasks.isEmpty();

        if (this.pendingCandidate != null) {
            if (!generationStable) {
                SodiumCullReuseTelemetry.recordRacyCompletion();
            } else {
                SodiumCullReuseOracle.Comparison comparison = SodiumCullReuseOracle.compare(
                        this.pendingCandidate.regular(),
                        this.pendingCandidate.wide(),
                        oracleRegular,
                        oracleWide,
                        oracleTaskListEmpty,
                        this.pendingInput.viewport().getChunkCoord(),
                        minSectionY,
                        maxSectionY
                );
                if (comparison.matches()) {
                    SodiumCullReuseTelemetry.recordMatch();
                } else {
                    SodiumCullReuseTelemetry.recordMismatch();
                }
            }
        }

        this.reference = generationStable && oracleRegular != null && oracleWide != null
                ? new ReferencePass(this.pendingInput, oracleRegular, oracleWide, oracleTaskListEmpty)
                : null;
        this.pendingInput = null;
        this.pendingCandidate = null;
    }
}
