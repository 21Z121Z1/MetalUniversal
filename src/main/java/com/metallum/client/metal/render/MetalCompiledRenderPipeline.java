package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.*;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Environment(EnvType.CLIENT)
final class MetalCompiledRenderPipeline implements BackendRenderPipeline, AutoCloseable {
    enum ResourceKind {
        UNIFORM_BUFFER,
        SAMPLED_IMAGE,
        TEXEL_BUFFER,
        /** 顶点属性元数据，仅用于记录格式，不占用 Metal 的 buffer slot。 */
        VERTEX_ATTRIBUTE
    }

    static final int STAGE_VERTEX = 1;
    static final int STAGE_FRAGMENT = 2;
    static final int STAGE_ALL = STAGE_VERTEX | STAGE_FRAGMENT;

    /** push constant 块在绑定表里使用的固定名字，与 {@code MetalCrossShaderCompiler} 保持一致。 */
    static final String PUSH_CONSTANT_NAME = "push_constants";

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
    private final MemorySegment withDepthPipeline;
    private final MemorySegment withoutDepthPipeline;

    MetalCompiledRenderPipeline(
            final MetalDevice device,
            final BackendRenderPipeline.CreateInfo info,
            final String vertexMsl,
            final String fragmentMsl,
            final String vertexEntryPoint,
            final String fragmentEntryPoint,
            final List<ResourceBinding> resources
    ) {
        this.resources = resources;
        this.resourcesByName = resources.stream()
                .filter(binding -> binding.bindingIndex() >= 0)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(ResourceBinding::name, binding -> binding));

        int maxBindingIndex = -1;
        long resourceMask = 0L;
        for (ResourceBinding binding : resources) {
            if (binding.bindingIndex() < 0) {
                continue;
            }
            maxBindingIndex = Math.max(maxBindingIndex, binding.bindingIndex());
            resourceMask |= 1L << binding.bindingIndex();
        }
        if (maxBindingIndex >= Long.SIZE) {
            throw new IllegalStateException("Pipeline " + info.name() + " has binding index " + maxBindingIndex + ", limit is " + (Long.SIZE - 1));
        }
        this.allResourceMask = resourceMask;

        this.firstAvailableVertexBufferSlot = firstAvailableVertexBufferSlot(resources);
        this.cullMode = info.cull() ? MTLCullMode.Back : MTLCullMode.None;
        this.fillMode = info.polygonMode() == PolygonMode.WIREFRAME ? MTLTriangleFillMode.Lines : MTLTriangleFillMode.Fill;
        this.topology = MTLPrimitiveType.from(info.primitiveTopology());
        this.vertexBufferCount = info.vertexBuffers().size();

        MTLCompareFunction depthCompareOp;
        int depthWrite;
        DepthStencilState depthStencilState = info.depthStencilState();
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

        ColorTargetState colorTarget = info.colorTargetStates().isEmpty() ? null : info.colorTargetStates().get(0);
        MTLPixelFormat colorFormat = colorTarget != null ? MTLPixelFormat.from(colorTarget.format()) : MTLPixelFormat.RGBA8Unorm;

        MemorySegment vertexFunction = device.getOrCompileFunction(vertexMsl, vertexEntryPoint);
        MemorySegment fragmentFunction = device.getOrCompileFunction(fragmentMsl, fragmentEntryPoint);

        try (MTLVertexDescriptor vertexDescriptor = buildVertexDescriptor(info, this.firstAvailableVertexBufferSlot)) {
            this.withoutDepthPipeline = createPipeline(device, colorTarget, vertexFunction, fragmentFunction, vertexDescriptor, colorFormat, MTLPixelFormat.Invalid);
            this.withDepthPipeline = createPipeline(device, colorTarget, vertexFunction, fragmentFunction, vertexDescriptor, colorFormat, MTLPixelFormat.Depth32Float);
        }
    }

    private static MemorySegment createPipeline(
            final MetalDevice device,
            final ColorTargetState colorTarget,
            final MemorySegment vertexFunction,
            final MemorySegment fragmentFunction,
            final MTLVertexDescriptor vertexDescriptor,
            final MTLPixelFormat colorFormat,
            final MTLPixelFormat depthFormat
    ) {
        if (MetalNativeBridge.isNullHandle(vertexFunction) || MetalNativeBridge.isNullHandle(fragmentFunction)) {
            return MemorySegment.NULL;
        }

        Optional<BlendFunction> blendFunction = colorTarget == null ? Optional.empty() : colorTarget.blendFunction();
        long writeMask = colorTarget == null ? MTLColorWriteMask.All.value : MTLColorWriteMask.from(colorTarget.writeMask());

        try (MTLRenderPipelineDescriptor pipelineDesc = new MTLRenderPipelineDescriptor()) {
            pipelineDesc.setCompiledFunctions(vertexFunction, fragmentFunction);
            pipelineDesc.setVertexDescriptor(vertexDescriptor);
            pipelineDesc.setAttachmentFormats(colorFormat, depthFormat, MTLPixelFormat.Invalid);

            if (blendFunction.isPresent()) {
                var function = blendFunction.get();
                pipelineDesc.setBlendState(
                        MTLBlendFactor.from(function.color().sourceFactor()),
                        MTLBlendFactor.from(function.color().destFactor()),
                        MTLBlendOperation.from(function.color().op()),
                        MTLBlendFactor.from(function.alpha().sourceFactor()),
                        MTLBlendFactor.from(function.alpha().destFactor()),
                        MTLBlendOperation.from(function.alpha().op()),
                        writeMask
                );
            } else {
                pipelineDesc.disableBlending(writeMask);
            }

            return MetalNativeBridge.metallum_MTLDevice_makeRenderPipelineState(
                    device.metalDeviceHandle(),
                    pipelineDesc.handle()
            );
        }
    }

    @Override
    public boolean isClosed() {
        return MetalNativeBridge.isNullHandle(this.withoutDepthPipeline);
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

    MemorySegment getNativePipeline(final boolean useDepth) {
        return useDepth && !MetalNativeBridge.isNullHandle(this.withDepthPipeline) ? this.withDepthPipeline : this.withoutDepthPipeline;
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

    /**
     * 按 26.3 的 {@link BackendRenderPipeline.CreateInfo} 构建 Metal 顶点描述符。
     *
     * <p>{@code CreateInfo} 已把顶点布局摊平成「buffer slot + stride + stepRate」的
     * {@code VertexBuffer} 列表与「bufferSlot/location/offset/format」的
     * {@code AttribBinding} 列表，因此这里直接映射，无需再遍历 {@code VertexFormat}。
     */
    private static MTLVertexDescriptor buildVertexDescriptor(
            final BackendRenderPipeline.CreateInfo pipeline,
            final int firstMetalVertexBufferSlot
    ) {
        MTLVertexDescriptor vertexDesc = new MTLVertexDescriptor();
        if (pipeline.vertexBuffers().isEmpty()) {
            return vertexDesc;
        }

        for (BackendRenderPipeline.CreateInfo.VertexBuffer buffer : pipeline.vertexBuffers()) {
            int metalSlot = firstMetalVertexBufferSlot + buffer.bufferSlot();
            MTLVertexStepFunction stepFunction =
                    buffer.stepRate() > 0 ? MTLVertexStepFunction.PerInstance : MTLVertexStepFunction.PerVertex;
            vertexDesc.setLayout(
                    metalSlot,
                    buffer.stride(),
                    stepFunction,
                    buffer.stepRate() > 0 ? buffer.stepRate() : 1
            );
        }

        for (BackendRenderPipeline.CreateInfo.AttribBinding binding : pipeline.attribBindings()) {
            MTLVertexFormat format = MTLVertexFormat.from(binding.format());
            if (format == MTLVertexFormat.Invalid) {
                throw new IllegalStateException("Unsupported vertex attribute format: " + binding.format());
            }
            int metalSlot = firstMetalVertexBufferSlot + binding.bufferSlot();
            vertexDesc.setAttribute(binding.location(), format.value, binding.offset(), metalSlot);
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
        if (!MetalNativeBridge.isNullHandle(this.withDepthPipeline)) {
            MetalNativeBridge.metallum_release_object(this.withDepthPipeline);
        }
        if (!MetalNativeBridge.isNullHandle(this.withoutDepthPipeline)) {
            MetalNativeBridge.metallum_release_object(this.withoutDepthPipeline);
        }
    }
}
