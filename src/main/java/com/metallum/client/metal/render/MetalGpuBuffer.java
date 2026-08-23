package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLHazardTrackingMode;
import com.metallum.client.metal.render.mtl.MTLResourceOptions;
import com.metallum.client.metal.render.mtl.MTLStorageMode;
import com.metallum.client.validation.contract.RenderContractRuntime;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
/**
 * Public because MixinExtras generates an {@code Args} bridge for the upload
 * deduplication hook. The generated bridge lives in a synthetic package and
 * must be able to resolve this method-descriptor type at runtime.
 */
public class MetalGpuBuffer extends GpuBuffer {
    private final MetalDevice device;
    private final String logicalLabel;
    private final boolean cpuAccessible;
    private final boolean dynamic;
    private final long resourceOptions;
    private final long allocationSize;
    private MetalAllocationIdentity allocationIdentity;
    @Nullable
    private MemorySegment nativeHandle;
    @Nullable
    private ByteBuffer storage;
    private boolean closed;

    MetalGpuBuffer(final MetalDevice device, @GpuBuffer.Usage final int usage, final long size) {
        this(device, null, usage, size);
    }

    MetalGpuBuffer(
            final MetalDevice device,
            final @Nullable Supplier<String> label,
            @GpuBuffer.Usage final int usage,
            final long size
    ) {
        super(usage, size);
        this.device = device;
        this.logicalLabel = normalizeLabel(label == null ? null : label.get());
        this.allocationIdentity = MetalAllocationIdentity.allocate(this.logicalLabel);

        this.dynamic = isDynamic(usage);
        this.cpuAccessible = isCpuAccessible(usage) || this.dynamic;
        this.resourceOptions = toMtlResourceOptions(usage);
        if (size <= 0L) {
            throw new IllegalArgumentException("Metal buffer size must be > 0 (got " + size + ")");
        }
        long aligned = (size + 15L) & ~15L;
        if (aligned <= 0L) {
            throw new IllegalArgumentException("Metal buffer size overflow after alignment: " + size);
        }
        this.allocationSize = aligned;

        MemorySegment pooled = device.tryAcquirePooledBuffer(this.allocationSize, this.resourceOptions);
        if (!MetalNativeBridge.isNullHandle(pooled)) {
            this.nativeHandle = pooled;
            if (this.cpuAccessible) {
                MemorySegment contents = MetalNativeBridge.metallum_get_buffer_contents(pooled);
                if (MetalNativeBridge.isNullHandle(contents)) {
                    MetalNativeBridge.metallum_release_object(pooled);
                    this.nativeHandle = null;
                    throw new IllegalStateException("MTLBuffer.contents returned null for pooled buffer (size=" + this.allocationSize + ", resourceOptions=" + this.resourceOptions + ")");
                }
                this.storage = MetalNativeBridge.nativeByteBufferView(contents, this.allocationSize).order(ByteOrder.nativeOrder());
            }
            return;
        }

        long max = device.maxBufferAllocationSize();
        if (max > 0L && this.allocationSize > max) {
            throw new IllegalArgumentException("Metal buffer size " + this.allocationSize + " exceeds device max " + max);
        }
        this.nativeHandle = MetalNativeBridge.metallum_create_buffer(device.metalDeviceHandle(), this.allocationSize, this.resourceOptions);
        if (MetalNativeBridge.isNullHandle(this.nativeHandle)) {
            throw new IllegalStateException("Failed to create Metal buffer (size=" + this.allocationSize + ", resourceOptions=" + this.resourceOptions + ", device=" + this.device.getClass().getSimpleName() + ")");
        }

        if (this.cpuAccessible) {
            MemorySegment contents = MetalNativeBridge.metallum_get_buffer_contents(this.nativeHandle);
            if (MetalNativeBridge.isNullHandle(contents)) {
                MetalNativeBridge.metallum_release_object(this.nativeHandle);
                this.nativeHandle = null;
                throw new IllegalStateException("MTLBuffer.contents returned null (size=" + this.allocationSize + ", resourceOptions=" + this.resourceOptions + ")");
            }

            this.storage = MetalNativeBridge.nativeByteBufferView(contents, this.allocationSize).order(ByteOrder.nativeOrder());
        }
    }

    MetalGpuBuffer(final MetalDevice device, @GpuBuffer.Usage final int usage, final long size, final @Nullable MemorySegment wrappedHandle) {
        super(usage, size);
        this.device = device;
        this.logicalLabel = "metal-buffer";
        this.allocationIdentity = MetalAllocationIdentity.allocate(this.logicalLabel);
        this.cpuAccessible = false;
        this.dynamic = false;
        this.resourceOptions = 0L;
        this.allocationSize = size;
        this.nativeHandle = wrappedHandle;
        this.storage = null;
    }

    ByteBuffer sliceStorage(final long offset, final long length) {
        if (this.storage == null) {
            throw new IllegalStateException("Buffer is not CPU-accessible");
        }

        ByteBuffer duplicate = this.storage.duplicate().order(this.storage.order());
        duplicate.position(Math.toIntExact(offset));
        duplicate.limit(Math.toIntExact(offset + length));
        return duplicate.slice().order(this.storage.order());
    }

    MemorySegment nativeHandle() {
        if (this.nativeHandle == null || this.nativeHandle.address() == 0L) {
            throw new IllegalStateException("Native Metal buffer is closed or null");
        }
        return this.nativeHandle;
    }

    MetalAllocationIdentity allocationIdentity() {
        return allocationIdentity;
    }

    long allocationId() {
        return allocationIdentity.allocationId();
    }

    String allocationDebugId() {
        return "metal-buffer-" + allocationId();
    }

    String logicalLabel() {
        return logicalLabel;
    }

    /** Observes the current renderer-owned backing identity when tracing is enabled. */
    void registerAllocationIdentity() {
        observeAllocationIdentity();
    }

    /** Narrow source compatibility for existing validation call sites. */
    @Deprecated
    long validationResourceId() {
        return allocationId();
    }

    /** Narrow source compatibility for existing validation call sites. */
    @Deprecated
    String validationDebugId() {
        return allocationDebugId();
    }

    boolean isDynamic() {
        return this.dynamic;
    }

    long allocationSize() {
        return this.allocationSize;
    }

    long resourceOptions() {
        return this.resourceOptions;
    }

    ByteBuffer currentStorage() {
        if (this.storage == null) {
            throw new IllegalStateException("Buffer is not CPU-accessible");
        }
        return this.storage.duplicate().order(this.storage.order());
    }

    void swapBacking(final MemorySegment handle, final ByteBuffer storage) {
        if (RenderContractRuntime.observing()) {
            RenderContractRuntime.invalidateResourceAllocations(
                    this.allocationId(),
                    this.allocationDebugId()
            );
        }
        this.allocationIdentity = MetalAllocationIdentity.allocate(this.logicalLabel);
        this.nativeHandle = handle;
        this.storage = storage;
        observeAllocationIdentity();
    }

    @Override
    public boolean isClosed() {
        return this.closed || this.nativeHandle == null;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.storage = null;
        if (this.nativeHandle != null) {
            if (RenderContractRuntime.observing()) {
                RenderContractRuntime.invalidateResourceAllocations(
                        this.allocationId(),
                        this.allocationDebugId()
                );
            }
            MemorySegment handle = this.nativeHandle;
            this.nativeHandle = null;
            this.device.queueBufferRelease(handle, this.allocationSize, this.resourceOptions);
        }
    }

    @Override
    public GpuBufferSlice.@NonNull MappedView map(final long offset, final long length, final boolean read, final boolean write) {
        if (this.isClosed()) {
            throw new IllegalStateException("Buffer already closed");
        }
        if (!read && !write) {
            throw new IllegalArgumentException("At least read or write must be true");
        }
        if (read && (this.usage() & GpuBuffer.USAGE_MAP_READ) == 0) {
            throw new IllegalStateException("Buffer is not readable");
        }
        if (write && (this.usage() & GpuBuffer.USAGE_MAP_WRITE) == 0) {
            throw new IllegalStateException("Buffer is not writable");
        }
        ByteBuffer mapped = this.sliceStorage(offset, length);
        return new GpuBufferSlice.MappedView(this.slice(offset, length), mapped, () -> {
        });
    }

    public int getUsage() {
        return this.usage();
    }

    private static boolean isCpuAccessible(@GpuBuffer.Usage final int usage) {
        return (usage & GpuBuffer.USAGE_MAP_READ) != 0
                || (usage & GpuBuffer.USAGE_MAP_WRITE) != 0
                || (usage & GpuBuffer.USAGE_HINT_CLIENT_STORAGE) != 0;
    }

    private static boolean isDynamic(@GpuBuffer.Usage final int usage) {
        return (usage & GpuBuffer.USAGE_UNIFORM) != 0 && (usage & GpuBuffer.USAGE_COPY_DST) != 0;
    }

    private static long toMtlResourceOptions(@GpuBuffer.Usage final int usage) {
        MTLStorageMode storageMode = isCpuAccessible(usage) || isDynamic(usage) ? MTLStorageMode.Shared : MTLStorageMode.Private;
        return MTLResourceOptions.of(storageMode, MTLHazardTrackingMode.Untracked);
    }

    private void observeAllocationIdentity() {
        if (!RenderContractRuntime.observing() || this.nativeHandle == null) {
            return;
        }
        RenderContractRuntime.identifyAllocation(
                this.logicalLabel,
                this.allocationId(),
                this.allocationIdentity.generation(),
                this.allocationDebugId(),
                "BUFFER",
                Math.toIntExact(Math.min(this.allocationSize, Integer.MAX_VALUE)),
                1,
                1,
                0,
                1,
                this.usage()
        );
    }

    private static String normalizeLabel(final String label) {
        return label == null || label.isBlank() ? "metal-buffer" : label;
    }
}
