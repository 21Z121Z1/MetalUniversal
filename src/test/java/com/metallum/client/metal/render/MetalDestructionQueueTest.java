package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Guards the destruction-delay contract established for the in-flight model:
 * with MAX_SUBMITS_IN_FLIGHT submits pipelined and the semaphore wait at
 * submit N confirming only submit N-depth+1, an action queued during submit N
 * must not run before the rotation whose wait has confirmed submit N itself.
 * With queue depth = MAX_SUBMITS_IN_FLIGHT + 1 that is the 4th rotation after
 * the add.
 */
final class MetalDestructionQueueTest {
    @Test
    void rejectsNonPositiveDepth() {
        assertThrows(IllegalArgumentException.class, () -> new MetalDestructionQueue(0));
        assertThrows(IllegalArgumentException.class, () -> new MetalDestructionQueue(-1));
    }

    @Test
    void actionQueuedNowRunsOnFourthRotationAtDepthFour() {
        MetalDestructionQueue queue = new MetalDestructionQueue(MetalCommandEncoder.MAX_SUBMITS_IN_FLIGHT + 1);
        int[] runs = {0};
        queue.add(() -> runs[0]++);
        for (int rotation = 1; rotation <= 3; rotation++) {
            queue.rotate();
            assertEquals(0, runs[0], "action ran on rotation " + rotation + "; the confirmed-complete submit is still older than the queueing submit");
        }
        queue.rotate();
        assertEquals(1, runs[0], "action must run exactly on the rotation whose semaphore wait confirmed the queueing submit");
        queue.rotate();
        assertEquals(1, runs[0], "action must not run twice");
    }

    @Test
    void closeDrainsEverySlot() {
        MetalDestructionQueue queue = new MetalDestructionQueue(MetalCommandEncoder.MAX_SUBMITS_IN_FLIGHT + 1);
        int[] runs = {0};
        for (int slot = 0; slot < 4; slot++) {
            queue.add(() -> runs[0]++);
            queue.rotate();
        }
        queue.add(() -> runs[0]++);
        queue.close();
        queue.close();
        assertEquals(5, runs[0], "close() must drain all queued actions exactly once");
    }

    @Test
    void closePreservesRotationOrder() {
        MetalDestructionQueue queue = new MetalDestructionQueue(3);
        List<Integer> releases = new ArrayList<>();

        queue.add(() -> releases.add(0));
        queue.rotate();
        queue.add(() -> releases.add(1));
        queue.rotate();
        queue.add(() -> releases.add(2));

        queue.close();

        assertEquals(List.of(0, 1, 2), releases);
    }

    @Test
    void additionsAfterCloseExecuteImmediatelyInsteadOfLeaking() {
        MetalDestructionQueue queue = new MetalDestructionQueue(4);
        AtomicInteger releases = new AtomicInteger();

        queue.close();
        queue.add(releases::incrementAndGet);
        queue.rotate();

        assertEquals(1, releases.get());
    }

    @Test
    void reentrantAdditionDuringCloseExecutesImmediately() {
        MetalDestructionQueue queue = new MetalDestructionQueue(2);
        List<String> releases = new ArrayList<>();

        queue.add(() -> {
            releases.add("outer");
            queue.add(() -> releases.add("inner"));
        });

        queue.close();

        assertEquals(List.of("outer", "inner"), releases);
    }
}
