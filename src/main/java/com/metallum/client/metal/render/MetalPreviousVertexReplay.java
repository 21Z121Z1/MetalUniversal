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
import com.mojang.renderpearl.util.TextureViewAndSampler;
import java.util.Map;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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

    /** Immutable bindings of the draw that actually executed, including OIT's selected pipeline. */
    record SourceState(RenderPipeline pipeline, Map<String, TextureViewAndSampler> textures,
                       RenderPass.@Nullable RenderArea scissor) {
        SourceState { textures = Map.copyOf(textures); }
    }

    record DrawTransforms(Matrix4f currentProjection, Matrix4f currentUnjitteredFromRaster,
                          Matrix4f previousFromPosition) {
        DrawTransforms {
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

    /** Never send NaN sentinels through the rasterizer: varyings can canonicalize nonfinite values. */
    static boolean writePreviousPositions(final float[] positions, final ByteBuffer destination) {
        if (positions.length == 0 || positions.length%3 != 0 || destination.remaining() < (long)positions.length/3*16) {
            throw new IllegalArgumentException("Malformed previous position/validity upload");
        }
        destination.order(ByteOrder.nativeOrder());
        boolean complete = true;
        for (int i = 0; i < positions.length; i += 3) {
            boolean valid = Float.isFinite(positions[i]) && Float.isFinite(positions[i+1]) && Float.isFinite(positions[i+2]);
            destination.putFloat(valid ? positions[i] : 0).putFloat(valid ? positions[i+1] : 0)
                    .putFloat(valid ? positions[i+2] : 0).putFloat(valid ? 1 : 0);
            complete &= valid;
        }
        return complete;
    }

    static @Nullable SourceDrawInputs sourceDrawInputs(final RenderPass pass) {
        if (pass instanceof MetalRenderPass metal) return metal.motionDrawInputs();
        if (pass instanceof RenderPearlBackendAccess access
                && access.metallum$getBackend() instanceof MetalRenderPass metal) {
            return metal.motionDrawInputs();
        }
        return null;
    }

    static @Nullable SourceState sourceState(final RenderPass pass) {
        if (pass instanceof MetalRenderPass metal) return metal.motionSourceState();
        if (pass instanceof RenderPearlBackendAccess access
                && access.metallum$getBackend() instanceof MetalRenderPass metal) return metal.motionSourceState();
        return null;
    }

    /** Capture even on reset/first appearance: the next accepted frame needs this draw's matrices. */
    static boolean captureHandTransform(final MetalPreviousVertexHistory.DrawToken token,
                                        final @Nullable SourceDrawInputs inputs) {
        return captureSourceTransform(token, inputs, new Matrix4f());
    }

    static boolean captureSourceTransform(final MetalPreviousVertexHistory.DrawToken token,
            final @Nullable SourceDrawInputs inputs, final Matrix4f unjitteredFromRaster) {
        Matrix4f projection = inputs == null ? null : sourceUniformMatrix(inputs.projection());
        Matrix4f modelView = inputs == null ? null : sourceUniformMatrix(inputs.dynamicTransforms());
        var transform = projection == null || modelView == null ? null
                : new MetalPreviousVertexHistory.DrawTransform(projection, modelView,
                        new Matrix4f(unjitteredFromRaster).mul(projection));
        MetalPreviousVertexHistory.stageDrawTransform(token, transform);
        return transform != null && MetalPreviousVertexHistory.currentDrawTransform(token) != null;
    }

    static MetalPreviousVertexHistory.@Nullable SurfaceIdentity surfaceIdentity(final @Nullable SourceState state) {
        if (state == null) return null;
        Map<String, MetalPreviousVertexHistory.TextureIdentity> textures = new java.util.TreeMap<>();
        // OIT's per-frame coefficient/depth targets describe compositing, not material identity.
        // Track the sampled resources that define visibility of the audited reduced families.
        for (String name : java.util.List.of("Sampler0", "DissolveMaskSampler", "GlintSampler")) {
            var pair = state.textures().get(name);
            if (pair == null) continue;
            var view = pair.view(); var sampler = pair.sampler();
            if (!(view.texture() instanceof MetalGpuTexture texture) || texture.isClosed() || view.isClosed()
                    || sampler.isClosed()) return null;
            textures.put(name, new MetalPreviousVertexHistory.TextureIdentity(texture.allocationIdentity(),
                    view.baseMipLevel(), view.mipLevels(), sampler.getAddressModeU(), sampler.getAddressModeV(),
                    sampler.getMinFilter(), sampler.getMagFilter(), sampler.getMaxAnisotropy(), sampler.getMaxLod()));
        }
        return new MetalPreviousVertexHistory.SurfaceIdentity(state.pipeline(), textures);
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

    static @Nullable DrawTransforms handTransforms(final MetalPreviousVertexHistory.DrawToken token) {
        var current = MetalPreviousVertexHistory.currentDrawTransform(token);
        var previous = MetalPreviousVertexHistory.matchedPreviousDrawTransform(token);
        if (current == null || previous == null) return null;
        // HUD rendering is unjittered. World camera motion/jitter must never enter either matrix.
        Matrix4f previousFromPosition = MetalMotionContract.minecraftToMetalFxClip(
                previous.unjitteredProjection().mul(previous.modelView()));
        if (!MetalFxMath.isFinite(previousFromPosition)) return null;
        return new DrawTransforms(current.projection(),
                MetalMotionContract.minecraftToMetalFxClip(new Matrix4f()), previousFromPosition);
    }

    static @Nullable DrawTransforms worldTransforms(final MetalPreviousVertexHistory.DrawToken token) {
        var current = MetalPreviousVertexHistory.currentDrawTransform(token);
        var previous = MetalPreviousVertexHistory.matchedPreviousDrawTransform(token);
        if (current == null || previous == null) return null;
        Matrix4f correction = MetalMotionContract.minecraftToMetalFxClip(
                current.unjitteredProjection().mul(current.projection().invert()));
        Matrix4f previousFromPosition = MetalMotionContract.minecraftToMetalFxClip(
                previous.unjitteredProjection().mul(previous.modelView()));
        if (!MetalFxMath.isFinite(correction) || !MetalFxMath.isFinite(previousFromPosition)) return null;
        return new DrawTransforms(current.projection(), correction, previousFromPosition);
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
