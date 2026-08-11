package com.metallum.client.metal.render;

import com.metallum.Metallum;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-depth deferred retirement queue with allocation-free rotation.
 *
 * <p>Each frame slot owns two lists. Producers append to {@code pending}; a
 * rotation swaps pending with the slot's empty drain list, so callbacks may
 * enqueue additional retirements without mutating the list currently being
 * iterated. The drained list is cleared and reused on the slot's next turn.</p>
 *
 * <p>Closing is a terminal state. Once every submitted GPU operation has been
 * observed complete, delayed retirement no longer provides safety and would
 * instead strand releases in slots that can never rotate again. Additions made
 * during or after close therefore run synchronously.</p>
 */
@Environment(EnvType.CLIENT)
final class MetalDestructionQueue {
    private final Slot[] slots;
    private int currentQueueIndex;
    private boolean closed;

    MetalDestructionQueue(final int queueCount) {
        if (queueCount <= 0) {
            throw new IllegalArgumentException("Metal destruction queue depth must be positive");
        }
        this.slots = new Slot[queueCount];
        for (int i = 0; i < queueCount; i++) {
            this.slots[i] = new Slot();
        }
    }

    void add(final Runnable destroyAction) {
        if (destroyAction == null) {
            return;
        }
        if (this.closed) {
            runDestroyAction(destroyAction);
            return;
        }
        this.slots[this.currentQueueIndex].pending.add(destroyAction);
    }

    void rotate() {
        if (this.closed) {
            return;
        }
        this.currentQueueIndex = (this.currentQueueIndex + 1) % this.slots.length;
        this.drainSlot(this.currentQueueIndex);
    }

    void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;

        // Preserve the order produced by repeated rotations. Marking the queue
        // closed first is load-bearing: a callback that retires another object
        // executes that retirement immediately instead of placing it into a slot
        // already visited by this terminal drain.
        for (int offset = 1; offset <= this.slots.length; offset++) {
            this.drainSlot((this.currentQueueIndex + offset) % this.slots.length);
        }
    }

    int pendingActionCount() {
        int count = 0;
        for (Slot slot : this.slots) {
            count += slot.pending.size();
            count += slot.draining.size();
        }
        return count;
    }

    boolean isClosed() {
        return this.closed;
    }

    private void drainSlot(final int queueIndex) {
        List<Runnable> toDestroy = this.slots[queueIndex].beginDrain();
        for (int index = 0; index < toDestroy.size(); index++) {
            runDestroyAction(toDestroy.get(index));
        }
        toDestroy.clear();
    }

    private static void runDestroyAction(final Runnable destroyAction) {
        try {
            destroyAction.run();
        } catch (Exception exception) {
            Metallum.LOGGER.error(
                    "[metallum] Destroy action threw an exception; resource may have leaked",
                    exception
            );
        }
    }

    private static final class Slot {
        private ArrayList<Runnable> pending = new ArrayList<>();
        private ArrayList<Runnable> draining = new ArrayList<>();

        private List<Runnable> beginDrain() {
            ArrayList<Runnable> previousPending = this.pending;
            this.pending = this.draining;
            this.draining = previousPending;
            return this.draining;
        }
    }
}
