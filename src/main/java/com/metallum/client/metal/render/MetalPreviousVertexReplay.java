package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix4f;

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
    record SourceDrawInputs(GpuBufferSlice projection, GpuBufferSlice dynamicTransforms,
                            @Nullable GpuTextureView depth) {}

    record HandTransforms(Matrix4f currentProjection, Matrix4f currentUnjitteredFromRaster,
                          Matrix4f previousFromPosition) {
        HandTransforms {
            currentProjection = new Matrix4f(currentProjection);
            currentUnjitteredFromRaster = new Matrix4f(currentUnjitteredFromRaster);
            previousFromPosition = new Matrix4f(previousFromPosition);
        }

        @Override
        public Matrix4f currentProjection() { return new Matrix4f(currentProjection); }

        @Override
        public Matrix4f currentUnjitteredFromRaster() { return new Matrix4f(currentUnjitteredFromRaster); }

        @Override
        public Matrix4f previousFromPosition() { return new Matrix4f(previousFromPosition); }
    }

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

    static @Nullable SourceDrawInputs sourceDrawInputs(final RenderPass pass) {
        if (pass instanceof MetalRenderPass metal) return metal.motionDrawInputs();
        if (pass instanceof RenderPearlBackendAccess access
                && access.metallum$getBackend() instanceof MetalRenderPass metal) {
            return metal.motionDrawInputs();
        }
        return null;
    }

    /** Capture even on reset/first appearance: the next accepted frame needs this draw's matrices. */
    static boolean captureHandTransform(final MetalPreviousVertexHistory.DrawToken token,
                                        final @Nullable SourceDrawInputs inputs) {
        Matrix4f projection = inputs == null ? null : sourceUniformMatrix(inputs.projection());
        Matrix4f modelView = inputs == null ? null : sourceUniformMatrix(inputs.dynamicTransforms());
        var transform = projection == null || modelView == null ? null
                : new MetalPreviousVertexHistory.DrawTransform(projection, modelView);
        MetalPreviousVertexHistory.stageDrawTransform(token, transform);
        return transform != null && MetalPreviousVertexHistory.currentDrawTransform(token) != null;
    }

    private static @Nullable Matrix4f sourceUniformMatrix(final @Nullable GpuBufferSlice slice) {
        if (slice == null || !(slice.buffer() instanceof MetalGpuBuffer buffer)) {
            return null;
        }
        // Renderer-owned uniform uploads are synchronous CPU writes: ProjectionMatrixBuffer uses
        // orphanWrite (UNIFORM|COPY_DST); DynamicGpuData uses a mapped ring (UNIFORM|MAP_WRITE).
        // Observe that live backing here on the source draw's render thread. Do not MAP_READ a
        // write-only API buffer, wait for the GPU, or retain its storage past the staged lifetime.
        return buffer.copySourceUniformMatrix(slice);
    }

    static @Nullable HandTransforms handTransforms(final MetalPreviousVertexHistory.DrawToken token) {
        var current = MetalPreviousVertexHistory.currentDrawTransform(token);
        var previous = MetalPreviousVertexHistory.matchedPreviousDrawTransform(token);
        if (current == null || previous == null) return null;
        // HUD rendering is unjittered. World camera motion/jitter must never enter either matrix.
        Matrix4f previousFromPosition = MetalMotionContract.minecraftToMetalFxClip(
                previous.projection().mul(previous.modelView()));
        if (!MetalFxMath.isFinite(previousFromPosition)) return null;
        return new HandTransforms(current.projection(),
                MetalMotionContract.minecraftToMetalFxClip(new Matrix4f()), previousFromPosition);
    }

    static @Nullable Plan plan(
            final RenderPipeline source,
            final StagedVertexBuffer.ExecuteInfo executeInfo,
            final MetalPreviousVertexHistory.DrawToken token
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
