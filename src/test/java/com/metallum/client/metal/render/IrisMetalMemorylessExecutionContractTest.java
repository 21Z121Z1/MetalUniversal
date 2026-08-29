package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalMemorylessExecutionContractTest {
    @Test
    void executionRequiresCurrentResolvedReceiptAndV3DontCareActions() throws Exception {
        String runtime = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/IrisMetalMemorylessPassAttachments.java"));
        String post = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/IrisMetalPostChain.java"));
        String targets = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/IrisMetalRenderTargets.java"));

        assertTrue(runtime.contains("metallum.iris.experimental.memorylessAttachments"));
        assertTrue(runtime.contains("MetalCommandEncoder.explicitColorActionsAvailable()"));
        assertTrue(runtime.contains("\"RESOLVED_CONSERVATIVE\".equals(receipt.status())"));
        assertTrue(runtime.contains("receipt.unresolvedConsumers().isEmpty()"));
        assertTrue(runtime.contains("receipt.targetEpoch() != targets.allocationStamp()"));
        assertTrue(runtime.contains("receipt.targetSignature().equals(IrisMetalAttachmentLifetimeCompiler.targetSignature(targets))"));
        assertTrue(runtime.contains("attachment.load() != IrisMetalOptimizationPlan.LoadAction.DONT_CARE"));
        assertTrue(runtime.contains("persistentWrite.allocationId() != attachment.allocationId()"));
        assertTrue(runtime.contains("loads[slot] = 0"));
        assertTrue(runtime.contains("stores[slot] = 0"));

        assertTrue(post.contains("IrisMetalMemorylessPassAttachments.tryCreate("));
        assertTrue(post.contains("pass, renderOrdinal"));
        assertTrue(post.contains("memoryless.loadActions()"));
        assertTrue(post.contains("memoryless.storeActions()"));
        assertTrue(targets.contains("colorOverrides"));
    }
}
