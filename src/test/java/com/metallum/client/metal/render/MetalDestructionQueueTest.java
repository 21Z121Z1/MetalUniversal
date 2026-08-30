package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the destruction-delay contract established for the in-flight model:
 * with MAX_SUBMITS_IN_FLIGHT submits pipelined and the semaphore wait at
 * submit N confirming only submit N-depth+1, an action queued during submit N
 * must not run before the rotation whose wait has confirmed submit N itself.
 */
final class MetalDestructionQueueTest {
    @Test
    void rejectsNonPositiveDepth() {
        assertThrows(IllegalArgumentException.class, () -> new MetalDestructionQueue(0));
        assertThrows(IllegalArgumentException.class, () -> new MetalDestructionQueue(-1));
    }

    @Test
    void actionQueuedNowRunsOnFourthRotationAtDepthFour() {
        MetalDestructionQueue queue = new MetalDestructionQueue(
                MetalCommandEncoder.MAX_SUBMITS_IN_FLIGHT + 1
        );
        int[] runs = {0};
        queue.add(() -> runs[0]++);
        for (int rotation = 1; rotation <= 3; rotation++) {
            queue.rotate();
            assertEquals(
                    0,
                    runs[0],
                    "action ran on rotation " + rotation
                            + "; the confirmed-complete submit is still older than the queueing submit"
            );
        }
        queue.rotate();
        assertEquals(
                1,
                runs[0],
                "action must run exactly on the rotation whose semaphore wait confirmed the queueing submit"
        );
        queue.rotate();
        assertEquals(1, runs[0], "action must not run twice");
    }

    @Test
    void callbackCanEnqueueWithoutJoiningCurrentDrain() {
        MetalDestructionQueue queue = new MetalDestructionQueue(2);
        List<String> events = new ArrayList<>();
        queue.add(() -> {
            events.add("first");
            queue.add(() -> events.add("second"));
        });

        queue.rotate();
        assertEquals(List.of(), events);

        queue.rotate();
        assertEquals(List.of("first"), events);
        assertEquals(1, queue.pendingActionCount());

        queue.rotate();
        assertEquals(List.of("first"), events);

        queue.rotate();
        assertEquals(List.of("first", "second"), events);
        assertEquals(0, queue.pendingActionCount());
    }

    @Test
    void closeDrainsEverySlot() {
        MetalDestructionQueue queue = new MetalDestructionQueue(
                MetalCommandEncoder.MAX_SUBMITS_IN_FLIGHT + 1
        );
        int[] runs = {0};
        for (int slot = 0; slot < 4; slot++) {
            queue.add(() -> runs[0]++);
            queue.rotate();
        }
        queue.add(() -> runs[0]++);
        queue.close();
        assertEquals(5, runs[0], "close() must drain all queued actions");
        assertEquals(0, queue.pendingActionCount());
    }

    @Test
    void closeAlsoDrainsActionsQueuedByClosingCallbacks() {
        MetalDestructionQueue queue = new MetalDestructionQueue(3);
        List<String> events = new ArrayList<>();
        queue.add(() -> {
            events.add("first");
            queue.add(() -> {
                events.add("second");
                queue.add(() -> events.add("third"));
            });
        });

        queue.close();

        assertEquals(List.of("first", "second", "third"), events);
        assertEquals(0, queue.pendingActionCount());
    }
}
