package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class IrisMetalPassTraceTest {
    @Test
    void bslComposite7TaaModeZeroDoesNotInventTwoPhaseJitter() {
        String source = """
                #define TAA
                #define TAA_MODE 0
                vec2 offset = frameCounter % 2 == 0
                        ? vec2(0.5, 0.0) : vec2(0.0, 0.5);
                """;

        assertEquals("none", IrisMetalPassTrace.jitterRuleFor("composite7", source));
    }

    @Test
    void composite7ModeOneReportsItsActualTwoPhaseRule() {
        String source = """
                #define TAA
                #define TAA_MODE 1
                vec2 offset = frameCounter % 2 == 0
                        ? vec2(0.5, 0.0) : vec2(0.0, 0.5);
                """;

        assertEquals(
                "framemod2=frameCounter%2;offset=(0.5,0)/(0,0.5)",
                IrisMetalPassTrace.jitterRuleFor("composite7", source)
        );
    }

    @Test
    void bslTerrainReportsEightPhaseJitter() {
        String source = """
                uniform float framemod8;
                vec2 jitterOffsets8[8];
                vec2 TAAJitter(vec2 coord, float w) {
                    return coord + jitterOffsets8[int(framemod8)] * w;
                }
                """;

        assertEquals(
                "framemod8=frameCounter%8;jitterOffsets8",
                IrisMetalPassTrace.jitterRuleFor("gbuffers_terrain", source)
        );
    }

    @Test
    void missingTaaModeDoesNotBecomeAnActiveTwoPhaseRule() {
        String source = "vec2 offset = frameCounter % 2 == 0 ? vec2(0.5, 0.0) : vec2(0.0, 0.5);";

        assertEquals(
                "unknown:frameCounter%2 (TAA_MODE not proven)",
                IrisMetalPassTrace.jitterRuleFor("composite7", source)
        );
    }
}
