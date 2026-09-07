package com.metallum.client.metal.render;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.jspecify.annotations.Nullable;

/**
 * Resolves a previous-position replay only after the whole source frame has finished staging.
 *
 * <p>{@link MetalPreviousVertexHistory#matchedPreviousPositions} requires the complete owning
 * object's draw manifest to match. Calling it while individual {@code ExecuteInfo}s are still
 * arriving can transiently mistake a prefix for the complete object, so exact replay planning is
 * deliberately deferred until {@code MetalFxManager} flushes all queued motion draws.</p>
 */
@Environment(EnvType.CLIENT)
final class MetalPreviousVertexReplay {
    record Plan(GpuBufferSlice currentVertexBuffer, int replayBaseVertex, float[] previousPositions) {
        Plan {
            previousPositions = previousPositions.clone();
        }

        @Override
        public float[] previousPositions() {
            return previousPositions.clone();
        }
    }

    private MetalPreviousVertexReplay() {
    }

    static @Nullable Plan plan(
            final RenderPipeline source,
            final StagedVertexBuffer.ExecuteInfo executeInfo,
            final @Nullable MetalPreviousVertexHistory.DrawToken token
    ) {
        if (!MetalEntityMotionPipeline.supportsPreviousPositions(source) || executeInfo == null) {
            return null;
        }
        float[] previousPositions = MetalPreviousVertexHistory.matchedPreviousPositions(token);
        if (previousPositions == null || previousPositions.length < 3 || previousPositions.length % 3 != 0) {
            return null;
        }

        int sourceStride = source.getVertexFormatBinding(0).getVertexSize();
        long vertexCount = previousPositions.length / 3L;
        long byteOffset;
        long byteLength;
        try {
            byteOffset = Math.multiplyExact((long) executeInfo.baseVertex(), sourceStride);
            byteLength = Math.multiplyExact(vertexCount, sourceStride);
        } catch (ArithmeticException overflow) {
            return null;
        }
        long sourceSize = executeInfo.vertexBuffer().size();
        if (sourceStride <= 0 || byteOffset < 0L || byteLength <= 0L
                || byteOffset > sourceSize || byteLength > sourceSize - byteOffset) {
            return null;
        }
        return new Plan(
                executeInfo.vertexBuffer().slice(byteOffset, byteLength),
                0,
                previousPositions
        );
    }
}
