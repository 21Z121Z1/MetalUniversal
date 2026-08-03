package com.metallum.client.metal.render;

import com.metallum.Metallum;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
final class MetalDestructionQueue {
    private final List<Runnable>[] queues;
    private int currentQueueIndex;
    private boolean closed;

    @SuppressWarnings("unchecked")
    MetalDestructionQueue(final int queueCount) {
        if (queueCount <= 0) {
            throw new IllegalArgumentException("Destruction queue depth must be positive");
        }
        this.queues = (List<Runnable>[]) new List<?>[queueCount];
        for (int i = 0; i < queueCount; i++) {
            this.queues[i] = new ArrayList<>();
        }
    }

    void add(final Runnable destroyAction) {
        if (destroyAction == null) {
            return;
        }
        if (this.closed) {
            // MetalCommandEncoder closes this queue only after all submitted GPU
            // work has completed. A resource wrapper may still outlive the device
            // and release itself later; execute that release immediately instead
            // of appending it to a queue that will never rotate again.
            runDestroyAction(destroyAction);
            return;
        }
        this.queues[this.currentQueueIndex].add(destroyAction);
    }

    void rotate() {
        if (this.closed) {
            return;
        }
        this.currentQueueIndex = (this.currentQueueIndex + 1) % this.queues.length;
        this.drain(this.currentQueueIndex);
    }

    void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;

        // Preserve the same oldest-to-newest order as repeatedly rotating the
        // queue, while preventing a destroy callback from re-enqueuing work into
        // a slot that has already been drained. Re-entrant additions execute
        // immediately through add() once closed is true.
        for (int offset = 1; offset <= this.queues.length; offset++) {
            this.drain((this.currentQueueIndex + offset) % this.queues.length);
        }
    }

    private void drain(final int queueIndex) {
        List<Runnable> toDestroy = this.queues[queueIndex];
        this.queues[queueIndex] = new ArrayList<>();
        for (Runnable destroyAction : toDestroy) {
            runDestroyAction(destroyAction);
        }
    }

    private static void runDestroyAction(final Runnable destroyAction) {
        try {
            destroyAction.run();
        } catch (Exception e) {
            Metallum.LOGGER.error("[metallum] Destroy action threw an exception; resource may have leaked", e);
        }
    }
}
