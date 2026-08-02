package com.metallum.client.performance;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded queue whose tasks execute only after frame-budget admission. */
public final class DeferredRenderWorkQueue {
    private static final int DEFAULT_CAPACITY = 1024;
    private final int capacity;
    private final AtomicLong sequence = new AtomicLong();
    private final PriorityQueue<QueuedWork> queue = new PriorityQueue<>(
            Comparator.comparingInt((QueuedWork work) -> work.priority().ordinal())
                    .thenComparingLong(QueuedWork::sequence)
    );
    private long enqueued;
    private long executed;
    private long rejected;
    private long deferredDrains;

    public DeferredRenderWorkQueue() {
        this(DEFAULT_CAPACITY);
    }

    public DeferredRenderWorkQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public synchronized boolean enqueue(
            String label,
            IrisMetalFrameBudgetController.WorkCategory category,
            Priority priority,
            long estimatedNanos,
            Runnable task
    ) {
        if (task == null || category == null || priority == null) {
            throw new IllegalArgumentException("work fields must not be null");
        }
        if (queue.size() >= capacity) {
            rejected++;
            return false;
        }
        queue.add(new QueuedWork(
                sequence.getAndIncrement(),
                label == null ? "" : label,
                category,
                priority,
                Math.max(1L, estimatedNanos),
                task
        ));
        enqueued++;
        return true;
    }

    /** Executes in priority order and stops at the first task that misses budget. */
    public int drain(IrisMetalFrameBudgetController controller, int maximumTasks) {
        if (controller == null) throw new IllegalArgumentException("controller must not be null");
        int limit = Math.max(0, maximumTasks);
        int completed = 0;
        while (completed < limit) {
            QueuedWork work;
            synchronized (this) {
                work = queue.peek();
            }
            if (work == null) break;
            if (!controller.reserve(work.category(), work.estimatedNanos(), System.nanoTime())) {
                synchronized (this) { deferredDrains++; }
                break;
            }
            synchronized (this) {
                if (queue.peek() != work) continue;
                queue.remove();
            }
            work.task().run();
            synchronized (this) { executed++; }
            completed++;
        }
        return completed;
    }

    public synchronized int size() {
        return queue.size();
    }

    public synchronized void clear() {
        queue.clear();
    }

    public synchronized Counters counters() {
        return new Counters(enqueued, executed, rejected, deferredDrains, queue.size());
    }

    public enum Priority {
        IMMEDIATE,
        HIGH,
        NORMAL,
        LOW
    }

    private record QueuedWork(
            long sequence,
            String label,
            IrisMetalFrameBudgetController.WorkCategory category,
            Priority priority,
            long estimatedNanos,
            Runnable task
    ) {}

    public record Counters(long enqueued, long executed, long rejected, long deferredDrains, int queued) {}
}
