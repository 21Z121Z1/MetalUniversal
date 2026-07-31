package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class IrisMetalExecutionGraphTest {
    @Test
    void drawBuffersFlipBeforeExplicitTrueFlip() {
        BitSet before = new BitSet();
        IrisMetalExecutionGraph.FlipTransition transition = IrisMetalExecutionGraph.transition(
                before, new int[]{0}, Map.of(0, true), 2
        );

        assertEquals(new BitSet(), transition.readsFromAlt());
        assertEquals(new BitSet(), transition.stateAfter(), "the two toggles cancel");
    }

    @Test
    void explicitFalseSuppressesImplicitDrawBuffersFlip() {
        IrisMetalExecutionGraph.FlipTransition transition = IrisMetalExecutionGraph.transition(
                new BitSet(), new int[]{1}, Map.of(1, false), 2
        );

        assertEquals(new BitSet(), transition.stateAfter());
    }

    @Test
    void invalidAndRepeatedDrawBuffersFailClosed() {
        assertThrows(
                IllegalStateException.class,
                () -> IrisMetalExecutionGraph.validateDrawBuffers("bad", new int[]{2}, 2)
        );
        assertThrows(
                IllegalStateException.class,
                () -> IrisMetalExecutionGraph.validateDrawBuffers("duplicate", new int[]{0, 0}, 2)
        );
    }

    @Test
    void explicitFlipTargetRangeIsStrict() {
        assertThrows(
                IllegalArgumentException.class,
                () -> IrisMetalExecutionGraph.transition(
                        new BitSet(), new int[]{0}, Map.of(2, true), 2
                )
        );
    }
}
