package com.metallum.client.metal.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBuffer.Usage;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuBufferSlice.MappedView;
import com.mojang.blaze3d.systems.TransientMemory;
import com.mojang.blaze3d.util.TransientBlockAllocator;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Mth;
import org.jspecify.annotations.NonNull;
import org.lwjgl.system.MemoryUtil;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.List;

@Environment(EnvType.CLIENT)
final class MetalTransientMemory implements TransientMemory {
    private static final long BLOCK_SIZE = 524288L;
    private static final long MAX_CPU_ALIGNMENT = 16L;
    private static final long MAX_GPU_ALIGNMENT = 256L;
    private static final int BLOCK_USAGE = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE;

    private final MetalDevice device;
    private final MetalCommandEncoder encoder;
    private final TransientBlockAllocator<Long> cpuBlockAllocator = new TransientBlockAllocator<>(
            BLOCK_SIZE,
            MAX_CPU_ALIGNMENT,
            TransientBlockAllocator.Allocator.create(MemoryUtil::nmemAlloc, MemoryUtil::nmemFree)
    );
    private final TransientBlockAllocator<MetalGpuBuffer> gpuBlockAllocator;
    /** One lightweight GpuBuffer facade per backing/usage pair in the current frame. */
    private final MetalFrameIdentityCache<MetalGpuBuffer, TransientGpuBuffer> frameWrappers =
            new MetalFrameIdentityCache<>();
    /** Reused primitive ordering scratch for multi-upload packing. */
    private int[] multiUploadIndices = new int[0];
    private long submitIndex;
    private boolean closed;

    MetalTransientMemory(final MetalDevice device, final MetalCommandEncoder encoder) {
        this.device = device;
        this.encoder = encoder;
        this.gpuBlockAllocator = new TransientBlockAllocator<>(
                BLOCK_SIZE,
                MAX_GPU_ALIGNMENT,
                TransientBlockAllocator.Allocator.create(this::allocateGpuBlock, this::freeGpuBlock)
        );
    }

    void rotate() {
        this.cpuBlockAllocator.rotate().run();
        this.encoder.queueForDestroy(this.gpuBlockAllocator.rotate());
        this.submitIndex++;
        // Old wrapper objects remain valid through slices that still reference
        // them. Clearing only prevents the next frame from reusing an object
        // whose submit-index lifetime belongs to the previous frame.
        this.frameWrappers.clear();
    }

    void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.frameWrappers.clear();
        this.cpuBlockAllocator.close();
        this.gpuBlockAllocator.close();
    }

    private MetalGpuBuffer allocateGpuBlock(final long size) {
        return new MetalGpuBuffer(this.device, BLOCK_USAGE, size);
    }

    private void freeGpuBlock(final MetalGpuBuffer block) {
        block.close();
    }

    @Override
    public @NonNull ByteBuffer allocateCpu(
            final long size,
            final long alignment,
            final long minimumAllocation,
            final long elementSize
    ) {
        TransientBlockAllocator.Allocation<Long> allocation = this.cpuBlockAllocator.allocate(
                size,
                alignment,
                minimumAllocation,
                elementSize
        );
        return MemoryUtil.memByteBuffer(
                allocation.block() + allocation.offset(),
                Math.toIntExact(allocation.size())
        );
    }

    @Override
    public @NonNull MappedView allocateStaging(
            final long size,
            final long alignment,
            @Usage final int usage,
            final long minimumAllocation,
            final long elementSize
    ) {
        return allocateMapped(size, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public @NonNull GpuBufferSlice allocateGpu(
            final long size,
            final long alignment,
            @Usage final int usage,
            final long minimumAllocation,
            final long elementSize
    ) {
        TransientBlockAllocator.Allocation<MetalGpuBuffer> allocation = this.gpuBlockAllocator.allocate(
                size,
                alignment,
                minimumAllocation,
                elementSize
        );
        return new GpuBufferSlice(
                wrap(allocation.block(), usage),
                allocation.offset(),
                allocation.size()
        );
    }

    @Override
    public @NonNull MappedView allocateGpuMapped(
            final long size,
            final long alignment,
            @Usage final int usage,
            final long minimumAllocation,
            final long elementSize
    ) {
        return allocateMapped(size, alignment, usage, minimumAllocation, elementSize);
    }

    private MappedView allocateMapped(
            final long size,
            final long alignment,
            @Usage final int usage,
            final long minimumAllocation,
            final long elementSize
    ) {
        TransientBlockAllocator.Allocation<MetalGpuBuffer> allocation = this.gpuBlockAllocator.allocate(
                size,
                alignment,
                minimumAllocation,
                elementSize
        );
        GpuBufferSlice slice = new GpuBufferSlice(
                wrap(allocation.block(), usage),
                allocation.offset(),
                allocation.size()
        );
        ByteBuffer hostView = allocation.block().sliceStorage(allocation.offset(), allocation.size());
        return new MappedView(slice, hostView, () -> {
        });
    }

    private MetalGpuBuffer wrap(final MetalGpuBuffer block, @Usage final int usage) {
        TransientGpuBuffer cached = this.frameWrappers.get(block, usage);
        if (cached != null) {
            MetalTransientArenaTelemetry.recordWrapperHit();
            return cached;
        }
        TransientGpuBuffer wrapper = new TransientGpuBuffer(
                this.device,
                block.nativeHandle(),
                usage,
                block.size(),
                this,
                this.submitIndex
        );
        this.frameWrappers.put(block, usage, wrapper);
        MetalTransientArenaTelemetry.recordWrapperMiss();
        return wrapper;
    }

    @Override
    public @NonNull GpuBufferSlice uploadStaging(
            final @NonNull List<ByteBuffer> data,
            final long alignment,
            @Usage final int usage,
            final long minimumAllocation,
            final long elementSize
    ) {
        return upload(data, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public @NonNull GpuBufferSlice uploadGpu(
            final @NonNull List<ByteBuffer> data,
            final long alignment,
            @Usage final int usage,
            final long minimumAllocation,
            final long elementSize
    ) {
        return upload(data, alignment, usage, minimumAllocation, elementSize);
    }

    private GpuBufferSlice upload(
            final List<ByteBuffer> data,
            final long alignment,
            @Usage final int usage,
            final long minimumAllocation,
            final long elementSize
    ) {
        long totalSize = 0L;
        for (ByteBuffer buffer : data) {
            totalSize = Math.addExact(totalSize, buffer.remaining());
            totalSize = Mth.roundToward(totalSize, alignment);
        }

        GpuBufferSlice result;
        try (MappedView mapped = allocateMapped(
                totalSize,
                alignment,
                usage,
                minimumAllocation,
                elementSize
        )) {
            long mappedPointer = MemoryUtil.memAddress(mapped.data());
            long offset = 0L;
            for (ByteBuffer buffer : data) {
                long copyLength = Math.min(mapped.slice().length() - offset, buffer.remaining());
                MemoryUtil.memCopy(MemoryUtil.memAddress(buffer), mappedPointer + offset, copyLength);
                offset = Mth.roundToward(Math.addExact(offset, buffer.remaining()), alignment);
                if (offset >= mapped.slice().length()) {
                    break;
                }
            }
            result = mapped.slice();
        }
        return result;
    }

    @Override
    public @NonNull List<GpuBufferSlice> multiUploadStaging(
            final @NonNull List<ByteBuffer> data,
            final long alignment,
            @Usage final int usage
    ) {
        return multiUpload(data, alignment, usage);
    }

    @Override
    public @NonNull List<GpuBufferSlice> multiUploadGpu(
            final @NonNull List<ByteBuffer> data,
            final long alignment,
            @Usage final int usage
    ) {
        return multiUpload(data, alignment, usage);
    }

    private List<GpuBufferSlice> multiUpload(
            final List<ByteBuffer> data,
            final long alignment,
            @Usage final int usage
    ) {
        ReferenceArrayList<GpuBufferSlice> uploaded = new ReferenceArrayList<>(data.size());
        uploaded.size(data.size());
        int remaining = data.size();
        int[] indices = ensureMultiUploadIndexCapacity(remaining);
        for (int index = 0; index < remaining; index++) {
            indices[index] = index;
        }
        sortIndicesByRemaining(data, indices, 0, remaining - 1);
        MetalTransientArenaTelemetry.recordMultiUpload(remaining);

        while (remaining > 0) {
            boolean allocatedAnything = false;

            // Prefer the largest item that still fits in the current block.
            // This preserves the previous packing policy without allocating an
            // IntStream, boxed comparator or temporary IntArrayList.
            for (int position = remaining - 1; position >= 0; position--) {
                int bufferIndex = indices[position];
                ByteBuffer currentBuffer = data.get(bufferIndex);
                if (this.gpuBlockAllocator.canAllocateInCurrentBlock(
                        currentBuffer.remaining(),
                        alignment
                )) {
                    removeIndex(indices, position, remaining);
                    remaining--;
                    uploadOne(currentBuffer, alignment, usage, uploaded, bufferIndex);
                    allocatedAnything = true;
                    break;
                }
            }

            if (!allocatedAnything) {
                int bufferIndex = indices[--remaining];
                uploadOne(data.get(bufferIndex), alignment, usage, uploaded, bufferIndex);
            }
        }

        return uploaded;
    }

    private void uploadOne(
            final ByteBuffer currentBuffer,
            final long alignment,
            @Usage final int usage,
            final ReferenceArrayList<GpuBufferSlice> uploaded,
            final int bufferIndex
    ) {
        try (MappedView view = allocateGpuMapped(currentBuffer.remaining(), alignment, usage)) {
            MemoryUtil.memCopy(currentBuffer, view.data());
            uploaded.set(bufferIndex, view.slice());
        }
    }

    private int[] ensureMultiUploadIndexCapacity(final int required) {
        if (this.multiUploadIndices.length >= required) {
            return this.multiUploadIndices;
        }
        int capacity = Math.max(8, this.multiUploadIndices.length);
        while (capacity < required) {
            capacity = Math.multiplyExact(capacity, 2);
        }
        this.multiUploadIndices = new int[capacity];
        return this.multiUploadIndices;
    }

    private static void removeIndex(final int[] indices, final int position, final int size) {
        int trailing = size - position - 1;
        if (trailing > 0) {
            System.arraycopy(indices, position + 1, indices, position, trailing);
        }
    }

    private static void sortIndicesByRemaining(
            final List<ByteBuffer> data,
            final int[] indices,
            final int low,
            final int high
    ) {
        int left = low;
        int right = high;
        if (left >= right) {
            return;
        }
        int pivot = data.get(indices[(left + right) >>> 1]).remaining();
        while (left <= right) {
            while (data.get(indices[left]).remaining() < pivot) {
                left++;
            }
            while (data.get(indices[right]).remaining() > pivot) {
                right--;
            }
            if (left <= right) {
                int temporary = indices[left];
                indices[left] = indices[right];
                indices[right] = temporary;
                left++;
                right--;
            }
        }
        if (low < right) {
            sortIndicesByRemaining(data, indices, low, right);
        }
        if (left < high) {
            sortIndicesByRemaining(data, indices, left, high);
        }
    }

    private static final class TransientGpuBuffer extends MetalGpuBuffer {
        private final MetalTransientMemory owner;
        private final long bufferSubmitIndex;

        TransientGpuBuffer(
                final MetalDevice device,
                final MemorySegment handle,
                @Usage final int usage,
                final long size,
                final MetalTransientMemory owner,
                final long submitIndex
        ) {
            super(device, usage, size, handle);
            this.owner = owner;
            this.bufferSubmitIndex = submitIndex;
        }

        @Override
        public boolean isClosed() {
            return this.owner.closed || this.bufferSubmitIndex < this.owner.submitIndex;
        }

        @Override
        public void close() {
            // The facade is shared by every slice of the same backing/usage in
            // this frame and does not own the underlying block. Closing one
            // slice must not invalidate its siblings; frame rotation or owner
            // shutdown is the only lifetime transition.
        }

        @Override
        public GpuBufferSlice.@NonNull MappedView map(
                final long offset,
                final long length,
                final boolean read,
                final boolean write
        ) {
            throw new IllegalStateException("Cannot map transient buffer");
        }

        @Override
        public @NonNull GpuBufferSlice slice(final long offset, final long length) {
            throw new IllegalStateException("Cannot slice transient buffer");
        }

        @Override
        public @NonNull GpuBufferSlice slice() {
            throw new IllegalStateException("Cannot slice transient buffer");
        }
    }
}
