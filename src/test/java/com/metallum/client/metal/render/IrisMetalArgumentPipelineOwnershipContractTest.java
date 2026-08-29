package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalArgumentPipelineOwnershipContractTest {
    @Test
    void renderPassesAliasPipelineOwnedInFlightState() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/IrisMetalArgumentBindingRuntime.java"
        ));

        assertTrue(source.contains("private static final Map<Object, State> PIPELINES = new WeakHashMap<>()"));
        assertTrue(source.contains("State state = PIPELINES.get(pipeline)"));
        assertTrue(source.contains("PIPELINES.put(pipeline, state)"));
        assertTrue(source.contains("PASSES.put(pass, state)"));

        // Submission rotates each pipeline ring once, rather than once for every
        // short-lived pass that happened to alias that pipeline.
        assertTrue(source.contains("new HashSet<>(PIPELINES.values())"));
        assertFalse(source.contains("for (State state : PASSES.values()) state.ring.advanceAfterSubmit()"));

        // Pipeline keys stay weak and State must not retain the generation owner.
        int stateStart = source.indexOf("private static final class State");
        assertTrue(stateStart >= 0);
        String state = source.substring(stateStart);
        assertFalse(state.contains("private final Object pipeline"));
    }
}
