package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.*;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Environment(EnvType.CLIENT)
final class MetalCompiledRenderPipeline implements CompiledRenderPipeline, AutoCloseable {
    private static final Identifier SODIUM_TERRAIN_VERTEX_SHADER =
            Identifier.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque");

    enum ResourceKind {
        UNIFORM_BUFFER,
        SAMPLED_IMAGE,
        TEXEL_BUFFER
    }

    static final int STAGE_VERTEX = 1;
    static final int STAGE_FRAGMENT = 2;
    static final int STAGE_ALL = STAGE_VERTEX | STAGE_FRAGMENT;

    record ResourceBinding(ResourceKind kind, String name, int bindingIndex, int stageMask,
                           @Nullable GpuFormat texelBufferFormat) {
    }

    private final List<ResourceBinding> resources;
    private final Map<String, ResourceBinding> resourcesByName;
    private final long allResourceMask;
    private final int firstAvailableVertexBufferSlot;
    private final MTLCullMode cullMode;
    private final MTLTriangleFillMode fillMode;
    private final float depthBiasScaleFactor;
    private final float depthBiasConstant;
    private final MTLPrimitiveType topology;
    private final int vertexBufferCount;

    private final MemorySegment depthStencilState;
    private final boolean hasDepthStencilState;
    private final MTLPixelFormat[] colorFormats;
    private final Map<PipelineSignature, MemorySegment> pipelineStates;
    private final MemorySegment withoutDepthPipeline;
    // Lazy-variant support (S9C, active only with metallum.opt.asyncPrecompile):
    // the constructor builds the two signatures the game actually starts with
    // and the rest are built on the prewarm thread or on first demand, all
    // under MetalDevice.COMPILE_CHAIN_LOCK.
    private final boolean lazyVariants;
    private final MetalDevice device;
    private final RenderPipeline info;
    private final MemorySegment vertexFunction;
    private final MemorySegment fragmentFunction;
    /** Guarded by MetalDevice.COMPILE_CHAIN_LOCK (close runs inside clearPipelineCache). */
    private boolean closed;

    private record PipelineSignature(List<MTLPixelFormat> colorFormats, MTLPixelFormat depthFormat,
                                     MTLPixelFormat stencilFormat, int sampleCount) {
    }

    MetalCompiledRenderPipeline(
            final MetalDevice device,
            final RenderPipeline info,
            final String vertexMsl,
            final String fragmentMsl,
            final String vertexEntryPoint,
            final String fragmentEntryPoint,
            final List<ResourceBinding> resources
    ) {
        this.resources = resources;
        this.resourcesByName = resources.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(ResourceBinding::name, binding -> binding));

        int maxBindingIndex = -1;
        long resourceMask = 0L;
        for (ResourceBinding binding : resources) {
            maxBindingIndex = Math.max(maxBindingIndex, binding.bindingIndex());
            resourceMask |= 1L << binding.bindingIndex();
        }
        if (maxBindingIndex >= Long.SIZE) {
            throw new IllegalStateException("Pipeline " + info.getLocation() + " has binding index " + maxBindingIndex + ", limit is " + (Long.SIZE - 1));
        }
        this.allResourceMask = resourceMask;

        this.firstAvailableVertexBufferSlot = firstAvailableVertexBufferSlot(resources);
        this.cullMode = info.isCull() ? MTLCullMode.Back : MTLCullMode.None;
        this.fillMode = info.getPolygonMode() == PolygonMode.WIREFRAME ? MTLTriangleFillMode.Lines : MTLTriangleFillMode.Fill;
        this.topology = MTLPrimitiveType.from(info.getPrimitiveTopology());
        this.vertexBufferCount = info.getVertexFormatBindings().length;
        if (device.metal4MainRendererEnabled()) {
            for (ResourceBinding binding : resources) {
                int limit = switch (binding.kind()) {
                    case UNIFORM_BUFFER -> 31;
                    case SAMPLED_IMAGE -> 16;
                    case TEXEL_BUFFER -> 128;
                };
                if (binding.bindingIndex() >= limit) {
                    throw new IllegalStateException(
                            "Metal 4 pipeline " + info.getLocation() + " has " + binding.kind()
                                    + " binding index " + binding.bindingIndex() + ", limit is " + (limit - 1)
                    );
                }
            }
            if (this.firstAvailableVertexBufferSlot + this.vertexBufferCount > 31) {
                throw new IllegalStateException(
                        "Metal 4 pipeline " + info.getLocation() + " needs vertex buffer slot "
                                + (this.firstAvailableVertexBufferSlot + this.vertexBufferCount - 1)
                                + ", limit is 30"
                );
            }
        }

        MTLCompareFunction depthCompareOp;
        int depthWrite;
        var depthStencilState = info.getDepthStencilState();
        this.hasDepthStencilState = depthStencilState != null;
        if (depthStencilState == null) {
            depthCompareOp = MTLCompareFunction.Always;
            depthWrite = 0;
            this.depthBiasScaleFactor = 0.0f;
            this.depthBiasConstant = 0.0f;
        } else {
            depthCompareOp = MTLCompareFunction.from(depthStencilState.depthTest());
            depthWrite = depthStencilState.writeDepth() ? 1 : 0;
            this.depthBiasScaleFactor = depthStencilState.depthBiasScaleFactor();
            this.depthBiasConstant = depthStencilState.depthBiasConstant();
        }

        this.depthStencilState = MetalNativeBridge.MTLDevice_makeDepthStencilState(
                device.metalDeviceHandle(),
                depthCompareOp,
                depthWrite
        );

        ColorTargetState[] colorTargets = info.getColorTargetStates();
        if (colorTargets.length == 0 || colorTargets.length > ColorTargetState.MAX_COLOR_TARGETS) {
            throw new IllegalArgumentException(
                    "Pipeline " + info.getLocation() + " has " + colorTargets.length
                            + " color targets; supported range is 1.." + ColorTargetState.MAX_COLOR_TARGETS
            );
        }
        this.colorFormats = new MTLPixelFormat[colorTargets.length];
        for (int index = 0; index < colorTargets.length; index++) {
            ColorTargetState target = colorTargets[index];
            this.colorFormats[index] = target == null ? MTLPixelFormat.Invalid : MTLPixelFormat.from(target.format());
        }

        this.device = device;
        this.info = info;
        this.lazyVariants = device.asyncPrewarmEnabled();
        this.vertexFunction = device.getOrCompileFunction(vertexMsl, vertexEntryPoint);
        this.fragmentFunction = device.getOrCompileFunction(fragmentMsl, fragmentEntryPoint);

        List<DepthStencilFormats> eagerFormats = this.lazyVariants ? eagerDepthStencilFormats() : supportedDepthStencilFormats();
        Map<PipelineSignature, MemorySegment> states = new java.util.concurrent.ConcurrentHashMap<>();
        try (MTLVertexDescriptor vertexDescriptor = buildVertexDescriptor(info, this.firstAvailableVertexBufferSlot)) {
            for (DepthStencilFormats formats : eagerFormats) {
                MemorySegment pipeline = createPipeline(
                        device,
                        info,
                        this.vertexFunction,
                        this.fragmentFunction,
                        vertexDescriptor,
                        this.colorFormats,
                        formats.depthFormat(),
                        formats.stencilFormat()
                );
                if (!MetalNativeBridge.isNullHandle(pipeline)) {
                    states.put(this.signatureFor(formats.depthFormat(), formats.stencilFormat()), pipeline);
                }
            }
        }
        this.pipelineStates = states;
        this.withoutDepthPipeline = states.get(this.signatureFor(MTLPixelFormat.Invalid, MTLPixelFormat.Invalid));
        if (this.lazyVariants) {
            for (DepthStencilFormats formats : supportedDepthStencilFormats()) {
                if (!eagerFormats.contains(formats)) {
                    device.submitPrewarmTask(() -> {
                        try {
                            this.buildVariantLocked(formats.depthFormat(), formats.stencilFormat());
                        } catch (Throwable t) {
                            com.metallum.Metallum.LOGGER.warn(
                                    "[metallum] background pipeline variant build failed for {}", info.getLocation(), t
                            );
                        }
                    });
                }
            }
        }
    }

    private PipelineSignature signatureFor(final MTLPixelFormat depthFormat, final MTLPixelFormat stencilFormat) {
        return new PipelineSignature(List.copyOf(Arrays.asList(this.colorFormats)), depthFormat, stencilFormat, 1);
    }

    /**
     * Builds one depth/stencil variant under the compile-chain lock and
     * publishes it. Returns the variant, or {@code null} when it could not
     * be built or this pipeline was already closed.
     */
    @Nullable
    private MemorySegment buildVariantLocked(final MTLPixelFormat depthFormat, final MTLPixelFormat stencilFormat) {
        synchronized (MetalDevice.COMPILE_CHAIN_LOCK) {
            if (this.closed) {
                return null;
            }
            PipelineSignature signature = this.signatureFor(depthFormat, stencilFormat);
            MemorySegment existing = this.pipelineStates.get(signature);
            if (existing != null) {
                return existing;
            }
            MemorySegment pipeline;
            try (MTLVertexDescriptor vertexDescriptor = buildVertexDescriptor(this.info, this.firstAvailableVertexBufferSlot)) {
                pipeline = createPipeline(
                        this.device,
                        this.info,
                        this.vertexFunction,
                        this.fragmentFunction,
                        vertexDescriptor,
                        this.colorFormats,
                        depthFormat,
                        stencilFormat
                );
            }
            if (MetalNativeBridge.isNullHandle(pipeline)) {
                return null;
            }
            this.pipelineStates.put(signature, pipeline);
            return pipeline;
        }
    }

    private record DepthStencilFormats(MTLPixelFormat depthFormat, MTLPixelFormat stencilFormat) {
    }

    private static List<DepthStencilFormats> supportedDepthStencilFormats() {
        return List.of(
                new DepthStencilFormats(MTLPixelFormat.Invalid, MTLPixelFormat.Invalid),
                new DepthStencilFormats(MTLPixelFormat.Depth16Unorm, MTLPixelFormat.Invalid),
                new DepthStencilFormats(MTLPixelFormat.Depth32Float, MTLPixelFormat.Invalid),
                new DepthStencilFormats(MTLPixelFormat.Depth24Unorm_Stencil8, MTLPixelFormat.Depth24Unorm_Stencil8),
                new DepthStencilFormats(MTLPixelFormat.Depth32Float_Stencil8, MTLPixelFormat.Depth32Float_Stencil8),
                new DepthStencilFormats(MTLPixelFormat.Invalid, MTLPixelFormat.Stencil8)
        );
    }

    /**
     * The two signatures every session starts with: depthless (UI, isValid)
     * and the Depth32Float main framebuffer. Everything else is built lazily
     * in lazy-variant mode.
     */
    private static List<DepthStencilFormats> eagerDepthStencilFormats() {
        return List.of(
                new DepthStencilFormats(MTLPixelFormat.Invalid, MTLPixelFormat.Invalid),
                new DepthStencilFormats(MTLPixelFormat.Depth32Float, MTLPixelFormat.Invalid)
        );
    }

    private static MemorySegment createPipeline(
            final MetalDevice device,
            final RenderPipeline info,
            final MemorySegment vertexFunction,
            final MemorySegment fragmentFunction,
            final MTLVertexDescriptor vertexDescriptor,
            final MTLPixelFormat[] colorFormats,
            final MTLPixelFormat depthFormat,
            final MTLPixelFormat stencilFormat
    ) {
        if (MetalNativeBridge.isNullHandle(vertexFunction) || MetalNativeBridge.isNullHandle(fragmentFunction)) {
            return MemorySegment.NULL;
        }

        try (MTLRenderPipelineDescriptor pipelineDesc = new MTLRenderPipelineDescriptor()) {
            pipelineDesc.setCompiledFunctions(vertexFunction, fragmentFunction);
            pipelineDesc.setVertexDescriptor(vertexDescriptor);
            ColorTargetState[] colorTargets = info.getColorTargetStates();
            for (int index = 0; index < colorFormats.length; index++) {
                ColorTargetState colorTarget = colorTargets[index];
                pipelineDesc.setColorAttachmentFormat(index, colorFormats[index]);
                if (colorTarget == null) {
                    pipelineDesc.disableBlending(index, MTLColorWriteMask.None.value);
                    continue;
                }

                Optional<BlendFunction> blendFunction = colorTarget.blendFunction();
                long writeMask = MTLColorWriteMask.from(colorTarget.writeMask());
                if (blendFunction.isPresent()) {
                    var function = blendFunction.get();
                    pipelineDesc.setColorAttachmentBlendState(
                            index,
                            true,
                            MTLBlendFactor.from(function.color().sourceFactor()),
                            MTLBlendFactor.from(function.color().destFactor()),
                            MTLBlendOperation.from(function.color().op()),
                            MTLBlendFactor.from(function.alpha().sourceFactor()),
                            MTLBlendFactor.from(function.alpha().destFactor()),
                            MTLBlendOperation.from(function.alpha().op()),
                            writeMask
                    );
                } else {
                    pipelineDesc.disableBlending(index, writeMask);
                }
            }

            pipelineDesc.setDepthStencilFormats(depthFormat, stencilFormat);

            return MetalNativeBridge.metallum_MTLDevice_makeRenderPipelineState(
                    device.metalDeviceHandle(),
                    pipelineDesc.handle()
            );
        }
    }

    @Override
    public boolean isValid() {
        return !MetalNativeBridge.isNullHandle(this.withoutDepthPipeline);
    }

    List<ResourceBinding> resources() {
        return this.resources;
    }

    long allResourceMask() {
        return this.allResourceMask;
    }

    @Nullable
    ResourceBinding resource(final String name) {
        return this.resourcesByName.get(name);
    }

    boolean usesStableTerrainSampler(final ResourceBinding binding) {
        return binding.kind() == ResourceKind.SAMPLED_IMAGE
                && isSodiumTerrainBlockSampler(binding.name(), this.info.getVertexShader());
    }

    static boolean isSodiumTerrainBlockSampler(final String bindingName, final Identifier vertexShader) {
        return "u_BlockTex".equals(bindingName) && SODIUM_TERRAIN_VERTEX_SHADER.equals(vertexShader);
    }

    int firstAvailableVertexBufferSlot() {
        return this.firstAvailableVertexBufferSlot;
    }

    float depthBiasScaleFactor() {
        return this.depthBiasScaleFactor;
    }

    float depthBiasConstant() {
        return this.depthBiasConstant;
    }

    MemorySegment getDepthStencilState() {
        return this.depthStencilState;
    }

    MemorySegment getNativePipeline(final MTLPixelFormat depthFormat, final MTLPixelFormat stencilFormat) {
        MemorySegment pipeline = this.pipelineStates.get(this.signatureFor(depthFormat, stencilFormat));
        if (pipeline == null && this.lazyVariants) {
            // First demand beat the prewarm thread to this variant; build it
            // now (bounded by one PSO compile, may wait out the prewarm
            // thread's current item).
            pipeline = this.buildVariantLocked(depthFormat, stencilFormat);
        }
        if (pipeline == null || MetalNativeBridge.isNullHandle(pipeline)) {
            throw new IllegalStateException("No cached Metal pipeline for attachment signature "
                    + this.signatureFor(depthFormat, stencilFormat));
        }
        return pipeline;
    }

    boolean hasDepthStencilState() {
        return this.hasDepthStencilState;
    }

    MTLPixelFormat[] colorAttachmentFormats() {
        return this.colorFormats.clone();
    }

    MTLCullMode cullMode() {
        return this.cullMode;
    }

    MTLTriangleFillMode fillMode() {
        return this.fillMode;
    }

    MTLPrimitiveType topology() {
        return this.topology;
    }

    int vertexBufferCount() {
        return this.vertexBufferCount;
    }

    private static MTLVertexDescriptor buildVertexDescriptor(
            final RenderPipeline pipeline,
            final int firstMetalVertexBufferSlot
    ) {
        VertexFormat[] bindings = pipeline.getVertexFormatBindings();
        MTLVertexDescriptor vertexDesc = new MTLVertexDescriptor();
        long attrIndex = 0;

        for (int i = 0; i < bindings.length; i++) {
            VertexFormat binding = bindings[i];
            if (binding == null || binding.getElements().isEmpty()) {
                continue;
            }

            int metalSlot = firstMetalVertexBufferSlot + i;

            long stride = binding.getVertexSize();
            long stepRate = binding.getStepRate();
            MTLVertexStepFunction stepFunction = stepRate > 0 ? MTLVertexStepFunction.PerInstance : MTLVertexStepFunction.PerVertex;
            vertexDesc.setLayout(metalSlot, stride, stepFunction, stepRate > 0 ? stepRate : 1);

            for (VertexFormatElement element : binding.getElements()) {
                MTLVertexFormat format = MTLVertexFormat.from(element.format());
                if (format == MTLVertexFormat.Invalid) {
                    throw new IllegalStateException("Unsupported vertex attribute format: " + element.format());
                }
                vertexDesc.setAttribute(attrIndex, format.value, element.offset(), metalSlot);
                attrIndex++;
            }
        }

        return vertexDesc;
    }

    private static int firstAvailableVertexBufferSlot(final List<ResourceBinding> resources) {
        int maxVertexBufferBinding = -1;
        for (ResourceBinding resource : resources) {
            if (resource.kind() == ResourceKind.UNIFORM_BUFFER && (resource.stageMask() & STAGE_VERTEX) != 0) {
                maxVertexBufferBinding = Math.max(maxVertexBufferBinding, resource.bindingIndex());
            }
        }
        return maxVertexBufferBinding + 1;
    }

    @Override
    public void close() {
        // Runs under MetalDevice.COMPILE_CHAIN_LOCK (clearPipelineCache);
        // pending lazy-variant tasks observe the flag and abandon.
        this.closed = true;
        Set<MemorySegment> uniqueStates = new HashSet<>(this.pipelineStates.values());
        for (MemorySegment state : uniqueStates) {
            if (!MetalNativeBridge.isNullHandle(state)) {
                MetalNativeBridge.metallum_release_object(state);
            }
        }
    }
}
