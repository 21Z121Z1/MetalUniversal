package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards Iris's effective shadow attachment layout against synthetic MRT widening. */
final class IrisMetalShadowDrawBufferCompatibilityTest {
    @Test
    void absentDirectiveKeepsIrisSingleTargetDefault() {
        int[] irisDefault = {0};

        int[] resolved = IrisMetalShadowPipeline.copyEffectiveShadowDrawBuffers(irisDefault);

        assertArrayEquals(new int[]{0}, resolved,
                "unknown directive provenance must not invent a second shadow attachment");
        assertNotSame(irisDefault, resolved, "the resolved draw-buffer list must not alias Iris state");
    }

    @Test
    void explicitMultipleTargetsRemainUnchanged() {
        assertArrayEquals(
                new int[]{0, 1},
                IrisMetalShadowPipeline.copyEffectiveShadowDrawBuffers(new int[]{0, 1})
        );
    }

    @Test
    void shadowStorageImagesUseRawPhysicalViews() throws IOException {
        String targets = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/IrisMetalShadowTargets.java"
        ));
        String pipeline = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/IrisMetalShadowPipeline.java"
        ));
        assertTrue(targets.contains("return colorTargets.physicalView(checked, readsFromAlt.get(checked));"));
        assertTrue(pipeline.contains("this.targets.storageView(shadowTarget, pass.readsFromAlt())"));
        assertTrue(pipeline.contains("this.targets.storageView(shadowTarget, compute.info.readsFromAlt())"));
        assertTrue(pipeline.contains("return this.targets.colorTargets().readView(target);"));
    }
}
