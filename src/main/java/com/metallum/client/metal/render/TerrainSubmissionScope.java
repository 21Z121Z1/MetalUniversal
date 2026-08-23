package com.metallum.client.metal.render;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.caffeinemc.mods.sodium.mixin.core.RenderPassAccessor;
import org.lwjgl.vulkan.VkDrawIndexedIndirectCommand;

import java.util.List;

/**
 * A lexical Sodium-terrain producer/consumer transaction.
 *
 * <p>The thread-local is scoped to the exact {@code DefaultChunkRenderer}
 * batch call.  There is no global scene cache and no generic indirect-draw
 * interception: an indirect pass can consume a snapshot only while this
 * producer-owned scope is active.</p>
 */
public final class TerrainSubmissionScope implements AutoCloseable {
    private static final ThreadLocal<TerrainSubmissionScope> CURRENT = new ThreadLocal<>();

    private final TerrainSubmissionScope previous;
    private TerrainSceneSnapshot snapshot;
    private boolean closed;

    private TerrainSubmissionScope(final TerrainSubmissionScope previous) {
        this.previous = previous;
    }

    public static TerrainSubmissionScope begin() {
        TerrainSubmissionScope scope = new TerrainSubmissionScope(CURRENT.get());
        CURRENT.set(scope);
        return scope;
    }

    /** Called only by the Sodium VK indirect producer mixin. */
    public static void capture(
            final RenderPass pass,
            final Object producerIdentity,
            final long commandAddress,
            final int drawCount,
            final GpuBufferSlice commandSlice
    ) {
        if (!TerrainSceneSnapshot.captureEnabled()) {
            return;
        }
        TerrainSubmissionScope scope = CURRENT.get();
        MetalRenderPass metalPass = metalPass(pass);
        if (scope == null || scope.snapshot != null || metalPass == null) {
            return;
        }
        List<IrisMetalIndirectCommandStream.IndexedDraw> commands =
                IrisMetalIndirectCommandStream.copyIndexedCommands(commandAddress, drawCount);
        if (commands.isEmpty()) {
            return;
        }
        try {
            scope.snapshot = TerrainSceneSnapshot.capture(
                    metalPass, producerIdentity, commandSlice, commands
            );
        } catch (RuntimeException ignored) {
            // A missing/retired binding is a normal fail-closed condition.  Do
            // not suppress Sodium's original indirect submission.
            scope.snapshot = null;
        }
    }

    /**
     * Consumes a snapshot exactly once.  False means the caller must execute
     * its existing legacy/native submission, also exactly once.
     */
    public static boolean consume(
            final RenderPass pass,
            final GpuBufferSlice commandSlice,
            final int drawCount
    ) {
        if (!TerrainSceneSnapshot.captureEnabled()) {
            return false;
        }
        MetalRenderPass metalPass = metalPass(pass);
        if (metalPass == null) {
            return false;
        }
        return consume(metalPass, commandSlice, drawCount);
    }

    static boolean consume(
            final MetalRenderPass metalPass,
            final GpuBufferSlice commandSlice,
            final int drawCount
    ) {
        if (!TerrainSceneSnapshot.captureEnabled()) {
            return false;
        }
        TerrainSubmissionScope scope = CURRENT.get();
        if (scope == null) {
            return false;
        }
        try {
            return consumeSnapshot(
                    metalPass.terrainSnapshotState(),
                    TerrainSceneSnapshot.ResourceSlice.ofGpuSlice(
                            commandSlice, VkDrawIndexedIndirectCommand.SIZEOF
                    ),
                    drawCount
            ) != null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean consume(
            final TerrainSceneSnapshot.StateView state,
            final TerrainSceneSnapshot.ResourceSlice commandBuffer,
            final int drawCount
    ) {
        if (!TerrainSceneSnapshot.captureEnabled()) {
            return false;
        }
        return consumeSnapshot(state, commandBuffer, drawCount) != null;
    }

    static TerrainSceneSnapshot consumeSnapshot(
            final TerrainSceneSnapshot.StateView state,
            final TerrainSceneSnapshot.ResourceSlice commandBuffer,
            final int drawCount
    ) {
        if (!TerrainSceneSnapshot.captureEnabled()) {
            return null;
        }
        TerrainSubmissionScope scope = CURRENT.get();
        if (scope == null) {
            return null;
        }
        TerrainSceneSnapshot candidate = scope.snapshot;
        scope.snapshot = null;
        try {
            return candidate != null && candidate.matches(state, commandBuffer, drawCount)
                    ? candidate
                    : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static MetalRenderPass metalPass(final RenderPass pass) {
        if (!(pass instanceof RenderPassAccessor accessor)) {
            return null;
        }
        return accessor.getBackend() instanceof MetalRenderPass metalPass ? metalPass : null;
    }

    /** Package-private host fixture hook; production uses {@link #capture}. */
    static void publish(final TerrainSceneSnapshot snapshot) {
        TerrainSubmissionScope scope = CURRENT.get();
        if (scope != null && scope.snapshot == null) {
            scope.snapshot = snapshot;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        snapshot = null;
        if (CURRENT.get() != this) {
            throw new IllegalStateException("Terrain submission scopes must close in nesting order");
        }
        CURRENT.set(previous);
    }
}
