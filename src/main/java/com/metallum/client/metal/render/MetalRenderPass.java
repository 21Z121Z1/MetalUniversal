package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.*;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkDrawIndexedIndirectCommand;
import org.lwjgl.vulkan.VkDrawIndirectCommand;

import java.lang.foreign.MemorySegment;
import java.nio.IntBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
final class MetalRenderPass implements RenderPassBackend {
    static final boolean VALIDATION = SharedConstants.IS_RUNNING_IN_IDE;
    static final int MAX_VERTEX_BUFFERS = RenderPass.MAX_VERTEX_BUFFERS;
    private final MetalDevice device;
    private final MetalCommandEncoder commandEncoder;
    @Nullable
    private final String label;
    private final GpuTextureView colorTexture;
    @Nullable
    private final GpuTextureView depthTexture;
    private final RenderPass.RenderArea renderArea;
    @Nullable
    private Vector4fc clearColor;
    private boolean clearDepthEnabled;
    private final double clearDepthValue;
    private final ScissorState scissorState = new ScissorState();
    private final GpuBufferSlice[] vertexBuffers = new GpuBufferSlice[MAX_VERTEX_BUFFERS];
    private final HashMap<String, GpuBufferSlice> uniforms = new HashMap<>();
    private final HashMap<String, TextureViewAndSampler> samplers = new HashMap<>();
    private long dirtyDescriptorMask;
    @Nullable
    private MetalCompiledRenderPipeline compiledPipeline;
    /**
     * When non-null, substitutes the Iris gbuffers pipeline for vanilla's
     * during {@link #bindDrawState} (M5d-1). Set by {@link #setPipeline} when
     * {@link MetalIrisRenderer#isPipelineSwapEnabled()} is true and a cached
     * Iris gbuffers pipeline exists for the active phase. While non-null, the
     * native pipeline state, depth stencil, cull/fill mode, topology and vertex
     * buffer layout all come from the Iris pipeline, and the vanilla named-
     * resource descriptor loop is skipped. Reflected Iris UBO slots are bound
     * by {@link #pushIrisUniformBindings} (M5d-2) and texture/sampler slots by
     * {@link #pushIrisTextureBindings} (M5d-3).
     */
    @Nullable
    private MetalIrisPipeline irisPipeline;
    @Nullable
    private GpuBuffer indexBuffer;
    private MTLIndexType indexType = MTLIndexType.UInt16;
    private int pushedDebugGroups = 0;
    private boolean scissorDirty = true;
    private boolean vertexBuffersDirty = true;
    private boolean pipelineDirty = true;

    MetalRenderPass(
            final MetalDevice device,
            final MetalCommandEncoder encoder,
            final Supplier<String> label,
            final GpuTextureView colorTexture,
            @Nullable final GpuTextureView depthTexture,
            final RenderPass.RenderArea renderArea,
            @Nullable final Vector4fc clearColor,
            final boolean clearDepthEnabled,
            final double clearDepthValue
    ) {
        this.device = device;
        this.commandEncoder = encoder;
        this.label = device.useLabels() ? label.get() : null;
        this.colorTexture = colorTexture;
        this.depthTexture = depthTexture;
        this.renderArea = renderArea;
        this.clearColor = clearColor;
        this.clearDepthEnabled = clearDepthEnabled;
        this.clearDepthValue = clearDepthValue;
    }

    @Override
    public void pushDebugGroup(final @NonNull Supplier<String> label) {
        pushedDebugGroups++;
        if (device.useLabels()) {
            commandEncoder.commandBuffer().pushDebugGroup(label.get());
        }
    }

    @Override
    public void popDebugGroup() {
        if (pushedDebugGroups == 0) {
            throw new IllegalStateException("Can't pop more debug groups than was pushed!");
        }
        pushedDebugGroups--;
        if (device.useLabels()) {
            commandEncoder.commandBuffer().popDebugGroup();
        }
    }

    @Override
    public void setPipeline(final @NonNull RenderPipeline pipeline) {
        MetalCompiledRenderPipeline compiled = device.getOrCompilePipeline(pipeline);
        if (this.compiledPipeline != compiled) {
            this.compiledPipeline = compiled;
            vertexBuffersDirty = true;
            pipelineDirty = true;
        }
        // M5g-1/M5g-2/M5g-3: when the Iris pipeline swap is enabled and a
        // gbuffers phase is active, ensure the gbuffers Iris pipeline is
        // cached — creating it on first use from the compiled MSL and the
        // vanilla vertex format captured here from the active RenderPipeline
        // (M5g-2). The captured VertexFormat[] ensures the Iris vertex
        // descriptor's [[attribute(N)]] mapping matches the vertex buffers
        // vanilla will bind via setVertexBuffer, so Iris gbuffers MSL reads
        // correct per-vertex position/color/UV/lightmap/normal data.
        //
        // Once cached, bindDrawState substitutes the Iris pipeline's native
        // state (and vertex buffer layout) for vanilla's, and reflected
        // UBO/texture/sampler bindings are pushed (M5d-2/M5d-3). When no
        // gbuffers phase is active or the MSL is unavailable, resolved is
        // null and vanilla rendering is unaffected (safe fallback).
        MetalIrisPipeline resolved = null;
        if (MetalIrisRenderer.isPipelineSwapEnabled()) {
            final String programName = MetalIrisRenderer.getActiveGbuffersProgram();
            if (programName != null) {
                resolved = MetalIrisRenderer.ensureGbuffersPipelineCached(
                        programName, pipeline.getVertexFormatBindings());
            }
        }
        if (this.irisPipeline != resolved) {
            this.irisPipeline = resolved;
            vertexBuffersDirty = true;
            pipelineDirty = true;
        }
    }

    @Override
    public void bindTexture(final @NonNull String name, @Nullable final GpuTextureView textureView, @Nullable final GpuSampler sampler) {
        if (textureView != null && sampler != null) {
            samplers.put(name, new TextureViewAndSampler(textureView, sampler));
            commandEncoder.flushPendingClear((MetalGpuTexture) textureView.texture());
            markDescriptorDirty(name);
        } else if (textureView == null && sampler == null) {
            samplers.remove(name);
        } else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public void setUniform(final @NonNull String name, final GpuBuffer value) {
        setUniform(name, value.slice());
    }

    @Override
    public void setUniform(final @NonNull String name, final @NonNull GpuBufferSlice value) {
        uniforms.put(name, value);
        markDescriptorDirty(name);
    }

    @Override
    public void enableScissor(final int x, final int y, final int width, final int height) {
        if (scissorState.enabled()
                && scissorState.x() == x
                && scissorState.y() == y
                && scissorState.width() == width
                && scissorState.height() == height) {
            return;
        }
        scissorState.enable(x, y, width, height);
        scissorDirty = true;
    }

    @Override
    public void disableScissor() {
        if (!scissorState.enabled()) {
            return;
        }
        scissorState.disable();
        scissorDirty = true;
    }

    @Override
    public void setVertexBuffer(final int slot, @Nullable final GpuBufferSlice vertexBuffer) {
        if (slot < 0 || slot >= MAX_VERTEX_BUFFERS) {
            throw new IllegalArgumentException("Unsupported Metal vertex buffer slot: " + slot);
        }

        if (!sameSlice(vertexBuffers[slot], vertexBuffer)) {
            vertexBuffers[slot] = vertexBuffer;
            vertexBuffersDirty = true;
        }
    }

    @Override
    public void setIndexBuffer(@Nullable final GpuBuffer indexBuffer, final @NonNull IndexType indexType) {
        setIndexBuffer(indexBuffer, MTLIndexType.from(indexType));
    }

    private void setIndexBuffer(@Nullable final GpuBuffer indexBuffer, final MTLIndexType indexType) {
        if (this.indexBuffer != indexBuffer || this.indexType != indexType) {
            this.indexBuffer = indexBuffer;
            this.indexType = indexType;
        }
    }

    @Override
    public void drawIndexed(final int indexCount, final int instanceCount, final int firstIndex, final int vertexOffset, final int firstInstance) {
        MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
        MTLRenderCommandEncoder enc = renderEncoder();

        bindDrawState(enc);
        drawIndexedNative(enc, nativeIndexBuffer, firstIndex, indexCount, vertexOffset, instanceCount, indexType, firstInstance);
    }

    @Override
    public void multiDrawIndexed(@NonNull IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        for (int i = 0; i < drawCount; i++) {
            int firstIndex = drawParameters.get(i * 3);
            int indexCount = drawParameters.get(i * 3 + 1);
            int baseVertex = drawParameters.get(i * 3 + 2);
            if (indexCount > 0) {
                drawIndexedNative(enc, nativeIndexBuffer, firstIndex, indexCount, baseVertex, instanceCount, indexType, firstInstance);
            }
        }
    }

    @Override
    public void multiDrawIndexed(@NonNull PointerBuffer firstIndexOffsets, @NonNull IntBuffer indexCounts, @NonNull IntBuffer vertexOffsets, int drawCount) {
        MTLPrimitiveType primitiveType = primitiveTopology();
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            throw new UnsupportedOperationException("Metal backend does not support triangle fan multiDrawIndexed");
        }

        MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        MetalNativeBridge.MTLRenderCommandEncoder_multiDrawIndexed(
                enc.handle(),
                primitiveType.value,
                indexType.value,
                nativeIndexBuffer.nativeHandle(),
                MemorySegment.ofAddress(org.lwjgl.system.MemoryUtil.memAddress(firstIndexOffsets)),
                MemorySegment.ofAddress(org.lwjgl.system.MemoryUtil.memAddress(indexCounts)),
                MemorySegment.ofAddress(org.lwjgl.system.MemoryUtil.memAddress(vertexOffsets)),
                drawCount,
                1L,
                0L
        );
    }

    @Override
    public void drawIndexedIndirect(final @NonNull GpuBufferSlice commands, final int drawCount) {
        MTLPrimitiveType primitiveType = primitiveTopology();
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            throw new UnsupportedOperationException("Metal backend does not support triangle fan indirect draws");
        }

        MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        enc.drawIndexedPrimitivesIndirect(
                primitiveType,
                indexType,
                nativeIndexBuffer.nativeHandle(),
                ((MetalGpuBuffer) commands.buffer()).nativeHandle(),
                commands.offset(),
                drawCount,
                VkDrawIndexedIndirectCommand.SIZEOF
        );
    }

    @Override
    public <T> void drawMultipleIndexed(
            final Collection<RenderPass.Draw<T>> draws,
            @Nullable final GpuBuffer defaultIndexBuffer,
            @Nullable final IndexType defaultIndexType,
            final @NonNull Collection<String> dynamicUniforms,
            final @NonNull T uniformArgument
    ) {
        IndexType fallbackIndexType = defaultIndexType == null ? IndexType.SHORT : defaultIndexType;
        MTLRenderCommandEncoder enc = renderEncoder();

        for (RenderPass.Draw<T> draw : draws) {
            MTLIndexType drawIndexType = MTLIndexType.from(draw.indexType() == null ? fallbackIndexType : draw.indexType());
            GpuBuffer currentIndexBuffer = draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer();

            setIndexBuffer(currentIndexBuffer, drawIndexType);
            setVertexBuffer(draw.slot(), draw.vertexBuffer().slice());

            if (draw.uniformUploaderConsumer() != null) {
                draw.uniformUploaderConsumer().accept(uniformArgument, this::setUniform);
            }

            if (scissorDirty || vertexBuffersDirty || dirtyDescriptorMask != 0L || pipelineDirty) {
                bindDrawState(enc);
            }
            MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
            drawIndexedNative(enc, nativeIndexBuffer, draw.firstIndex(), draw.indexCount(), draw.baseVertex(), 1, drawIndexType, 0);
        }
    }

    @Override
    public void draw(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
        MTLPrimitiveType primitiveType = primitiveTopology();
        MTLRenderCommandEncoder enc = renderEncoder();

        bindDrawState(enc);

        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            drawTriangleFan(enc, firstVertex, vertexCount, instanceCount, firstInstance);
        } else {
            enc.drawPrimitives(primitiveType, firstVertex, vertexCount, Math.max(1, instanceCount), firstInstance);
        }
    }

    @Override
    public void multiDraw(@NonNull IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void multiDraw(@NonNull IntBuffer firstVertices, @NonNull IntBuffer vertexCounts, int drawCount) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void drawIndirect(final @NonNull GpuBufferSlice commands, final int drawCount) {
        MTLPrimitiveType primitiveType = primitiveTopology();
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            throw new UnsupportedOperationException("Metal backend does not support triangle fan indirect draws");
        }

        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        enc.drawPrimitivesIndirect(
                primitiveType,
                ((MetalGpuBuffer) commands.buffer()).nativeHandle(),
                commands.offset(),
                drawCount,
                VkDrawIndirectCommand.SIZEOF
        );
    }

    @Override
    public void writeTimestamp(final @NonNull GpuQueryPool pool, final int index) {
        if (pool instanceof MetalGpuQueryPool metalPool && index >= 0 && index < pool.size()) {
            metalPool.setValue(index, device.getTimestampNow());
        }
    }

    MTLPixelFormat colorAttachmentFormat() {
        return ((MetalGpuTexture) colorTexture.texture()).mtlPixelFormat();
    }

    MTLPixelFormat depthAttachmentFormat() {
        if (depthTexture == null) {
            return MTLPixelFormat.Invalid;
        }
        return ((MetalGpuTexture) depthTexture.texture()).mtlPixelFormat();
    }

    MTLPixelFormat stencilAttachmentFormat() {
        if (depthTexture == null) {
            return MTLPixelFormat.Invalid;
        }
        return ((MetalGpuTexture) depthTexture.texture()).mtlStencilPixelFormat();
    }

    void materializePendingClear() {
        if (clearColor != null || clearDepthEnabled) {
            renderEncoder();
        }
    }

    private MTLRenderCommandEncoder renderEncoder() {
        MetalGpuTextureView colorTextureView = (MetalGpuTextureView) colorTexture;
        MetalGpuTextureView depthTextureView = depthTexture == null ? null : (MetalGpuTextureView) depthTexture;
        boolean clearColorNow = clearColor != null;
        boolean clearDepthNow = clearDepthEnabled;
        MTLRenderCommandEncoder encoder = commandEncoder.renderCommandEncoder(
                colorTextureView,
                depthTextureView,
                colorTexture.getWidth(0),
                colorTexture.getHeight(0),
                clearColorNow,
                clearColorNow ? clearColor.x() : 0.0F,
                clearColorNow ? clearColor.y() : 0.0F,
                clearColorNow ? clearColor.z() : 0.0F,
                clearColorNow ? clearColor.w() : 0.0F,
                clearDepthNow,
                clearDepthValue
        );
        clearColor = null;
        clearDepthEnabled = false;
        return encoder;
    }

    GpuBufferSlice.MappedView allocateTransient(final long size, final long alignment, @GpuBuffer.Usage final int usage) {
        return commandEncoder.transientMemory().allocateGpuMapped(size, alignment, usage);
    }

    private void pushVertexBuffers(final MTLRenderCommandEncoder enc) {
        // M5d-1: vertex buffer layout comes from the Iris pipeline when the
        // override is active (its firstAvailableVertexBufferSlot is 0 and its
        // vertexBufferCount matches the gbuffers vertex descriptor).
        int firstSlot;
        int count;
        if (irisPipeline != null) {
            firstSlot = irisPipeline.firstAvailableVertexBufferSlot();
            count = irisPipeline.vertexBufferCount();
        } else {
            firstSlot = compiledPipeline.firstAvailableVertexBufferSlot();
            count = compiledPipeline.vertexBufferCount();
        }
        for (int slot = 0; slot < count; slot++) {
            GpuBufferSlice vertexBuffer = vertexBuffers[slot];
            if (vertexBuffer == null) {
                continue;
            }
            if (VALIDATION && vertexBuffer.buffer().isClosed()) {
                throw new IllegalStateException("Vertex buffer at slot " + slot + " has been closed");
            }

            MetalGpuBuffer nativeVertexBuffer = (MetalGpuBuffer) vertexBuffer.buffer();
            int metalSlot = firstSlot + slot;
            enc.setBuffer(nativeVertexBuffer.nativeHandle(), vertexBuffer.offset(), metalSlot, MetalCompiledRenderPipeline.STAGE_VERTEX);
        }
    }

    private void drawTriangleFan(MTLRenderCommandEncoder encoder, final int firstVertex, final int vertexCount, final int instanceCount, final int baseInstance) {
        int triangleCount = vertexCount - 2;
        int indexCount = triangleCount * 3;
        MTLIndexType fanIndexType = vertexCount - 1 <= 0xFFFF ? MTLIndexType.UInt16 : MTLIndexType.UInt32;

        try (GpuBufferSlice.MappedView mapped = commandEncoder.transientMemory().allocateGpuMapped((long) indexCount * fanIndexType.bytes, fanIndexType.bytes, GpuBuffer.USAGE_INDEX)) {
            if (fanIndexType == MTLIndexType.UInt16) {
                java.nio.ShortBuffer indices = mapped.data().asShortBuffer();
                for (int i = 0; i < triangleCount; i++) {
                    indices.put((short) 0);
                    indices.put((short) (i + 1));
                    indices.put((short) (i + 2));
                }
            } else {
                java.nio.IntBuffer indices = mapped.data().asIntBuffer();
                for (int i = 0; i < triangleCount; i++) {
                    indices.put(0);
                    indices.put(i + 1);
                    indices.put(i + 2);
                }
            }
            GpuBufferSlice slice = mapped.slice();
            encoder.drawIndexedPrimitives(MTLPrimitiveType.Triangle, indexCount, fanIndexType, ((MetalGpuBuffer) slice.buffer()).nativeHandle(), slice.offset(), Math.max(1, instanceCount), firstVertex, baseInstance);
        }
    }

    private void drawIndexedNative(
            final MTLRenderCommandEncoder enc,
            final MetalGpuBuffer nativeIndexBuffer,
            final int firstIndex,
            final int indexCount,
            final int baseVertex,
            final int instanceCount,
            final MTLIndexType indexType,
            final int baseInstance
    ) {
        MTLPrimitiveType primitiveType = primitiveTopology();

        long indexOffsetBytes = (long) firstIndex * indexType.bytes;
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            long fanSize = Math.multiplyExact(Math.multiplyExact((long) indexCount - 2L, 3L), Integer.BYTES);
            try (GpuBufferSlice.MappedView mapped = commandEncoder.transientMemory().allocateGpuMapped(fanSize, Integer.BYTES, GpuBuffer.USAGE_INDEX)) {
                GpuBufferSlice slice = mapped.slice();
                enc.drawIndexedPrimitivesTriangleFan(
                        nativeIndexBuffer.nativeHandle(),
                        ((MetalGpuBuffer) slice.buffer()).nativeHandle(),
                        slice.offset(),
                        indexType.value,
                        indexOffsetBytes,
                        indexCount,
                        baseVertex,
                        instanceCount,
                        baseInstance
                );
            }
        } else {
            enc.drawIndexedPrimitives(primitiveType, indexCount, indexType, nativeIndexBuffer.nativeHandle(), indexOffsetBytes, instanceCount, baseVertex, baseInstance);
        }
    }

    private void bindDrawState(final MTLRenderCommandEncoder enc) {
        if (compiledPipeline == null) {
            throw new IllegalStateException("Pipeline is missing");
        }

        if (pipelineDirty) {
            boolean useDepth = depthAttachmentFormat().value != MTLPixelFormat.Invalid.value;
            // M5d-1: when an Iris gbuffers pipeline override is active, bind its
            // native pipeline state / depth stencil / draw state instead of
            // vanilla's. The Iris MSL uses a different (SPIR-V-cross) binding
            // convention than vanilla's named-resource model, so the vanilla
            // descriptor loop below is skipped while the override is active;
            // Iris UBO/sampler binding is added in M5d-2/M5d-3.
            MemorySegment pipelineHandle;
            MemorySegment depthState;
            float depthBiasConstant;
            float depthBiasScaleFactor;
            MTLCullMode cullMode;
            MTLTriangleFillMode fillMode;
            if (irisPipeline != null) {
                pipelineHandle = irisPipeline.getNativePipeline(useDepth);
                depthState = irisPipeline.depthStencilState();
                depthBiasConstant = irisPipeline.depthBiasConstant();
                depthBiasScaleFactor = irisPipeline.depthBiasScaleFactor();
                cullMode = irisPipeline.cullMode();
                fillMode = irisPipeline.fillMode();
            } else {
                pipelineHandle = compiledPipeline.getNativePipeline(useDepth);
                depthState = compiledPipeline.getDepthStencilState();
                depthBiasConstant = compiledPipeline.depthBiasConstant();
                depthBiasScaleFactor = compiledPipeline.depthBiasScaleFactor();
                cullMode = compiledPipeline.cullMode();
                fillMode = compiledPipeline.fillMode();
            }
            if (MetalNativeBridge.isNullHandle(pipelineHandle)) {
                throw new IllegalStateException("Native pipeline is unavailable");
            }
            enc.setRenderPipelineState(pipelineHandle);
            pipelineDirty = false;

            if (useDepth) {
                if (MetalNativeBridge.isNullHandle(depthState)) {
                    throw new IllegalStateException("Native depth state is unavailable");
                }
                enc.setDepthStencilState(depthState);
                enc.setDepthBias(depthBiasConstant, depthBiasScaleFactor, 0.0f);
            }

            // Clockwise (not CounterClockwise) because SPIRV-Cross's
            // FLIP_VERTEX_Y flips the Y coordinate in the vertex shader,
            // which reverses triangle winding (OpenGL CCW -> Metal CW).
            // Using CounterClockwise here causes Back culling to remove
            // all front-facing triangles, resulting in a black screen.
            enc.setFrontFacingWinding(MTLWinding.Clockwise);
            enc.setCullMode(cullMode);
            enc.setTriangleFillMode(fillMode);

            if (irisPipeline == null) {
                dirtyDescriptorMask |= compiledPipeline.allResourceMask();
            }
        }

        if (scissorDirty) {
            pushEffectiveScissor(enc);
            scissorDirty = false;
        }

        if (vertexBuffersDirty) {
            pushVertexBuffers(enc);
            vertexBuffersDirty = false;
        }

        if (irisPipeline == null && dirtyDescriptorMask != 0) {
            for (MetalCompiledRenderPipeline.ResourceBinding binding : compiledPipeline.resources()) {
                if ((dirtyDescriptorMask & (1L << binding.bindingIndex())) != 0L) {
                    pushDescriptor(enc, binding);
                }
            }
        } else if (irisPipeline != null) {
            // M5d-2/M5d-3: bind reflected Iris UBO and texture/sampler slots.
            // The swap is enabled by MetalIrisRenderer.pipelineSwapEnabled
            // (flipped to true in MetalIrisRenderingPipeline.beginLevelRendering
            // once both UBO and texture/sampler binding were in place), so the
            // Iris MSL never runs with unbound arguments.
            pushIrisUniformBindings(enc, irisPipeline);
            pushIrisTextureBindings(enc, irisPipeline);
        }

        dirtyDescriptorMask = 0L;
    }

    private MTLPrimitiveType primitiveTopology() {
        if (compiledPipeline == null) {
            throw new IllegalStateException("Pipeline is missing");
        }
        return irisPipeline != null ? irisPipeline.topology() : compiledPipeline.topology();
    }

    private void pushEffectiveScissor(final MTLRenderCommandEncoder enc) {
        int areaLeft = renderArea.x();
        int areaTop = renderArea.y();
        if (!scissorState.enabled()) {
            if (renderArea.fillsTexture(colorTexture)) {
                enc.setScissorRect(0L, 0L, colorTexture.getWidth(0), colorTexture.getHeight(0));
                return;
            }
            enc.setScissorRect(areaLeft, areaTop, renderArea.width(), renderArea.height());
            return;
        }

        int areaRight = areaLeft + renderArea.width();
        int areaBottom = areaTop + renderArea.height();
        int left = Math.max(areaLeft, scissorState.x());
        int top = Math.max(areaTop, scissorState.y());
        int right = Math.min(areaRight, scissorState.x() + scissorState.width());
        int bottom = Math.min(areaBottom, scissorState.y() + scissorState.height());
        if (right <= left || bottom <= top) {
            enc.setScissorRect(0, 0, 0, 0);
        } else {
            enc.setScissorRect(left, top, right - left, bottom - top);
        }
    }

    private void markDescriptorDirty(final String name) {
        if (compiledPipeline != null) {
            MetalCompiledRenderPipeline.ResourceBinding binding = compiledPipeline.resource(name);
            if (binding != null) {
                dirtyDescriptorMask |= 1L << binding.bindingIndex();
            }
        }
    }

    private void pushDescriptor(
            final MTLRenderCommandEncoder enc,
            final MetalCompiledRenderPipeline.ResourceBinding binding
    ) {
        if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE) {
            TextureViewAndSampler textureBinding = samplers.get(binding.name());
            if (textureBinding == null) {
                throw new IllegalStateException("Missing sampler " + binding.name());
            }

            if (VALIDATION && textureBinding.textureView().isClosed()) {
                throw new IllegalStateException("Sampler " + binding.name() + " texture view has been closed");
            }

            MetalGpuTextureView textureView = (MetalGpuTextureView) textureBinding.textureView();
            MetalGpuSampler sampler = (MetalGpuSampler) textureBinding.sampler();
            enc.setTextureAndSampler(textureView.nativeHandle(), sampler.nativeHandle(), binding.bindingIndex(), binding.stageMask());
            return;
        }

        if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.TEXEL_BUFFER) {
            pushTexelBufferDescriptor(enc, binding);
            return;
        }

        GpuBufferSlice uniformSlice = uniforms.get(binding.name());
        if (uniformSlice == null) {
            throw new IllegalStateException("Missing uniform " + binding.name());
        }
        if (VALIDATION && uniformSlice.buffer().isClosed()) {
            throw new IllegalStateException("Uniform " + binding.name() + " buffer has been closed");
        }

        MetalGpuBuffer uniformBuffer = (MetalGpuBuffer) uniformSlice.buffer();
        enc.setBuffer(uniformBuffer.nativeHandle(), uniformSlice.offset(), binding.bindingIndex(), binding.stageMask());
    }

    private void pushTexelBufferDescriptor(final MTLRenderCommandEncoder enc, final MetalCompiledRenderPipeline.ResourceBinding binding) {
        GpuBufferSlice texelSlice = uniforms.get(binding.name());
        if (texelSlice == null) {
            throw new IllegalStateException("Missing texel buffer " + binding.name());
        }
        if (VALIDATION && texelSlice.buffer().isClosed()) {
            throw new IllegalStateException("Texel buffer " + binding.name() + " has been closed");
        }

        GpuFormat texelFormat = binding.texelBufferFormat();
        if (texelFormat == null) {
            throw new IllegalStateException("Texel buffer " + binding.name() + " is missing a format");
        }

        MetalGpuBuffer texelBuffer = (MetalGpuBuffer) texelSlice.buffer();
        long pixelFormat = MTLPixelFormat.from(texelFormat).value;
        int pixelSize = texelFormat.blockSize();
        long texelByteLength = texelSlice.length();
        if (texelByteLength <= 0L || texelByteLength % pixelSize != 0L) {
            throw new IllegalStateException("Texel buffer " + binding.name() + " length " + texelByteLength + " is not a valid " + texelFormat + " range");
        }
        long texelCount = texelByteLength / pixelSize;
        MemorySegment texelTexture = MetalNativeBridge.metallum_create_buffer_texture_view(
                texelBuffer.nativeHandle(),
                pixelFormat,
                texelSlice.offset(),
                texelCount,
                1L,
                texelByteLength
        );
        if (MetalNativeBridge.isNullHandle(texelTexture)) {
            throw new IllegalStateException("Failed to create Metal texel buffer texture for " + binding.name());
        }

        enc.setTexture(texelTexture, binding.bindingIndex(), binding.stageMask());
        commandEncoder.queueForDestroy(() -> MetalNativeBridge.metallum_release_object(texelTexture));
    }

    /**
     * Binds reflected Iris MSL UBO slots (M5d-2, extended in M5e). For each
     * reflected {@link MetalIrisPipeline.IrisResourceBinding} of kind
     * {@link MetalIrisPipeline.IrisResourceKind#UNIFORM_BUFFER}, binds the
     * matching {@link #uniforms} entry if one was provided via
     * {@link #setUniform}; otherwise, if the pipeline has a registered
     * {@code iris_LooseUniforms} layout (M5e), binds the per-program
     * {@link MetalIrisUniformProvider}'s marshalled buffer (real camera
     * matrices, fog, ...); otherwise falls back to the device's zeroed scratch
     * uniform buffer so the MSL's {@code [[buffer(N)]]} argument is never left
     * unbound.
     *
     * <p><b>M5e name-matching heuristic.</b> SPIRV-Cross may emit the
     * {@code iris_LooseUniforms} UBO variable with a generated name (e.g.
     * {@code _19}) rather than the GLSL block name, so exact name matching is
     * unreliable. Instead, the provider's buffer is bound when either:
     * <ol>
     *   <li>the reflected binding name contains {@code "looseuniform"} (case-
     *       insensitive) — matches when SPIRV-Cross preserves the block name;
     *       <em>or</em></li>
     *   <li>the pipeline has a non-null {@link MetalIrisPipeline#looseUniformLayout}
     *       and the binding is the <em>only</em> unprovided UBO slot — the
     *       common case for Iris programs which declare a single UBO.</li>
     * </ol>
     * For programs with multiple unprovided UBOs and no name match, the slot
     * falls back to the 16 KiB scratch buffer (safe — large enough for any
     * typical UBO struct, avoiding Metal validation traps on undersized
     * buffers).
     *
     * <p>The provider's {@link MetalIrisUniformProvider#marshal()} is called
     * once per {@link #bindDrawState} to refresh the buffer from live Iris
     * state before binding. The marshal zero-fill was optimized in M6-3 to use
     * 8-byte bulk writes instead of byte-by-byte. UBO binding dirtiness
     * tracking (skipping re-bind when the same buffer is already bound) is not
     * yet implemented — all reflected UBOs are re-bound on every
     * {@link #bindDrawState} call while the Iris override is active. This is
     * correct (idempotent) and can be optimized later.
     */
    private void pushIrisUniformBindings(final MTLRenderCommandEncoder enc, final MetalIrisPipeline iris) {
        final MetalGpuBuffer scratch = device.getOrEnsureIrisScratchUniformBuffer();
        // M5e: obtain the per-program uniform provider (if the program has a
        // registered iris_LooseUniforms layout) and refresh its contents.
        MetalIrisUniformProvider provider = null;
        if (iris.looseUniformLayout() != null) {
            provider = MetalIrisRenderer.getUniformProvider(device, iris.name());
            if (provider != null) {
                provider.marshal();
            }
        }
        // Count unprovided UBO bindings to apply the single-UBO heuristic
        // below (see method javadoc, M5e name-matching heuristic #2).
        int unprovidedUboCount = 0;
        if (provider != null) {
            for (MetalIrisPipeline.IrisResourceBinding b : iris.bindings()) {
                if (b.kind() != MetalIrisPipeline.IrisResourceKind.UNIFORM_BUFFER) {
                    continue;
                }
                final GpuBufferSlice s = uniforms.get(b.name());
                if (s == null || s.buffer().isClosed()) {
                    unprovidedUboCount++;
                }
            }
        }
        for (MetalIrisPipeline.IrisResourceBinding binding : iris.bindings()) {
            if (binding.kind() != MetalIrisPipeline.IrisResourceKind.UNIFORM_BUFFER) {
                continue;
            }
            final GpuBufferSlice slice = uniforms.get(binding.name());
            if (slice != null && !slice.buffer().isClosed()) {
                enc.setBuffer(
                        ((MetalGpuBuffer) slice.buffer()).nativeHandle(),
                        slice.offset(),
                        binding.bindingIndex(),
                        binding.stageMask());
            } else if (provider != null && isLikelyLooseUniforms(binding.name(), unprovidedUboCount)) {
                // M5e: bind the marshalled real uniform data instead of the
                // zeroed scratch buffer.
                enc.setBuffer(
                        provider.buffer().nativeHandle(),
                        0L,
                        binding.bindingIndex(),
                        binding.stageMask());
            } else {
                enc.setBuffer(
                        scratch.nativeHandle(),
                        0L,
                        binding.bindingIndex(),
                        binding.stageMask());
            }
        }
    }

    /**
     * Heuristic for whether a reflected UBO binding is the
     * {@code iris_LooseUniforms} UBO (M5e). See
     * {@link #pushIrisUniformBindings} javadoc for the full rationale.
     */
    private static boolean isLikelyLooseUniforms(final String bindingName, final int unprovidedUboCount) {
        if (bindingName != null
                && bindingName.toLowerCase(java.util.Locale.ROOT).contains("looseuniform")) {
            return true;
        }
        // If this is the only unprovided UBO, assume it's iris_LooseUniforms.
        return unprovidedUboCount == 1;
    }

    /**
     * Binds reflected Iris MSL texture and sampler slots (M5d-3, extended in
     * M5f). SPIRV-Cross emits a GLSL combined image sampler
     * ({@code uniform sampler2D foo;}) as a texture argument
     * {@code [[texture(N)]]} plus a sampler argument {@code [[sampler(N)]]}
     * sharing the same index {@code N}. Metal binds the pair together via
     * {@code setTextureAndSampler}, so this method pairs each reflected
     * {@link MetalIrisPipeline.IrisResourceKind#TEXTURE} binding with the
     * {@link MetalIrisPipeline.IrisResourceKind#SAMPLER} at the same index.
     *
     * <p><b>M5f name resolution.</b> For each texture binding, the method
     * resolves the texture + sampler in this priority order:
     * <ol>
     *   <li><b>Vanilla-provided sampler (with Iris aliases).</b> If the
     *       {@link #samplers} map has an entry for the reflected name (direct
     *       match) or for an Iris alias of the name (e.g. {@code gtexture}
     *       &rarr; {@code Sampler0}, {@code lightmap} &rarr; {@code Sampler1},
     *       {@code iris_overlay} &rarr; {@code Sampler2}), bind that texture
     *       view + sampler. See {@link #resolveProvidedSampler} and
     *       {@link #irisSamplerAlias}.</li>
     *   <li><b>Iris render-target texture.</b> Otherwise, if the reflected name
     *       matches an Iris render-target convention ({@code colortex0..7},
     *       {@code depthtex0..2}, {@code shadowtex0/1}, {@code shadow},
     *       {@code watershadow}, {@code gcolor}), bind the corresponding pool
     *       view from {@link MetalIrisRenderer#getIrisRenderTargetTexture}
     *       paired with the dummy sampler (clamp-to-edge, linear — appropriate
     *       for color/depth target sampling).</li>
     *   <li><b>Dummy fallback.</b> Otherwise, bind the device's cached 1&times;1
     *       dummy texture + default sampler so the MSL's
     *       {@code [[texture(N)]]} / {@code [[sampler(N)]]} arguments are never
     *       left unbound.</li>
     * </ol>
     *
     * <p>Like {@link #pushIrisUniformBindings}, all reflected texture/sampler
     * pairs are re-bound on every {@link #bindDrawState} call (idempotent).
     */
    private void pushIrisTextureBindings(final MTLRenderCommandEncoder enc, final MetalIrisPipeline iris) {
        final MemorySegment dummyTexture = MetalIrisRenderer.getDummyTexture(device);
        final MemorySegment dummySampler = MetalIrisRenderer.getDummySampler(device);

        // Pair each texture binding with the sampler at the same index. Metal's
        // vertex/fragment tables are independent, but SPIRV-Cross emits paired
        // texture+sampler at the same index within a stage, so keying by index
        // (within the merged binding list) is sufficient.
        final HashMap<Integer, MetalIrisPipeline.IrisResourceBinding> samplersByIndex = new HashMap<>();
        for (MetalIrisPipeline.IrisResourceBinding binding : iris.bindings()) {
            if (binding.kind() == MetalIrisPipeline.IrisResourceKind.SAMPLER) {
                samplersByIndex.put(binding.bindingIndex(), binding);
            }
        }

        for (MetalIrisPipeline.IrisResourceBinding binding : iris.bindings()) {
            if (binding.kind() != MetalIrisPipeline.IrisResourceKind.TEXTURE) {
                continue;
            }
            final int index = binding.bindingIndex();
            int stageMask = binding.stageMask();
            final MetalIrisPipeline.IrisResourceBinding samplerBinding = samplersByIndex.get(index);
            if (samplerBinding != null) {
                stageMask |= samplerBinding.stageMask();
            }

            MemorySegment textureHandle = dummyTexture;
            MemorySegment samplerHandle = dummySampler;

            // M5f-1: vanilla-provided sampler (with Iris name aliases).
            final TextureViewAndSampler provided = resolveProvidedSampler(binding.name());
            if (provided != null
                    && !provided.textureView().isClosed()
                    && !((MetalGpuSampler) provided.sampler()).isClosed()) {
                textureHandle = ((MetalGpuTextureView) provided.textureView()).nativeHandle();
                samplerHandle = ((MetalGpuSampler) provided.sampler()).nativeHandle();
            } else {
                // M5f-2: Iris render-target texture (colortex*, depthtex*,
                // shadowtex*, gcolor, shadow, watershadow).
                final MetalGpuTextureView rtView =
                        MetalIrisRenderer.getIrisRenderTargetTexture(binding.name());
                if (rtView != null && !rtView.isClosed()) {
                    textureHandle = rtView.nativeHandle();
                    // Use the dummy sampler for render-target textures
                    // (clamp-to-edge, linear filter — appropriate for color/
                    // depth target sampling).
                    samplerHandle = dummySampler;
                }
                // else: fall through to dummy texture + dummy sampler.
            }

            if (MetalNativeBridge.isNullHandle(textureHandle) || MetalNativeBridge.isNullHandle(samplerHandle)) {
                continue;
            }
            enc.setTextureAndSampler(textureHandle, samplerHandle, index, stageMask);
        }
    }

    /**
     * Resolves a reflected Iris texture binding name to a vanilla-provided
     * sampler, applying Iris name aliases (M5f). Iris shaderpacks use names like
     * {@code gtexture}, {@code lightmap}, {@code iris_overlay} instead of
     * vanilla's {@code Sampler0}, {@code Sampler1}, {@code Sampler2}. This
     * method first tries a direct match in the {@link #samplers} map, then
     * maps the Iris alias to the vanilla name and looks that up.
     *
     * @param bindingName the reflected MSL texture binding name
     * @return the vanilla-provided texture view + sampler, or {@code null} if
     *         no vanilla sampler was provided for this name or its alias
     */
    private TextureViewAndSampler resolveProvidedSampler(final String bindingName) {
        if (bindingName == null) {
            return null;
        }
        // Direct match first (handles vanilla names like "Sampler0" and any
        // names vanilla happened to provide under the Iris name).
        final TextureViewAndSampler direct = samplers.get(bindingName);
        if (direct != null) {
            return direct;
        }
        // Iris alias → vanilla sampler name.
        final String vanillaName = irisSamplerAlias(bindingName);
        if (vanillaName != null) {
            return samplers.get(vanillaName);
        }
        return null;
    }

    /**
     * Maps an Iris sampler alias to the vanilla sampler name (M5f). Returns
     * {@code null} if the name is not a known vanilla-sampler alias.
     *
     * <p>Based on Iris's {@code IrisSamplers.addLevelSamplers} naming
     * conventions (studied from the read-only {@code /workspace/MU-iris}
     * reference). Note: {@code gcolor} is intentionally NOT mapped here — it
     * is an alias for {@code colortex0} (a render-target texture), resolved by
     * {@link MetalIrisRenderer#getIrisRenderTargetTexture}, not a vanilla
     * sampler.
     *
     * <table>
     *   <tr><th>Iris alias</th><th>Vanilla name</th><th>Meaning</th></tr>
     *   <tr><td>{@code gtexture}, {@code tex}, {@code texture},
     *           {@code u_MainSampler}</td>
     *       <td>{@code Sampler0}</td><td>main albedo texture</td></tr>
     *   <tr><td>{@code lightmap}</td>
     *       <td>{@code Sampler1}</td><td>lightmap texture</td></tr>
     *   <tr><td>{@code iris_overlay}, {@code overlay}</td>
     *       <td>{@code Sampler2}</td><td>overlay texture</td></tr>
     * </table>
     */
    private static String irisSamplerAlias(final String name) {
        return switch (name) {
            case "gtexture", "tex", "texture", "u_MainSampler" -> "Sampler0";
            case "lightmap" -> "Sampler1";
            case "iris_overlay", "overlay" -> "Sampler2";
            default -> null;
        };
    }

    record TextureViewAndSampler(GpuTextureView textureView, GpuSampler sampler) {
    }

    private static boolean sameSlice(@Nullable final GpuBufferSlice left, @Nullable final GpuBufferSlice right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.buffer() == right.buffer()
                && left.offset() == right.offset()
                && left.length() == right.length();
    }
}
