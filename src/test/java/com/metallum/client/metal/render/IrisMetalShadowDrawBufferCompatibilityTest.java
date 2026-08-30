package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

final class IrisMetalShadowDrawBufferCompatibilityTest {
    @Test
    void unknownDrawBuffersKeepTheIrisCompatibilityPair() {
        assertArrayEquals(
                new int[]{0, 1},
                IrisMetalShadowPipeline.resolveShadowDrawBuffers(true, new int[]{0})
        );
    }

    @Test
    void explicitDrawBuffersRemainUnchanged() {
        assertArrayEquals(
                new int[]{3, 1},
                IrisMetalShadowPipeline.resolveShadowDrawBuffers(false, new int[]{3, 1})
        );
    }
}
