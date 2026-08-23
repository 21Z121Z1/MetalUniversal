package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLIndexType;
import com.metallum.client.metal.render.mtl.MTLPrimitiveType;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * One lazy native ICB owned by one Sodium indirect batch.
 *
 * <p>The immutable snapshot is the Java-side content key.  A new native ICB
 * is encoded only when that key changes; an unchanged batch executes its
 * already encoded command buffer.  Replaced/closed handles go through the
 * renderer's existing completion-safe destruction queue.</p>
 */
public final class TerrainIcbOwner implements AutoCloseable {
    private MemorySegment indirectCommandBuffer = MemorySegment.NULL;
    private TerrainSceneSnapshot.IcbContent content;
    private MetalDevice device;
    private MTLPrimitiveType primitiveType;
    private boolean closed;

    boolean execute(
            final MetalDevice currentDevice,
            final MTLRenderCommandEncoder encoder,
            final MTLPrimitiveType currentPrimitiveType,
            final MTLIndexType indexType,
            final MemorySegment indexBuffer,
            final MemorySegment pipeline,
            final TerrainSceneSnapshot snapshot,
            final int drawCount
    ) {
        if (closed || currentDevice == null || encoder == null || snapshot == null
                || drawCount <= 0 || drawCount != snapshot.draws().size()
                || indexBuffer == null || pipeline == null
                || MetalNativeBridge.isNullHandle(indexBuffer)
                || MetalNativeBridge.isNullHandle(pipeline)) {
            return false;
        }
        if (device != null && device != currentDevice) {
            retire();
            content = null;
        }
        device = currentDevice;

        if (!snapshot.sameIcbContent(content) || primitiveType != currentPrimitiveType) {
            retire();
            content = null;
            primitiveType = currentPrimitiveType;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment packed = snapshot.packIndexedCommands(arena);
                indirectCommandBuffer = MetalNativeBridge.MTLDevice_createTerrainIndexedIcb(
                        currentDevice.metalDeviceHandle(),
                        currentPrimitiveType.value,
                        indexType.value,
                        indexBuffer,
                        pipeline,
                        packed,
                        drawCount
                );
            } catch (RuntimeException exception) {
                indirectCommandBuffer = MemorySegment.NULL;
                return false;
            }
            if (MetalNativeBridge.isNullHandle(indirectCommandBuffer)) {
                return false;
            }
            content = snapshot.icbContent();
        }

        if (MetalNativeBridge.isNullHandle(indirectCommandBuffer)) {
            return false;
        }
        if (encoder.executeTerrainIcb(indirectCommandBuffer, drawCount)) {
            return true;
        }

        // A failed execute must not poison the one-entry owner.  The caller
        // immediately emits its existing indirect fallback exactly once.
        retire();
        content = null;
        primitiveType = null;
        return false;
    }

    private void retire() {
        if (MetalNativeBridge.isNullHandle(indirectCommandBuffer)) {
            return;
        }
        MemorySegment retired = indirectCommandBuffer;
        indirectCommandBuffer = MemorySegment.NULL;
        if (device != null) {
            device.queueResourceRelease(retired);
        } else {
            MetalNativeBridge.metallum_release_object(retired);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        retire();
        content = null;
        primitiveType = null;
    }
}
