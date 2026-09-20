package com.metallum.mixin.terrain;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.terrain.VanillaTerrainUploadPressure;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Measures existing staging retries without changing partial-allocation or buffer ownership. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
abstract class RenderSectionUploadPressureMixin {
    @WrapMethod(method = "addSectionBuffersToUberBuffer")
    private boolean metallum$measureStagingAttempt(
            final ChunkSectionLayer layer, final CompiledSectionMesh mesh,
            final ByteBuffer vertices, final ByteBuffer indices, final Operation<Boolean> original
    ) {
        // Capture before vanilla may advance a buffer cursor. A retry deliberately counts again.
        long requestedBytes = (vertices == null ? 0L : vertices.remaining())
                + (indices == null ? 0L : indices.remaining());
        long start = System.nanoTime();
        boolean completed = false;
        boolean success = false;
        try {
            success = original.call(layer, mesh, vertices, indices);
            completed = true;
            return success;
        } finally {
            VanillaTerrainUploadPressure.counters().recordStagingAttempt(
                    requestedBytes, System.nanoTime() - start, success, completed);
        }
    }

    @WrapOperation(method = "addSectionBuffersToUberBuffer", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/locks/ReentrantLock;lock()V"))
    private void metallum$measureCopyLock(final ReentrantLock lock, final Operation<Void> original) {
        long start = System.nanoTime();
        original.call(lock);
        VanillaTerrainUploadPressure.counters().recordCopyLockWait(System.nanoTime() - start);
    }
}
