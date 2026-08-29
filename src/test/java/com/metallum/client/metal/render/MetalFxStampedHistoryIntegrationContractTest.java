package com.metallum.client.metal.render;

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
