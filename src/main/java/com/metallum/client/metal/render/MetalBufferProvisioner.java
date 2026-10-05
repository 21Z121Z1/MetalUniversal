package com.metallum.client.metal.render;

import com.metallum.client.metal.render.mtl.MTLBlitCommandEncoder;
import com.metallum.client.metal.render.mtl.MTLCommandBuffer;
import com.metallum.client.metal.render.mtl.MTLCommandQueue;
import com.metallum.client.metal.render.mtl.MTLStorageMode;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Moves first-touch page wiring for large private MTLBuffers ahead of their
 * first render submission. Work is serialized on a daemon thread but encoded
 * on the device's existing command queue, preserving queue ordering.
 */
final class MetalBufferProvisioner implements AutoCloseable {
    static final String ENABLE_PROPERTY = "metallum.opt.largeBufferProvision";
    static final String THRESHOLD_PROPERTY = "metallum.opt.largeBufferProvisionBytes";
    static final long DEFAULT_THRESHOLD = 32L * 1024L * 1024L;

    private static final AtomicLong scheduled = new AtomicLong();
    private static final AtomicLong completed = new AtomicLong();
    private static final AtomicLong failures = new AtomicLong();
    private static final AtomicLong foregroundWaits = new AtomicLong();
    private static final AtomicLong provisionNanos = new AtomicLong();

    private final MTLCommandQueue queue;
    private final ExecutorService executor;

    MetalBufferProvisioner(MTLCommandQueue queue) {
        this.queue = queue;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "metallum-buffer-provision");
            thread.setDaemon(true);
            return thread;
        });
    }

    CompletableFuture<Void> schedule(MemorySegment buffer, long size, long resourceOptions) {
        if (!eligible(size, resourceOptions)) {
            return CompletableFuture.completedFuture(null);
        }
        scheduled.incrementAndGet();
        return CompletableFuture.runAsync(() -> provision(buffer, size), this.executor);
    }

    private void provision(MemorySegment buffer, long size) {
        long started = System.nanoTime();
        try {
            MTLCommandBuffer commandBuffer = this.queue.makeCommandBuffer("large buffer provision");
            try {
                MTLBlitCommandEncoder encoder = commandBuffer.makeBlitCommandEncoder("large buffer first-touch");
                encoder.fillBuffer(buffer, 0L, size, (byte) 0);
                encoder.endEncoding();
                commandBuffer.commit();
                if (!commandBuffer.waitUntilCompleted(15_000L) || !commandBuffer.completedSuccessfully()) {
                    throw new IllegalStateException("Large MTLBuffer provisioning command did not complete successfully");
                }
                completed.incrementAndGet();
            } finally {
                commandBuffer.close();
            }
        } catch (RuntimeException failure) {
            failures.incrementAndGet();
            throw failure;
        } finally {
            provisionNanos.addAndGet(Math.max(0L, System.nanoTime() - started));
        }
    }

    static void await(CompletableFuture<Void> future) {
        if (future == null || future.isDone() && !future.isCompletedExceptionally()) {
            return;
        }
        foregroundWaits.incrementAndGet();
        try {
            future.join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw failure;
        }
    }

    static boolean eligible(long size, long resourceOptions) {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) return false;
        if (size < thresholdBytes()) return false;
        long storageMode = (resourceOptions >>> 4) & 0x3L;
        return storageMode == MTLStorageMode.Private.value;
    }

    static long thresholdBytes() {
        String raw = System.getProperty(THRESHOLD_PROPERTY);
        if (raw == null || raw.isBlank()) return DEFAULT_THRESHOLD;
        try {
            return Math.max(1L << 20, Long.parseLong(raw.trim()));
        } catch (NumberFormatException ignored) {
            return DEFAULT_THRESHOLD;
        }
    }

    static Snapshot snapshot() {
        return new Snapshot(
                scheduled.get(),
                completed.get(),
                failures.get(),
                foregroundWaits.get(),
                provisionNanos.get()
        );
    }

    public static long scheduledCount() {
        return scheduled.get();
    }

    public static long completedCount() {
        return completed.get();
    }

    public static long failureCount() {
        return failures.get();
    }

    public static long foregroundWaitCount() {
        return foregroundWaits.get();
    }

    @Override
    public void close() {
        this.executor.shutdown();
        try {
            if (!this.executor.awaitTermination(20L, TimeUnit.SECONDS)) {
                this.executor.shutdownNow();
                this.executor.awaitTermination(5L, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            this.executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    record Snapshot(
            long scheduled,
            long completed,
            long failures,
            long foregroundWaits,
            long provisionNanos
    ) {}
}
