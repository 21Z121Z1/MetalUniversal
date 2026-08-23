package com.metallum.client.metal.render;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.metallum.client.metal.render.mtl.MTLIndexType;
import org.lwjgl.vulkan.VkDrawIndexedIndirectCommand;

import java.lang.foreign.Arena;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable authority for one Sodium terrain indirect submission.
 *
 * <p>This is deliberately a draw snapshot, not a second allocator or a
 * render graph.  It retains the renderer-owned buffer objects and their
 * allocation generations, while copying only the compact indexed command
 * records that Sodium already produced.  It also retains the exact uploaded
 * indirect command slice consumed by the Metal pass. The consumer uses the
 * snapshot to authorize the existing one-call indirect ABI.</p>
 */
public final class TerrainSceneSnapshot {
    /** New, scoped opt-in.  It is intentionally independent of Iris ICB. */
    public static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("metallum.opt.terrainSceneSnapshot", "false")
    );

    /**
     * Feature-gated Metal 4 ICB submission. Enabling it also enables the
     * producer-owned snapshot boundary; the diagnostic snapshot switch above
     * remains independently useful when ICB execution is not requested.
     */
    public static final boolean ICB_ENABLED = Boolean.parseBoolean(
            System.getProperty("metallum.opt.terrainIcb", "false")
    );

    public static boolean captureEnabled() {
        return ENABLED || ICB_ENABLED;
    }

    static final int MAX_VERTEX_BUFFERS = RenderPass.MAX_VERTEX_BUFFERS;

    /** A renderer-owned slice, including the identity that makes it live. */
    static final class ResourceSlice {
        private final Object resource;
        private final MetalAllocationIdentity allocation;
        private final long offset;
        private final long length;
        private final int stride;
        private final boolean closed;

        private ResourceSlice(
                final Object resource,
                final MetalAllocationIdentity allocation,
                final long offset,
                final long length,
                final int stride,
                final boolean closed
        ) {
            this.resource = resource;
            this.allocation = allocation;
            this.offset = offset;
            this.length = length;
            this.stride = stride;
            this.closed = closed;
        }

        static ResourceSlice empty() {
            return new ResourceSlice(null, null, 0L, 0L, 0, false);
        }

        static ResourceSlice of(
                final Object resource,
                final MetalAllocationIdentity allocation,
                final long offset,
                final long length,
                final int stride,
                final boolean closed
        ) {
            if (resource == null || allocation == null || offset < 0L || length < 0L || stride < 0) {
                return new ResourceSlice(resource, allocation, offset, length, stride, true);
            }
            return new ResourceSlice(resource, allocation, offset, length, stride, closed);
        }

        static ResourceSlice ofGpuSlice(final GpuBufferSlice slice, final int stride) {
            Objects.requireNonNull(slice, "slice");
            if (!(slice.buffer() instanceof MetalGpuBuffer buffer)) {
                return of(slice.buffer(), null, slice.offset(), slice.length(), stride, true);
            }
            try {
                return of(
                        buffer,
                        buffer.allocationIdentity(),
                        slice.offset(),
                        slice.length(),
                        stride,
                        buffer.isClosed()
                );
            } catch (RuntimeException exception) {
                return of(buffer, null, slice.offset(), slice.length(), stride, true);
            }
        }

        boolean present() {
            return resource != null || allocation != null;
        }

        boolean live() {
            if (!present() || closed || allocation == null) {
                return false;
            }
            if (resource instanceof MetalGpuBuffer buffer) {
                if (buffer.isClosed()) {
                    return false;
                }
                try {
                    return allocation.equals(buffer.allocationIdentity());
                } catch (RuntimeException exception) {
                    return false;
                }
            }
            // Host fixtures use opaque handles with an authoritative identity;
            // their lifetime is represented by the closed bit.
            return true;
        }

        boolean sameBinding(final ResourceSlice other) {
            if (other == null) {
                return false;
            }
            if (!present() && !other.present()) {
                return true;
            }
            return resource == other.resource
                    && Objects.equals(allocation, other.allocation)
                    && offset == other.offset
                    && length == other.length
                    && stride == other.stride
                    && !closed
                    && !other.closed;
        }
    }

    /**
     * State at the producer/consumer boundary; all vertex slots are fixed.
     * The binding generation is a compact group stamp, not a material resource
     * map. Full argument-resource stamps remain future ICB work.
     */
    static final class StateView {
        private final Object pipelineIdentity;
        private final long pipelineGeneration;
        private final long bindingGeneration;
        private final long sourceGeneration;
        private final ResourceSlice indexBuffer;
        private final MTLIndexType indexType;
        private final List<ResourceSlice> vertexBuffers;

        StateView(
                final Object pipelineIdentity,
                final long pipelineGeneration,
                final long bindingGeneration,
                final long sourceGeneration,
                final ResourceSlice indexBuffer,
                final MTLIndexType indexType,
                final List<ResourceSlice> vertexBuffers
        ) {
            this.pipelineIdentity = Objects.requireNonNull(pipelineIdentity, "pipelineIdentity");
            if (pipelineGeneration < 1L || bindingGeneration < 1L || sourceGeneration < 1L) {
                throw new IllegalArgumentException("Terrain state generations must be positive");
            }
            this.pipelineGeneration = pipelineGeneration;
            this.bindingGeneration = bindingGeneration;
            this.sourceGeneration = sourceGeneration;
            this.indexBuffer = Objects.requireNonNull(indexBuffer, "indexBuffer");
            this.indexType = Objects.requireNonNull(indexType, "indexType");
            if (vertexBuffers.size() != MAX_VERTEX_BUFFERS) {
                throw new IllegalArgumentException("Terrain state must contain every vertex slot");
            }
            this.vertexBuffers = List.copyOf(vertexBuffers);
        }

        /**
         * A monotonic source stamp: no process-wide call counter is involved.
         * Allocation ids are renderer-owned monotonic identities; pipeline and
         * binding generations advance only when their state changes. Material
         * resource maps are intentionally outside this bounded slice.
         */
        long sceneGeneration() {
            long value = Math.max(sourceGeneration, Math.max(pipelineGeneration, bindingGeneration));
            value = Math.max(value, allocationGeneration(indexBuffer));
            for (ResourceSlice vertexBuffer : vertexBuffers) {
                value = Math.max(value, allocationGeneration(vertexBuffer));
            }
            return Math.max(1L, value);
        }

        boolean sameState(final StateView other) {
            if (other == null
                    || pipelineIdentity != other.pipelineIdentity
                    || pipelineGeneration != other.pipelineGeneration
                    || bindingGeneration != other.bindingGeneration
                    || indexType != other.indexType
                    || !indexBuffer.sameBinding(other.indexBuffer)) {
                return false;
            }
            for (int slot = 0; slot < vertexBuffers.size(); slot++) {
                if (!vertexBuffers.get(slot).sameBinding(other.vertexBuffers.get(slot))) {
                    return false;
                }
            }
            return true;
        }

        boolean allLive() {
            if (pipelineIdentity instanceof MetalCompiledRenderPipeline pipeline && !pipeline.isValid()) {
                return false;
            }
            if (!indexBuffer.live()) {
                return false;
            }
            for (ResourceSlice vertexBuffer : vertexBuffers) {
                if (vertexBuffer.present() && !vertexBuffer.live()) {
                    return false;
                }
            }
            return true;
        }

        private static long allocationGeneration(final ResourceSlice resource) {
            if (resource.allocation == null) {
                return 0L;
            }
            return Math.max(resource.allocation.allocationId(), resource.allocation.generation());
        }
    }

    record Draw(int ordinal, IrisMetalIndirectCommandStream.IndexedDraw arguments) {
        Draw {
            if (ordinal < 0) {
                throw new IllegalArgumentException("Terrain draw ordinal must be non-negative");
            }
            Objects.requireNonNull(arguments, "arguments");
        }
    }

    private final long sceneGeneration;
    private final StateView state;
    private final ResourceSlice commandBuffer;
    private final List<Draw> draws;

    private TerrainSceneSnapshot(
            final StateView state,
            final ResourceSlice commandBuffer,
            final List<Draw> draws
    ) {
        this.state = Objects.requireNonNull(state, "state");
        this.commandBuffer = Objects.requireNonNull(commandBuffer, "commandBuffer");
        this.draws = List.copyOf(draws);
        if (this.draws.isEmpty()) {
            throw new IllegalArgumentException("Terrain scene snapshot must contain draws");
        }
        this.sceneGeneration = state.sceneGeneration();
    }

    static TerrainSceneSnapshot capture(
            final MetalRenderPass pass,
            final GpuBufferSlice commandSlice,
            final List<IrisMetalIndirectCommandStream.IndexedDraw> commands
    ) {
        return capture(pass.terrainSnapshotState(), ResourceSlice.ofGpuSlice(
                commandSlice, VkDrawIndexedIndirectCommand.SIZEOF
        ), commands);
    }

    static TerrainSceneSnapshot capture(
            final StateView state,
            final ResourceSlice commandBuffer,
            final List<IrisMetalIndirectCommandStream.IndexedDraw> commands
    ) {
        Objects.requireNonNull(commands, "commands");
        List<Draw> draws = new ArrayList<>(commands.size());
        for (int ordinal = 0; ordinal < commands.size(); ordinal++) {
            draws.add(new Draw(ordinal, commands.get(ordinal)));
        }
        return new TerrainSceneSnapshot(state, commandBuffer, draws);
    }

    long sceneGeneration() {
        return sceneGeneration;
    }

    List<Draw> draws() {
        return draws;
    }

    /** Packs the immutable command records once for the native ICB encoder. */
    java.lang.foreign.MemorySegment packIndexedCommands(final Arena arena) {
        Objects.requireNonNull(arena, "arena");
        java.lang.foreign.MemorySegment packed = arena.allocate(
                (long) draws.size() * Integer.BYTES * 5L,
                Integer.BYTES
        );
        java.nio.IntBuffer values = packed.asByteBuffer()
                .order(ByteOrder.nativeOrder())
                .asIntBuffer();
        for (Draw draw : draws) {
            IrisMetalIndirectCommandStream.IndexedDraw command = draw.arguments();
            values.put(command.indexCount());
            values.put(command.instanceCount());
            values.put(command.firstIndex());
            values.put(command.baseVertex());
            values.put(command.firstInstance());
        }
        return packed;
    }

    boolean matches(
            final StateView current,
            final ResourceSlice currentCommandBuffer,
            final int drawCount
    ) {
        return drawCount == draws.size()
                && state.sameState(current)
                && commandBuffer.sameBinding(currentCommandBuffer)
                && state.allLive()
                && current.allLive()
                && commandBuffer.live()
                && currentCommandBuffer.live()
                && sceneGeneration == current.sceneGeneration();
    }
}
