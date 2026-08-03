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
 */
@Environment(EnvType.CLIENT)
final class MetalDestructionQueue {
    private final Slot[] slots;
    private int currentQueueIndex;

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
        this.slots[this.currentQueueIndex].pending.add(destroyAction);
    }

    void rotate() {
        this.currentQueueIndex = (this.currentQueueIndex + 1) % this.slots.length;
        Slot slot = this.slots[this.currentQueueIndex];
        List<Runnable> toDestroy = slot.beginDrain();
        for (int index = 0; index < toDestroy.size(); index++) {
            Runnable destroyAction = toDestroy.get(index);
            try {
                destroyAction.run();
            } catch (Exception exception) {
                Metallum.LOGGER.error(
                        "[metallum] Destroy action threw an exception; resource may have leaked",
                        exception
                );
            }
        }
        toDestroy.clear();
    }

    void close() {
        for (int i = 0; i < this.slots.length; i++) {
            this.rotate();
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
