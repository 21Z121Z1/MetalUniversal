from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    source = p.read_text()
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"{path}: anchor count={count}: {old[:120]!r}")
    p.write_text(source.replace(old, new, 1))


path = "src/main/java/com/metallum/client/metal/render/MetalFxManager.java"

# Process-local monotonic frame id + reset generation. The epoch is deliberately
# separate from configRevision: camera cuts and transient failures reset temporal
# history without changing the user configuration.
replace_once(
    path,
    "    private int phaseCount;\n    private int phase;\n    private boolean historyReset = true;\n",
    "    private int phaseCount;\n"
    "    private int phase;\n"
    "    private long historyFrameId;\n"
    "    private long historyEpoch = 1L;\n"
    "    private boolean historyReset = true;\n",
)

# Count only valid world-scene frames. The stamp is captured later, at the exact
# point where encoded history becomes owned by a command-buffer transaction.
replace_once(
    path,
    "        this.sceneFrame = true;\n        long sceneFrameStartNanos = System.nanoTime();\n",
    "        this.sceneFrame = true;\n"
    "        this.historyFrameId = Math.incrementExact(this.historyFrameId);\n"
    "        long sceneFrameStartNanos = System.nanoTime();\n",
)

old_callback = """        if (historyTransactionEncoded) {
            Matrix4f submittedViewProjection = new Matrix4f(this.currentViewProjection);
            int submittedNextPhase = (phase + 1) % phaseCount;
            encoder.onCurrentSubmit(
                    () -> {
                        this.historyReset = false;
                        this.previousViewProjection.set(submittedViewProjection);
                        this.previousMatrixValid = true;
                        this.motionStateStore.commitSubmittedFrame();
                        this.phase = submittedNextPhase;
                    },
                    () -> {
                        this.motionStateStore.discardFrame();
                        resetHistoryInternal("Metal command buffer failed after temporal encode");
                    }
            );
        } else {
            this.motionStateStore.discardFrame();
        }"""
new_callback = """        if (historyTransactionEncoded) {
            Matrix4f submittedViewProjection = new Matrix4f(this.currentViewProjection);
            int submittedNextPhase = (phase + 1) % phaseCount;
            MetalFxHistoryStamp submittedHistory = new MetalFxHistoryStamp(
                    this.historyFrameId, this.historyEpoch
            );
            encoder.onCurrentSubmit(
                    () -> {
                        // submit callbacks may be delayed until presentation. A
                        // resize/scene cut/reset in between revokes this frame's
                        // authority to publish temporal history.
                        if (!submittedHistory.canCommit(this.historyFrameId, this.historyEpoch)) {
                            return;
                        }
                        this.historyReset = false;
                        this.previousViewProjection.set(submittedViewProjection);
                        this.previousMatrixValid = true;
                        this.motionStateStore.commitSubmittedFrame();
                        this.phase = submittedNextPhase;
                    },
                    () -> {
                        // A failure in the active epoch poisons all dependent
                        // successors. A failure from an already-reset epoch is
                        // stale and must not discard the new frame's staging.
                        if (!submittedHistory.canReject(this.historyEpoch)) {
                            return;
                        }
                        this.motionStateStore.discardFrame();
                        resetHistoryInternal("Metal command buffer failed after temporal encode");
                    }
            );
        } else {
            this.motionStateStore.discardFrame();
        }"""
replace_once(path, old_callback, new_callback)

# Reset revokes every outstanding callback from the old temporal generation.
replace_once(
    path,
    "    private void resetHistoryInternal(final String reason) {\n"
    "        historyReset = true;\n",
    "    private void resetHistoryInternal(final String reason) {\n"
    "        this.historyEpoch = Math.incrementExact(this.historyEpoch);\n"
    "        historyReset = true;\n",
)

Path("src/test/java/com/metallum/client/metal/render/MetalFxStampedHistoryIntegrationContractTest.java").write_text(
    """package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalFxStampedHistoryIntegrationContractTest {
    @Test
    void resetGenerationGuardsSubmitCallbacks() throws Exception {
        String manager = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalFxManager.java"));
        assertTrue(manager.contains("private long historyEpoch = 1L"));
        assertTrue(manager.contains("Math.incrementExact(this.historyFrameId)"));
        assertTrue(manager.contains("new MetalFxHistoryStamp("));
        assertTrue(manager.contains("submittedHistory.canCommit(this.historyFrameId, this.historyEpoch)"));
        assertTrue(manager.contains("submittedHistory.canReject(this.historyEpoch)"));
        assertTrue(manager.contains("this.historyEpoch = Math.incrementExact(this.historyEpoch)"));
    }
}
"""
)
