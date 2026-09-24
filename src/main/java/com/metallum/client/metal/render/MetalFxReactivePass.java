package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Optional;

/** Source-owned auxiliary MRT declaration and the receipt from actual encoded CUTOUT draws. */
public final class MetalFxReactivePass {
    private MetalFxReactivePass() {}

    /** One world source, never a scaler cache key or a global feature-capability bit. */
    static final class Source {
        private final FrameSynthesisContract.FrameStamp stamp;
        private final GpuTexture sceneColor;
        private final GpuTexture worldDepth;
        private final GpuTextureView coverage;
        private final int width, height;
        private boolean acceptingPasses = true;
        private boolean invalidated;
        private long encodedDrawBatches;
        private boolean incompleteCoverage;

        Source(FrameSynthesisContract.FrameStamp stamp, GpuTexture sceneColor,
               GpuTexture worldDepth, GpuTextureView coverage) {
            if (stamp == null || sceneColor == null || worldDepth == null || coverage == null) {
                throw new IllegalArgumentException("Reactive source identities are required");
            }
            this.stamp = stamp;
            this.sceneColor = sceneColor;
            this.worldDepth = worldDepth;
            this.coverage = coverage;
            width = sceneColor.getWidth(0);
            height = sceneColor.getHeight(0);
            if (!resourcesValid()) throw new IllegalArgumentException("Incompatible reactive source textures");
        }

        private boolean resourcesValid() {
            return !invalidated && width > 0 && height > 0
                    && !sceneColor.isClosed() && !worldDepth.isClosed() && !coverage.isClosed()
                    && !coverage.texture().isClosed()
                    && sceneColor.getFormat() == GpuFormat.RGBA8_UNORM
                    && worldDepth.getFormat() == GpuFormat.D32_FLOAT
                    && worldDepth.getWidth(0) == width && worldDepth.getHeight(0) == height
                    && coverage.texture().getFormat() == GpuFormat.R8_UNORM
                    && coverage.getWidth(0) == width && coverage.getHeight(0) == height
                    && coverage.texture().getDepthOrLayers() == 1
                    && (coverage.texture().usage() & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0
                    && (coverage.texture().usage() & GpuTexture.USAGE_TEXTURE_BINDING) != 0;
        }

        RenderPassDescriptor decorate(RenderPassDescriptor descriptor) {
            if (!acceptingPasses || !resourcesValid() || descriptor.colorAttachments().size() != 1
                    || !ownsWorldTargets(descriptor)) return descriptor;
            var colors = new ArrayList<>(descriptor.colorAttachments());
            // prepareMotionInputs clears this exact allocation before world
            // draws. Later passes LOAD previous coverage and the backend STOREs
            // it for the same-source Temporal consumer. Never clear per draw.
            colors.add(new RenderPassDescriptor.Attachment<>(coverage, Optional.empty()));
            return new RenderPassDescriptor(descriptor.label(), colors,
                    descriptor.depthAttachment(), descriptor.renderArea());
        }

        @Nullable Pass claim(RenderPassDescriptor descriptor) {
            if (!acceptingPasses || !resourcesValid() || descriptor.colorAttachments().size() != 2
                    || !ownsWorldTargets(descriptor)
                    || descriptor.colorAttachments().get(1) == null
                    || descriptor.colorAttachments().get(1).textureView() != coverage
                    || descriptor.colorAttachments().get(1).clearValue().isPresent()) return null;
            return new Pass(this);
        }

        private boolean ownsWorldTargets(RenderPassDescriptor descriptor) {
            return !descriptor.colorAttachments().isEmpty()
                    && descriptor.colorAttachments().getFirst() != null
                    && descriptor.colorAttachments().getFirst().textureView().texture() == sceneColor
                    && descriptor.depthAttachment() != null
                    && descriptor.depthAttachment().textureView().texture() == worldDepth
                    && descriptor.colorAttachments().getFirst().textureView().getWidth(0) == width
                    && descriptor.colorAttachments().getFirst().textureView().getHeight(0) == height;
        }

        boolean acceptsNewPasses() { return acceptingPasses && resourcesValid(); }
        void endWorld() { acceptingPasses = false; }
        void invalidate() { invalidated = true; acceptingPasses = false; }

        boolean hasReceipt(FrameSynthesisContract.FrameStamp consumer, GpuTexture texture) {
            return resourcesValid() && stamp.equals(consumer) && texture == coverage.texture()
                    && encodedDrawBatches > 0 && !incompleteCoverage;
        }

        boolean requiresConservativeFallback(FrameSynthesisContract.FrameStamp consumer) {
            return resourcesValid() && stamp.equals(consumer) && incompleteCoverage;
        }

        long encodedDrawBatches() { return encodedDrawBatches; }
    }

    /** A draw receipt is impossible to mint by fetching a texture or binding a pipeline. */
    static final class Pass {
        private final Source source;
        private boolean closed;
        private boolean writesCoverage;
        private boolean unsupportedCoverage;

        Pass(Source source) { this.source = source; }
        void bind(MetalCompiledRenderPipeline pipeline) {
            writesCoverage = pipeline.writesCutoutCoverage();
            unsupportedCoverage = pipeline.unsupportedCutoutCoverage();
        }
        void didEncode(long batches) {
            if (!closed && batches > 0 && source.resourcesValid()) {
                if (unsupportedCoverage) source.incompleteCoverage = true;
                if (writesCoverage) source.encodedDrawBatches = Math.addExact(source.encodedDrawBatches, batches);
            }
        }
        void close() { closed = true; }
    }

    /** Called before RenderPearl validates the declared pipeline/attachment signature. */
    public static CompiledRenderPipeline adaptPipeline(RenderPassBackend backend, CompiledRenderPipeline pipeline) {
        if (!(backend instanceof MetalRenderPass metal) || !metal.hasReactivePass()) return pipeline;
        if (!(pipeline instanceof FrontendRenderPipeline frontend)
                || !(frontend.backendRenderPipeline() instanceof MetalCompiledRenderPipeline original)) {
            throw new IllegalArgumentException("Reactive world pass requires a Metal-backed RenderPearl pipeline");
        }
        if (original.isReactiveTargetVariant()) return pipeline;
        MetalCompiledRenderPipeline variant = original.withCutoutReactiveTarget();
        return new FrontendRenderPipeline(frontend.name(), variant, frontend.vertexFormats(),
                frontend.uniformIndices(), frontend.uniforms(), variant.colorTargetStates(),
                frontend.wantsDepthTexture(), frontend.pushConstantSize());
    }

    /** Fast paths must not bypass the draw-owned receipt; normal paths keep the same draw authority. */
    public static boolean needsDrawReceipt(Object pass) {
        return pass instanceof MetalRenderPass metal && metal.hasReactivePass();
    }
}
