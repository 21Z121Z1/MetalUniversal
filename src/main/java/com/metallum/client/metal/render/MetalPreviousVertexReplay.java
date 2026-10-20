package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.IndexType;
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
    record SourceGeometry(@Nullable GpuBufferSlice vertices,GpuBuffer indices,IndexType indexType) {}
    record SourceDrawInputs(GpuBufferSlice projection, GpuBufferSlice dynamicTransforms,
                            @Nullable GpuTextureView depth, @Nullable GpuBufferSlice globals,@Nullable GpuBufferSlice fog) {
        SourceDrawInputs(GpuBufferSlice projection,GpuBufferSlice dynamicTransforms,@Nullable GpuTextureView depth,
                         @Nullable GpuBufferSlice globals) {
            this(projection,dynamicTransforms,depth,globals,null);
        }
        SourceDrawInputs(GpuBufferSlice projection,GpuBufferSlice dynamicTransforms,@Nullable GpuTextureView depth) {
            this(projection,dynamicTransforms,depth,null);
        }
    }

    /** Actual hardware raster state, after any producer depth-convention conversion. */
    record RasterState(int viewportWidth, int viewportHeight, float slopeBias, float constantBias) {
        static final RasterState UNBIASED = new RasterState(0, 0, 0, 0);

        boolean biased() { return slopeBias != 0 || constantBias != 0; }

        boolean reconstructible() {
            // D32's constant-bias unit depends on the primitive's maximum depth exponent.
            // A fragment exponent approximation has <= 2^-23 absolute unit error in [0,1].
            // Bound its contribution below the existing 3e-6 correspondence error budget.
            return !biased() || (viewportWidth > 0 && viewportHeight > 0
                    && Float.isFinite(slopeBias) && Float.isFinite(constantBias)
                    && Math.abs(constantBias) <= 16);
        }

        void write(ByteBuffer target) {
            target.order(ByteOrder.nativeOrder()).putFloat(viewportWidth).putFloat(viewportHeight)
                    .putFloat(slopeBias).putFloat(constantBias);
        }
    }

    /** Immutable bindings of the draw that actually executed, including OIT's selected pipeline. */
    record SourceState(RenderPipeline pipeline, Map<String, TextureViewAndSampler> textures,
                       RenderPass.@Nullable RenderArea scissor, boolean depthAttachmentPresent,
                       RasterState raster) {
        SourceState(RenderPipeline pipeline,Map<String,TextureViewAndSampler> textures,
                    RenderPass.@Nullable RenderArea scissor,boolean depthAttachmentPresent) {
            this(pipeline,textures,scissor,depthAttachmentPresent,RasterState.UNBIASED);
        }
        SourceState(RenderPipeline pipeline,Map<String,TextureViewAndSampler> textures,RenderPass.@Nullable RenderArea scissor) {
            this(pipeline,textures,scissor,true);
        }
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

    record Plan(GpuBufferSlice currentVertexBuffer,int replayBaseVertex,float[] previousPositions,float @Nullable [] previousCoverage) {
        Plan(GpuBufferSlice currentVertexBuffer,int replayBaseVertex,float[] previousPositions) {
            this(currentVertexBuffer,replayBaseVertex,previousPositions,null);
        }
        Plan {
            previousPositions = previousPositions.clone();
            if(previousCoverage!=null && previousCoverage.length!=previousPositions.length/3*2)
                throw new IllegalArgumentException("Previous coverage does not match the vertex stream");
            previousCoverage=previousCoverage==null ? null : previousCoverage.clone();
        }

        @Override
        public float[] previousPositions() {
            return previousPositions.clone();
        }
        @Override public float @Nullable [] previousCoverage() {return previousCoverage==null ? null : previousCoverage.clone();}
    }

    private MetalPreviousVertexReplay() {
    }

    static GpuBufferSlice freezeSourceVertices(final MetalCommandEncoder encoder,final byte[] vertices) {
        if(vertices.length==0)throw new IllegalArgumentException("Empty source vertex snapshot");
        try(GpuBufferSlice.MappedView mapped=encoder.transientMemory().allocateGpuMapped(vertices.length,16L,GpuBuffer.USAGE_VERTEX)) {
            mapped.data().put(vertices);MetalFxMotionTelemetry.recordGpuUpload(vertices.length);return mapped.slice();
        }
    }

    /** Never send NaN sentinels through the rasterizer: varyings can canonicalize nonfinite values. */
    static boolean writePreviousPositions(final float[] positions, final ByteBuffer destination) {
        return writePreviousPositions(positions,null,destination);
    }

    static boolean writePreviousPositions(final float[] positions,final float @Nullable [] coverage,final ByteBuffer destination) {
        int stride=coverage==null ? 16 : 24;
        if (positions.length == 0 || positions.length%3 != 0 || destination.remaining() < (long)positions.length/3*stride
                || (coverage!=null && coverage.length!=positions.length/3*2)) {
            throw new IllegalArgumentException("Malformed previous position/validity upload");
        }
        destination.order(ByteOrder.nativeOrder());
        boolean complete = true;
        for (int i = 0; i < positions.length; i += 3) {
            boolean valid = Float.isFinite(positions[i]) && Float.isFinite(positions[i+1]) && Float.isFinite(positions[i+2]);
            int uv=i/3*2;
            valid &= coverage==null || (Float.isFinite(coverage[uv])&&Float.isFinite(coverage[uv+1]));
            destination.putFloat(valid ? positions[i] : 0).putFloat(valid ? positions[i+1] : 0)
                    .putFloat(valid ? positions[i+2] : 0).putFloat(valid ? 1 : 0);
            if(coverage!=null)destination.putFloat(valid ? coverage[uv] : 0).putFloat(valid ? coverage[uv+1] : 0);
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

    static @Nullable SourceGeometry sourceGeometry(final RenderPass pass) {
        if(pass instanceof MetalRenderPass metal)return metal.motionSourceGeometry();
        if(pass instanceof RenderPearlBackendAccess access
                && access.metallum$getBackend() instanceof MetalRenderPass metal)return metal.motionSourceGeometry();
        return null;
    }

    static void flushAfterSourcePass(final RenderPass pass) {
        if(pass instanceof MetalRenderPass metal)metal.flushMotionReplayAfterClose();
        else if(pass instanceof RenderPearlBackendAccess access
                && access.metallum$getBackend() instanceof MetalRenderPass metal)metal.flushMotionReplayAfterClose();
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
        MetalSourceUniforms uniforms = inputs == null ? null : MetalSourceUniforms.capture(inputs.dynamicTransforms(),inputs.globals(),inputs.fog());
        Matrix4f modelView = uniforms == null ? null : uniforms.modelView();
        var transform = projection == null || modelView == null ? null
                : new MetalPreviousVertexHistory.DrawTransform(projection, modelView,
                        new Matrix4f(unjitteredFromRaster).mul(projection));
        MetalPreviousVertexHistory.stageDrawTransform(token, transform);
        MetalPreviousVertexHistory.stageSourceUniforms(token,transform == null ? null : uniforms);
        return transform != null && MetalPreviousVertexHistory.currentDrawTransform(token) != null;
    }

    static MetalPreviousVertexHistory.@Nullable SurfaceIdentity surfaceIdentity(final @Nullable SourceState state) {
        if (state == null) return null;
        Map<String, MetalPreviousVertexHistory.TextureIdentity> textures = new java.util.TreeMap<>();
        // OIT's per-frame coefficient/depth targets describe compositing, not material identity.
        // Track the sampled resources that define visibility of the audited reduced families.
        var vertex=state.pipeline().getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX);
        boolean particle=vertex!=null && vertex.getPath().equals("core/particle");
        for (String name : java.util.List.of("Sampler0","DissolveMaskSampler","GlintSampler","Sampler2")) {
            if(name.equals("Sampler2") && !particle)continue;
            var pair = state.textures().get(name);
            if (pair == null) continue;
            var view = pair.view(); var sampler = pair.sampler();
            if (!(view.texture() instanceof MetalGpuTexture texture) || texture.isClosed() || view.isClosed()
                    || sampler.isClosed()) return null;
            textures.put(name, new MetalPreviousVertexHistory.TextureIdentity(texture.allocationIdentity(),
                    view.baseMipLevel(), view.mipLevels(), sampler.getAddressModeU(), sampler.getAddressModeV(),
                    sampler.getMinFilter(), sampler.getMagFilter(), sampler.getMaxAnisotropy(), sampler.getMaxLod()));
        }
        return new MetalPreviousVertexHistory.SurfaceIdentity(state.pipeline(), textures,state.depthAttachmentPresent(),state.raster());
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

    /** Replays only current visibility. A zero previous clip W explicitly rejects every history sample. */
    static @Nullable DrawTransforms currentOnlyTransforms(final MetalPreviousVertexHistory.DrawToken token,
                                                         final boolean firstPerson) {
        var current = MetalPreviousVertexHistory.currentDrawTransform(token);
        if (current == null || MetalPreviousVertexHistory.currentVertexCount(token) <= 0) return null;
        Matrix4f correction = firstPerson ? new Matrix4f()
                : current.unjitteredProjection().mul(current.projection().invert());
        MetalMotionContract.minecraftToMetalFxClip(correction);
        if (!MetalFxMath.isFinite(correction)) return null;
        return new DrawTransforms(current.projection(), correction, new Matrix4f().zero());
    }

    static @Nullable Plan currentOnlyPlan(final RenderPipeline source,
            final StagedVertexBuffer.ExecuteInfo executeInfo, final MetalPreviousVertexHistory.DrawToken token) {
        if (!MetalEntityMotionPipeline.supportsPreviousPositions(source)) return null;
        int count = MetalPreviousVertexHistory.currentVertexCount(token);
        if (count <= 0 || count > Integer.MAX_VALUE/3) return null;
        float[] invalidPositions = new float[count*3];
        java.util.Arrays.fill(invalidPositions, Float.NaN); // CPU sentinel; upload writes finite XYZ and validity=0.
        return planPositions(source, executeInfo, invalidPositions);
    }

    static @Nullable Plan plan(
            final RenderPipeline source,
            final StagedVertexBuffer.ExecuteInfo executeInfo,
            final MetalPreviousVertexHistory.DrawToken token
    ) {
        if (!MetalEntityMotionPipeline.supportsPreviousPositions(source) || executeInfo == null) {
            return null;
        }
        float[] previousPositions = previousPositionsForSource(source,token);
        if (previousPositions == null || previousPositions.length < 3 || previousPositions.length % 3 != 0) {
            return null;
        }

        return planPositions(source, executeInfo, previousPositions);
    }

    static float @Nullable [] previousPositionsForSource(final RenderPipeline source,
            final MetalPreviousVertexHistory.DrawToken token) {
        float[] positions=MetalPreviousVertexHistory.matchedPreviousPositions(token);
        if(positions==null)return null;
        if(source.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX).getPath().equals("core/block")) {
            var previous=MetalPreviousVertexHistory.matchedPreviousSourceUniforms(token);
            if(previous==null)return null;
            var offset=previous.modelOffset();
            for(int i=0;i<positions.length;i+=3) {
                positions[i]+=offset.x;positions[i+1]+=offset.y;positions[i+2]+=offset.z;
                if(!Float.isFinite(positions[i])||!Float.isFinite(positions[i+1])||!Float.isFinite(positions[i+2]))return null;
            }
        }
        return positions;
    }

    private static @Nullable Plan planPositions(final RenderPipeline source,
            final StagedVertexBuffer.ExecuteInfo executeInfo, final float[] previousPositions) {
        if (executeInfo == null) return null;

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
