package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class Metal4TerrainVisibleIcbAuthorityContractTest {
    @Test
    void realTerrainSubmissionConsumesVisiblePlanBeforeAllVisibleReuse() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalRenderPass.java"
        ));
        int visible = source.indexOf("TerrainVisibleDrawPlan.tryBuild(snapshot, candidates)");
        int owner = source.indexOf("TerrainGpuVisibilityProbe.ownerForEpoch(", visible);
        int encode = source.indexOf("owner.encodeVisibleGpu(", owner);
        int execute = source.indexOf("owner.execute(", encode);
        int reusable = source.indexOf("owner.hasReusableGpuIcb(device, primitiveType, snapshot)");
        assertTrue(visible >= 0, "real draw path must build the visible mapping");
        assertTrue(owner > visible, "visible mapping must select its exact producer epoch");
        assertTrue(encode > owner, "real draw path must invoke the visible ICB author");
        assertTrue(execute > encode, "visible ICB must execute through the existing owner");
        assertTrue(reusable > execute, "epoch-visible authority must precede all-visible immutable reuse");
    }

    @Test
    void visibleFailureFallsThroughInsteadOfDroppingDrawAuthority() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalRenderPass.java"
        ));
        int visible = source.indexOf("owner.encodeVisibleGpu(");
        int invalidate = source.indexOf("owner.invalidateVisibilityAuthored();", visible);
        int allVisible = source.indexOf("owner.encodeGpu(", invalidate);
        int finalFallback = source.indexOf("return owner.execute(", allVisible);
        assertTrue(visible >= 0);
        assertTrue(invalidate > visible);
        assertTrue(allVisible > invalidate, "visible failure must continue into all-visible GPU authoring");
        assertTrue(finalFallback > allVisible, "existing CPU ICB/indirect fallback must remain reachable");
    }


    @Test
    void visibleIcbPropertyAloneRequestsSubmission() throws Exception {
        String renderPass = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalRenderPass.java"
        ));
        assertTrue(renderPass.contains(
                "&& !TerrainSceneSnapshot.VISIBLE_GPU_ICB_ENABLED)"
        ));
        assertTrue(renderPass.contains(
                "|| TerrainSceneSnapshot.VISIBLE_GPU_ICB_ENABLED)"
        ));
    }
}
