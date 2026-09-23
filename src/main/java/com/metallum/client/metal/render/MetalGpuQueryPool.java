package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;

import java.lang.foreign.MemorySegment;
import java.util.OptionalLong;

/** A GPU counter pool; a slot is readable only after its owning submission completes. */
@Environment(EnvType.CLIENT)
final class MetalGpuQueryPool implements GpuQueryPool {
    private final MetalDevice device;
    private final int size;
    private final long[] generations;
    private final boolean[] pending;
    private final boolean[] completed;
    private final int[] backends;
    private final long[] values;
    private MemorySegment nativeHandle;
    private boolean closed;

    MetalGpuQueryPool(final MetalDevice device, final int size) {
        if (size <= 0 || size > 4096) {
            throw new IllegalArgumentException("Timestamp query count must be in 1..4096");
        }
        this.device = device;
        this.size = size;
        this.generations = new long[size];
        this.pending = new boolean[size];
        this.completed = new boolean[size];
        this.backends = new int[size];
        this.values = new long[size];
        this.nativeHandle = device.getDeviceInfo().timestampPeriod() > 0.0F
                ? MetalNativeBridge.renderPearlTimestampPoolCreate(device.metalDeviceHandle(), size)
                : MemorySegment.NULL;
    }

    MemorySegment nativeHandle() {
        if (closed) throw new IllegalStateException("Timestamp query pool is closed");
        if (MetalNativeBridge.isNullHandle(nativeHandle)) {
            throw new UnsupportedOperationException("GPU timestamp counter sampling is unavailable on this Metal device");
        }
        return nativeHandle;
    }

    void requireDevice(final MetalDevice expected) {
        if (device != expected) {
            throw new IllegalArgumentException("Timestamp query pool belongs to another Metal device");
        }
    }

    synchronized long beginWrite(final int index) {
        checkIndex(index);
        nativeHandle();
        if (pending[index]) {
            throw new IllegalStateException("Timestamp query " + index + " is still in flight");
        }
        long generation = ++generations[index];
        pending[index] = true;
        completed[index] = false;
        backends[index] = 0;
        values[index] = 0L;
        return generation;
    }

    void written(final MetalCommandEncoder encoder, final int index, final long generation, final int backend) {
        if (backend != 1 && backend != 2) {
            failed(index, generation);
            throw new UnsupportedOperationException("This Metal encoder cannot sample a GPU timestamp at this boundary");
        }
        synchronized (this) {
            if (generations[index] != generation || !pending[index]) {
                throw new IllegalStateException("Timestamp query slot changed before submission");
            }
            backends[index] = backend;
        }
        encoder.onCurrentSubmit(() -> { },
                () -> completed(index, generation),
                () -> failed(index, generation));
    }

    synchronized void failed(final int index, final long generation) {
        if (generations[index] == generation) {
            pending[index] = false;
            completed[index] = false;
        }
    }

    private synchronized void completed(final int index, final long generation) {
        if (generations[index] == generation && !closed) {
            pending[index] = false;
            completed[index] = true;
        }
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public @NonNull OptionalLong getValue(final int index) {
        checkIndex(index);
        device.commandEncoder().pollCompletedSubmissions();
        synchronized (this) {
            return readCompletedValue(index);
        }
    }

    @Override
    public OptionalLong @NonNull [] getValues(final int index, final int count) {
        if (index < 0 || count < 0 || index > size - count) {
            throw new IndexOutOfBoundsException("Timestamp query range is outside the pool");
        }
        device.commandEncoder().pollCompletedSubmissions();
        OptionalLong[] result = new OptionalLong[count];
        synchronized (this) {
            for (int i = 0; i < count; i++) {
                result[i] = readCompletedValue(index + i);
            }
        }
        return result;
    }

    private OptionalLong readCompletedValue(final int index) {
        if (closed) throw new IllegalStateException("Timestamp query pool is closed");
        if (!completed[index]) return OptionalLong.empty();
        long value = values[index];
        if (value == 0L) {
            value = MetalNativeBridge.renderPearlTimestampRead(nativeHandle, index, backends[index]);
            values[index] = value;
        }
        return value == 0L ? OptionalLong.empty() : OptionalLong.of(value);
    }

    private void checkIndex(final int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Timestamp query " + index + " outside 0.." + (size - 1));
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (!MetalNativeBridge.isNullHandle(nativeHandle)) {
            device.queueResourceRelease(nativeHandle);
            nativeHandle = MemorySegment.NULL;
        }
    }
}
