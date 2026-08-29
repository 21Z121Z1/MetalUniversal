package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the synchronization proof that makes untracked placement aliases legal. */
final class IrisMetalPlacementAliasOrderingContractTest {
    @Test
    void placementAliasesRemainBehindGlobalPassOrdering() throws Exception {
        String encoder = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java"
        ));
        String nativeSource = Files.readString(Path.of("src/main/native/MetallumNative.swift"));
        String compiler = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/IrisMetalAttachmentLifetimeCompiler.java"
        ));
        String recipe = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/IrisMetalHeapAliasRecipe.java"
        ));

        // Metal 3: every render pass consumes the previous global fence and
        // publishes its fragment completion back to that same fence.
        assertTrue(encoder.contains("waitRenderFences(final MTLRenderCommandEncoder encoder)"));
        assertTrue(encoder.contains("encoder.waitForFence(fence, MTLRenderStages.VertexAndFragment)"));
        assertTrue(encoder.contains("renderEncoder.updateFence("));
        int computeStart = encoder.indexOf("MTLComputeCommandEncoder computeCommandEncoder()");
        int computeEnd = encoder.indexOf("long encoderGeneration()", computeStart);
        assertTrue(computeStart >= 0 && computeEnd > computeStart);
        String computeMethod = encoder.substring(computeStart, computeEnd);
        assertTrue(computeMethod.contains("encoder.waitForFence(fence)"));
        assertTrue(encoder.contains("computeEncoder.updateFence(fence)"));

        // Metal 4 deliberately ignores the legacy fence calls; new render
        // encoders instead consume all relevant prior queue stages before any
        // vertex/fragment access. This is the alias-memory visibility edge.
        assertTrue(nativeSource.contains(
                "afterQueueStages: [.blit, .fragment, .dispatch]"
        ));
        assertTrue(nativeSource.contains(
                "beforeStages: [.vertex, .fragment]"
        ));
        assertTrue(nativeSource.contains(
                "metal4RenderBridge(pointer) != nil { return }"
        ));

        // The current physical lifetime binder is intentionally raster-only.
        // Any compute/copy consumer makes the receipt unresolved, so a recipe
        // cannot alias resources whose non-raster lifetime is unrepresented.
        assertTrue(compiler.contains("if (pass.type() == PassType.RENDER)"));
        assertTrue(compiler.contains("unresolvedConsumers.add(pass.planPassKey() + \":\" + use.resource()"));
        assertTrue(recipe.contains("receipt.unresolvedConsumers().isEmpty()"));
        assertTrue(recipe.contains("previous.lastUse() >= current.firstUse()"));
    }
}
