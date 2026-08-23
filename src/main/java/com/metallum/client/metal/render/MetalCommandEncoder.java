package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.*;
import com.metallum.client.validation.contract.AttachmentBindingRecord;
import com.metallum.client.validation.contract.AttachmentSemantic;
import com.metallum.client.validation.contract.CapturePoint;
import com.metallum.client.validation.contract.CapturePointKind;
import com.metallum.client.validation.contract.PassType;
import com.metallum.client.validation.contract.ProducerType;
import com.metallum.client.validation.contract.RenderContractRuntime;
import com.metallum.client.validation.contract.ResourceIdentity;
import com.metallum.client.validation.contract.SemanticPassIdResolver;
import com.metallum.client.validation.contract.ScissorRecord;
import com.metallum.client.validation.contract.TraceIdentity;
import com.metallum.client.validation.contract.ViewportRecord;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

@Environment(EnvType.CLIENT)
final class MetalCommandEncoder implements CommandEncoderBackend {
    public static final int MAX_SUBMITS_IN_FLIGHT = 3;
    // Depth MAX_SUBMITS_IN_FLIGHT+1: an action queued during submit N runs at
    // submit N+3, whose semaphore wait has just confirmed submit N (the last
    // possible GPU consumer of the queued resource) completed. A depth of 3
    // would run it at N+2 with only N-1 confirmed, racing pooled reuse and
    // readback callbacks against in-flight GPU work.
    static final boolean DEFERRED_DEPTH_STORE =
            Boolean.parseBoolean(System.getProperty("metallum.opt.deferredStore", "true"));
    /**
     * Defers every live V3 color store decision (.unknown) so a full-clear
     * successor can resolve it to dontCare instead of paying dead tile
     * write-back. Suppression fires only with the same-texture full-clear
     * proof; pixels are identical by construction.
     */
    static final boolean DEFERRED_COLOR_STORE =
            Boolean.parseBoolean(System.getProperty("metallum.opt.deferredColorStore", "true"));
    private static final boolean BLIT_BATCH =
            Boolean.parseBoolean(System.getProperty("metallum.opt.blitBatch", "true"));
    private final MetalDevice device;
    private long currentSubmitIndex = MAX_SUBMITS_IN_FLIGHT;
    private final InFlight[] inFlight = new InFlight[MAX_SUBMITS_IN_FLIGHT];
    private final MemorySegment[] submitSemaphores = new MemorySegment[MAX_SUBMITS_IN_FLIGHT];
    private final MetalDestructionQueue destroyQueue = new MetalDestructionQueue(MAX_SUBMITS_IN_FLIGHT + 1);
    private final MetalTransientMemory transientMemory;
    private final Map<MetalGpuTexture, Vector4fc> pendingColorClears = new IdentityHashMap<>();
    private final Map<MetalGpuTexture, Double> pendingDepthClears = new IdentityHashMap<>();
    /**
     * S10 split-fence mode: blit (transfer) work signals its own fence so
     * render encoders can begin vertex work waiting only on transfers, and
     * defer waiting on prior render output until the fragment stage — the
     * TBDR overlap the single-fence chain serializes away. Default off; the
     * off path is byte-identical to the pre-split encoder.
     */
    static final boolean SPLIT_FENCE =
            Boolean.parseBoolean(System.getProperty("metallum.opt.splitFence", "false"));
    private final MemorySegment fence;
    private final MemorySegment transferFence;
    private final float[] currentViewProjectionBuffer = new float[16];
    private final float[] inverseViewProjectionBuffer = new float[16];
    private final float[] previousViewProjectionBuffer = new float[16];
    @Nullable
    private MetalRenderPass currentRenderPass;
    @Nullable
    private MTLCommandBuffer commandBuffer;
    @Nullable
    private MTLCommandEncoder currentEncoder;
    private MemorySegment[] renderColorAttachments = new MemorySegment[0];
    private MetalGpuTexture[] renderColorTextures = new MetalGpuTexture[0];
    private MemorySegment renderDepthAttachment = MemorySegment.NULL;
    private MemorySegment[] killedColorAttachments = new MemorySegment[0];
    private MetalGpuTexture[] killedColorTextures = new MetalGpuTexture[0];
    private long deferredColorStorePixels;
    private int[] deferredColorStorePixelBytes = new int[0];
    // Bumped every time a fresh native encoder is installed. MetalRenderPass
    // compares generations to know its cached dirty-state no longer matches a
    // rebuilt encoder (a new MTLRenderCommandEncoder starts with no state).
    private long encoderGeneration;
    @Nullable
    private MetalGpuTexture renderDepthTexture;
    private boolean renderEncoderDeferredStore;
    @Nullable
    private boolean[] renderEncoderDeferredColorStores;

    /**
     * RenderPassDescriptorV3 selection. "auto" (default) uses V3 whenever the
     * loaded dylib exposes the symbol and falls back to V2 otherwise; "v2"
     * forces the legacy path (IrisGraphBench equivalence runs); "v3" demands
     * the symbol and fails closed if it is missing.
     */
    private static volatile String renderPassAbiMode =
            System.getProperty("metallum.renderpass.abi", "auto");

    static void setRenderPassAbiModeForTests(final String mode) {
        renderPassAbiMode = mode;
    }

    private static boolean deferredColorStoreActive() {
        return DEFERRED_COLOR_STORE && MetalNativeBridge.colorStoreResolutionAvailable();
    }

    private static boolean renderPassDescriptorV3Active() {
        switch (renderPassAbiMode) {
            case "v2":
                return false;
            case "v3":
                if (!MetalNativeBridge.renderCommandEncoderV3Available()) {
                    throw new IllegalStateException(
                            "metallum.renderpass.abi=v3 but the loaded native bridge has no V3 symbol");
                }
                return true;
            default:
                return MetalNativeBridge.renderCommandEncoderV3Available();
        }
    }

    private final Long2ObjectOpenHashMap<java.util.ArrayDeque<MemorySegment>> dynamicBackingPool = new Long2ObjectOpenHashMap<>();
    private static final int MAX_POOLED_DYNAMIC_BACKINGS_PER_SIZE = 8;
    private final List<SubmitCallback> currentSubmitCallbacks = new ArrayList<>();
    private int contractTraceGroupDepth;

    MetalCommandEncoder(final MetalDevice device) {
        this.device = device;
        this.transientMemory = new MetalTransientMemory(device, this);
        fence = MetalNativeBridge.metallum_create_fence(device.metalDeviceHandle());
        if (MetalNativeBridge.isNullHandle(fence)) {
            throw new IllegalStateException("Failed to allocate MTLFence");
        }
        if (SPLIT_FENCE) {
            transferFence = MetalNativeBridge.metallum_create_fence(device.metalDeviceHandle());
            if (MetalNativeBridge.isNullHandle(transferFence)) {
                throw new IllegalStateException("Failed to allocate transfer MTLFence");
            }
            // Native-side blits (FG input copies) must join the same chain.
            MetalNativeBridge.metallum_set_transfer_fence(transferFence);
        } else {
            transferFence = MemorySegment.NULL;
        }
        for (int slot = 0; slot < MAX_SUBMITS_IN_FLIGHT; slot++) {
            submitSemaphores[slot] = MetalNativeBridge.metallum_create_semaphore();
            if (MetalNativeBridge.isNullHandle(submitSemaphores[slot])) {
                throw new IllegalStateException("Failed to allocate submit semaphore");
            }
        }
    }

    MTLCommandBuffer commandBuffer() {
        if (commandBuffer != null) {
            return commandBuffer;
        }
        return commandBuffer = device.commandQueue.makeCommandBuffer(
                device.useLabels() ? "Metallum frame " + currentSubmitIndex : null
        );
    }

    MTLBlitCommandEncoder blitCommandEncoder() {
        // Consecutive CPU-source uploads (writeToBuffer/writeToTexture/
        // copyToBuffer/copyBufferToTexture) share one blit encoder and one
        // fence wait/update pair. Ops that read GPU-written textures
        // (copyTextureToBuffer/copyTextureToTexture) call endEncoder() first
        // so they never join a batch whose ordering they would depend on.
        if (BLIT_BATCH && currentEncoder instanceof MTLBlitCommandEncoder blit) {
            return blit;
        }
        endEncoder();
        MTLBlitCommandEncoder encoder = commandBuffer().makeBlitCommandEncoder("batched upload/copy");
        encoder.waitForFence(fence);
        if (SPLIT_FENCE) {
            // Transfer-chain ordering (WAW/upload sequencing between blits)
            // no longer flows through the render fence.
            encoder.waitForFence(transferFence);
        }
        encoderGeneration++;
        currentEncoder = encoder;
        return encoder;
    }

    /**
     * Compute encoders stay on the <b>render</b> fence, in both modes.
     *
     * <p>The split only moves the blit/upload chain onto {@code transferFence};
     * per {@code NativeState.transferFence}'s invariant, the sole encoder on
     * that chain is the frame-generation input-copy blit, and every other
     * encoder keeps the render fence. The M6 dependency table's
     * "compute writes &rarr; render reads" row records the same thing for
     * Metal 3 (full wait/update on the passed fence), and it is what the
     * Metal 4 migration translates into the dispatch&rarr;fragment barrier
     * pair — so putting compute on both chains here would mistranslate later.
     *
     * <p>Guarded by {@code metalComputeBackendIntegrationTest}'s
     * render&rarr;compute&rarr;render / compute&rarr;compute / indirect-args
     * ordering cases.
     */
    MTLComputeCommandEncoder computeCommandEncoder() {
        endEncoder();
        MTLComputeCommandEncoder encoder = commandBuffer().makeComputeCommandEncoder();
        encoder.waitForFence(fence);
        encoderGeneration++;
        currentEncoder = encoder;
        return encoder;
    }

    long encoderGeneration() {
        return encoderGeneration;
    }

    boolean isCurrentEncoder(final MTLRenderCommandEncoder encoder) {
        return currentEncoder == encoder;
    }

    /**
     * Render-encoder fence waits. Split mode narrows by dependency type per
     * the S10 table: uploads gate vertex fetch, while prior render output is
     * only consumed from the fragment stage (sampling, attachment loads,
     * depth test), letting this pass's tiling overlap the previous pass's
     * fragment work.
     */
    private void waitRenderFences(final MTLRenderCommandEncoder encoder) {
        if (SPLIT_FENCE) {
            encoder.waitForFence(transferFence, MTLRenderStages.Vertex);
            encoder.waitForFence(fence, MTLRenderStages.Fragment);
        } else {
            encoder.waitForFence(fence, MTLRenderStages.VertexAndFragment);
        }
    }

    void endEncoder() {
        endEncoder(false, null);
    }

    private void endEncoder(
            final boolean incomingClearsSameDepth,
            final boolean[] colorStoresKilled
    ) {
        if (currentEncoder != null) {
            if (currentEncoder instanceof MTLRenderCommandEncoder renderEncoder) {
                if (renderEncoderDeferredStore) {
                    // The descriptor used storeAction=.unknown, so the store
                    // decision is owed before endEncoding. The depth contents
                    // are dead when a clear is already pending for the texture
                    // (any later reader goes through flushPendingClear) or the
                    // pass breaking this encoder clears the same attachment.
                    boolean deadDepth = incomingClearsSameDepth
                            || (renderDepthTexture != null && pendingDepthClears.containsKey(renderDepthTexture));
                    renderEncoder.setDeferredDepthStore(!deadDepth);
                    if (deadDepth && renderDepthTexture != null) {
                        RenderGraphTelemetry.onDepthStoreKilled(
                                (long) renderDepthTexture.getWidth(0) * renderDepthTexture.getHeight(0),
                                renderDepthTexture.pixelSize()
                        );
                    }
                }
                if (renderEncoderDeferredColorStores != null) {
                    // Resolve every deferred color store before endEncoding;
                    // a full-clear same-texture successor proved the
                    // predecessor's store dead, everything else keeps its
                    // contents alive. Accounting counts ONLY the slots whose
                    // evidence resolved them to dontCare.
                    int[] killedPixelBytes = new int[renderEncoderDeferredColorStores.length];
                    int killedSlotCount = 0;
                    for (int index = 0; index < renderEncoderDeferredColorStores.length; index++) {
                        if (!renderEncoderDeferredColorStores[index]) {
                            continue;
                        }
                        boolean killed = colorStoresKilled != null
                                && index < colorStoresKilled.length
                                && colorStoresKilled[index]
                                && index < deferredColorStorePixelBytes.length
                                && deferredColorStorePixelBytes[index] > 0;
                        renderEncoder.setDeferredColorStore(index, !killed);
                        if (killed) {
                            killedPixelBytes[index] = deferredColorStorePixelBytes[index];
                            killedSlotCount++;
                        }
                    }
                    if (killedSlotCount > 0) {
                        RenderGraphTelemetry.onColorStoresKilled(
                                deferredColorStorePixels,
                                killedPixelBytes,
                                killedSlotCount
                        );
                    }
                }
                // Signal timing is identical either way (the fence fires
                // after the last listed stage); the split form documents the
                // consumer contract: prior render output gates fragment work.
                renderEncoder.updateFence(
                        fence,
                        SPLIT_FENCE ? MTLRenderStages.Fragment : MTLRenderStages.VertexAndFragment
                );
            } else if (currentEncoder instanceof MTLBlitCommandEncoder blitEncoder) {
                blitEncoder.updateFence(SPLIT_FENCE ? transferFence : fence);
            } else if (currentEncoder instanceof MTLComputeCommandEncoder computeEncoder) {
                // Render fence in both modes; see computeCommandEncoder().
                computeEncoder.updateFence(fence);
            }
            currentEncoder.endEncoding();
            currentEncoder = null;
        }
        renderColorAttachments = new MemorySegment[0];
        renderColorTextures = new MetalGpuTexture[0];
        renderDepthAttachment = MemorySegment.NULL;
        killedColorAttachments = new MemorySegment[0];
        killedColorTextures = new MetalGpuTexture[0];
        deferredColorStorePixels = 0;
        deferredColorStorePixelBytes = new int[0];
        renderDepthTexture = null;
        renderEncoderDeferredStore = false;
        renderEncoderDeferredColorStores = null;
    }

    /**
     * Begins a mod-private compute pass. Vanilla Blaze3D 26.2 has no compute
     * abstraction, so this API is only reachable from metallum code (Iris
     * backend). The pass owns the underlying compute encoder until
     * {@link MetalComputePass#close()}; interleaving other encoder work while
     * a pass is open is a caller error.
     */
    MetalComputePass createComputePass() {
        return createComputePass("metallum/compute");
    }

    MetalComputePass createComputePass(final String semanticPassId) {
        submitRenderPass();
        // Pending deferred clears materialize through transient render
        // encoders; they must all land BEFORE the compute encoder opens, since
        // flushing mid-pass would tear the pass's encoder out from under it.
        flushAllPendingClears();
        long contractPassToken = RenderContractRuntime.beginRenderPass(
                semanticPassId,
                PassType.COMPUTE,
                List.of(),
                null,
                null,
                new ViewportRecord(0, 0, 0, 0),
                ScissorRecord.disabled(),
                "unbound",
                List.of(),
                Map.of(
                        "backend", "metal",
                        "commandBufferSubmissionId", Long.toString(currentSubmitIndex),
                        "nativeEncoderGeneration", Long.toString(encoderGeneration + 1)
                )
        );
        MTLComputeCommandEncoder nativeEncoder = computeCommandEncoder();
        beginContractTraceGroup(contractPassToken);
        return new MetalComputePass(this, nativeEncoder, contractPassToken);
    }

    void beginContractTraceGroup(final long passToken) {
        if (passToken < 0L) {
            return;
        }
        TraceIdentity identity = RenderContractRuntime.traceIdentity(passToken);
        if (identity == null) {
            return;
        }
        MetalNativeBridge.MTLCommandBuffer_pushDebugGroup(commandBuffer().nativeHandle(), identity.debugLabel());
        contractTraceGroupDepth++;
    }

    void endContractTraceGroup() {
        if (contractTraceGroupDepth <= 0) {
            return;
        }
        MetalNativeBridge.MTLCommandBuffer_popDebugGroup(commandBuffer().nativeHandle());
        contractTraceGroupDepth--;
    }

    private void flushAllPendingClears() {
        while (!pendingColorClears.isEmpty() || !pendingDepthClears.isEmpty()) {
            MetalGpuTexture next = !pendingColorClears.isEmpty()
                    ? pendingColorClears.keySet().iterator().next()
                    : pendingDepthClears.keySet().iterator().next();
            flushPendingClear(next);
        }
    }

    boolean hasPendingClear(final MetalGpuTexture texture) {
        return pendingColorClears.containsKey(texture) || pendingDepthClears.containsKey(texture);
    }

    void endComputePass(final MTLComputeCommandEncoder encoder) {
        if (currentEncoder != encoder) {
            throw new IllegalStateException(
                    "Compute pass closed after another encoder was started; passes must be closed before other encoding"
            );
        }
        endEncoder();
    }

    /**
     * GPU mipmap generation for a texture whose levels should derive from
     * level 0 (Iris {@code setupMipmapping}/{@code glGenerateMipmap} semantics).
     * Runs on a blit encoder inside the global fence chain.
     */
    void generateMipmaps(final MetalGpuTexture texture) {
        if (texture.getMipLevels() <= 1) {
            return;
        }
        flushPendingClear(texture);
        blitCommandEncoder().generateMipmaps(texture.nativeHandle());
        if (!RenderContractRuntime.enabled()) {
            return;
        }
        ResourceIdentity identity = contractResource(texture, 0);
        RenderContractRuntime.recordTransfer(
                PassType.MIPMAP,
                "metallum/mipmap",
                ProducerType.GENERATE_MIPMAPS,
                List.of(identity),
                Map.of("mipLevels", Integer.toString(texture.getMipLevels())),
                Map.of("texture", identity.stableKey())
        );
    }

    @Override
    public @NonNull TransientMemory transientMemory() {
        return transientMemory;
    }

    @Override
    public void submit() {
        if (commandBuffer == null) {
            return;
        }

        submitRenderPass();
        endEncoder();

        int slot = (int) (currentSubmitIndex % MAX_SUBMITS_IN_FLIGHT);
        MemorySegment completedSemaphore = submitSemaphores[slot];
        InFlight toClose = inFlight[slot];
        if (toClose != null) {
            if (!awaitInFlightCompletion(toClose, 5000L)) {
                throw new IllegalStateException("5s timeout reached when waiting for Metal submit completion");
            }
            toClose.buffer.close();
            inFlight[slot] = null;
        }

        List<SubmitCallback> callbacks = List.copyOf(currentSubmitCallbacks);
        currentSubmitCallbacks.clear();
        commandBuffer.commitWithSignal(completedSemaphore);
        for (SubmitCallback callback : callbacks) {
            callback.committed.run();
        }

        inFlight[slot] = new InFlight(currentSubmitIndex, commandBuffer, completedSemaphore, callbacks);
        commandBuffer = null;
        currentSubmitIndex++;

        transientMemory.rotate();
        destroyQueue.rotate();
    }

    /**
     * Associates a frame transaction with the command buffer that currently
     * owns its encoded work. The commit callback runs only after Metal accepts
     * the command buffer; the failure callback runs if that submitted buffer
     * completes in the error state or is abandoned during shutdown.
     */
    void onCurrentSubmit(final Runnable committed, final Runnable failed) {
        if (commandBuffer == null) {
            throw new IllegalStateException("Cannot register a submit callback without an encoded command buffer");
        }
        currentSubmitCallbacks.add(new SubmitCallback(committed, failed));
    }

    MTLRenderCommandEncoder renderCommandEncoder(
            final MetalGpuTextureView[] colorTextureViews,
            @Nullable final MetalGpuTextureView depthTextureView,
            final int viewportWidth,
            final int viewportHeight,
            final int[] clearColorEnabled,
            final float[] clearColorValues,
            final boolean clearDepthEnabled,
            final double clearDepthValue,
            final String label
    ) {
        if (colorTextureViews == null || colorTextureViews.length > Math.min(
                com.mojang.blaze3d.pipeline.ColorTargetState.MAX_COLOR_TARGETS,
                device.getDeviceInfo().limits().maxColorAttachments()
        )
                || clearColorEnabled == null || clearColorValues == null
                || clearColorEnabled.length != colorTextureViews.length
                || clearColorValues.length != colorTextureViews.length * 4) {
            throw new IllegalArgumentException("Invalid Metal MRT attachment arrays");
        }

        RenderGraphTelemetry.onPassRequested(label);

        MemorySegment[] colorAttachments = new MemorySegment[colorTextureViews.length];
        for (int index = 0; index < colorTextureViews.length; index++) {
            colorAttachments[index] = colorTextureViews[index] == null
                    ? MemorySegment.NULL
                    : colorTextureViews[index].nativeHandle();
        }
        MemorySegment depthAttachment = depthTextureView == null ? MemorySegment.NULL : depthTextureView.nativeHandle();
        boolean sameAttachments = currentEncoder instanceof MTLRenderCommandEncoder
                && sameAttachmentHandles(renderColorAttachments, colorAttachments)
                && MetalPipelineSupport.sameHandle(renderDepthAttachment, depthAttachment);
        if (sameAttachments && !clearDepthEnabled && !hasClearColor(clearColorEnabled)) {
            RenderGraphTelemetry.onEncoderReused(label);
            return (MTLRenderCommandEncoder) currentEncoder;
        }

        // The incoming pass clearing the same attachment proves that the
        // outgoing store is dead bandwidth. Resolve this evidence before
        // rebuilding the encoder, then emit concrete V3 actions.
        boolean incomingClearsSameDepth = clearDepthEnabled
                && renderDepthTexture != null
                && MetalPipelineSupport.sameHandle(renderDepthAttachment, depthAttachment);
        boolean deferredDepthStore = DEFERRED_DEPTH_STORE
                && depthTextureView != null
                && ((MetalGpuTexture) depthTextureView.texture()).mtlDepthPixelFormat() != MTLPixelFormat.Invalid;
        boolean hasIncomingColorClear = hasClearColor(clearColorEnabled);
        boolean[] colorStoreKilled = new boolean[colorAttachments.length];
        if (renderPassDescriptorV3Active()) {
            for (int index = 0; index < colorAttachments.length; index++) {
                Object incomingTexture = colorTextureViews[index] == null ? null : colorTextureViews[index].texture();
                colorStoreKilled[index] = hasIncomingColorClear
                        && index < renderColorAttachments.length
                        && index < renderColorTextures.length
                        && renderColorAttachments[index] != null
                        && !MetalPipelineSupport.sameHandle(
                                renderColorAttachments[index], MemorySegment.NULL)
                        && renderColorTextures[index] == incomingTexture
                        && index < deferredColorStorePixelBytes.length
                        && deferredColorStorePixelBytes[index] > 0;
            }
        }
        endEncoder(incomingClearsSameDepth, colorStoreKilled);
        MTLRenderCommandEncoder encoder;
        if (renderPassDescriptorV3Active()) {
            // P2.3 admitted policy: live color slots defer their store
            // decision (.unknown) exactly like depth. The successor supplies
            // kill evidence while this encoder is still open; endEncoder
            // resolves every deferred slot to store-or-dontCare before
            // endEncoding. A full-clear successor overwrites the attachment
            // completely, so suppressing the predecessor's store cannot
            // change any pixel. Disable with metallum.opt.deferredColorStore
            // or when the loaded dylib lacks the resolution symbol.
            int slotCount = colorTextureViews.length;
            int[] colorLoadActions = new int[slotCount];
            int[] colorStoreActions = new int[slotCount];
            for (int index = 0; index < slotCount; index++) {
                if (colorTextureViews[index] == null) {
                    colorLoadActions[index] = 0;
                    colorStoreActions[index] = 0;
                } else {
                    colorLoadActions[index] = clearColorEnabled[index] != 0 ? 2 : 1;
                    colorStoreActions[index] = deferredColorStoreActive() ? 2 : 1;
                }
            }
            encoder = commandBuffer().makeRenderCommandEncoderV3(
                    colorAttachments,
                    depthAttachment,
                    colorLoadActions,
                    colorStoreActions,
                    clearColorValues,
                    clearDepthEnabled ? 2 : 1,
                    deferredDepthStore ? 2 : 1,
                    clearDepthValue,
                    viewportWidth,
                    viewportHeight,
                    label
            );
            renderEncoderDeferredColorStores = new boolean[slotCount];
            for (int index = 0; index < slotCount; index++) {
                renderEncoderDeferredColorStores[index] = colorStoreActions[index] == 2;
            }
        } else {
            renderEncoderDeferredColorStores = null;
            encoder = commandBuffer().makeRenderCommandEncoderV2(
                    colorAttachments,
                    depthAttachment,
                    viewportWidth,
                    viewportHeight,
                    clearColorEnabled,
                    clearColorValues,
                    clearDepthEnabled ? 1 : 0,
                    clearDepthValue,
                    label
            );
        }
        waitRenderFences(encoder);
        encoderGeneration++;
        currentEncoder = encoder;
        renderColorAttachments = colorAttachments;
        killedColorAttachments = colorAttachments.clone();
        killedColorTextures = renderColorTextures.clone();
        renderColorTextures = new MetalGpuTexture[colorTextureViews.length];
        for (int index = 0; index < colorTextureViews.length; index++) {
            renderColorTextures[index] = colorTextureViews[index] == null
                    ? null
                    : (MetalGpuTexture) colorTextureViews[index].texture();
        }
        renderDepthAttachment = depthAttachment;
        deferredColorStorePixels = (long) viewportWidth * viewportHeight;
        deferredColorStorePixelBytes = new int[colorTextureViews.length];
        for (int index = 0; index < colorTextureViews.length; index++) {
            deferredColorStorePixelBytes[index] = colorTextureViews[index] == null
                    ? 0
                    : ((MetalGpuTexture) colorTextureViews[index].texture()).pixelSize();
        }
        renderDepthTexture = depthTextureView == null ? null : (MetalGpuTexture) depthTextureView.texture();
        renderEncoderDeferredStore = deferredDepthStore;
        recordGraphTelemetry(
                label,
                viewportWidth,
                viewportHeight,
                colorTextureViews,
                clearColorEnabled,
                new boolean[colorTextureViews.length],
                clearDepthEnabled,
                deferredDepthStore
        );
        return encoder;
    }

    /**
     * Structured render-graph evidence for the TBDR compiler work. Byte
     * estimates reflect the actions ACTUALLY sent to the native side under
     * the active ABI mode, so a future policy change is visible in
     * rendergraph.json without touching the estimator.
     */
    private void recordGraphTelemetry(
            final String label,
            final int width,
            final int height,
            final MetalGpuTextureView[] colorTextureViews,
            final int[] clearColorEnabled,
            final boolean[] colorStoreKilled,
            final boolean depthClear,
            final boolean deferredDepthStore
    ) {
        try {
            int[] slotBytes = new int[colorTextureViews.length];
            boolean[] slotClear = new boolean[colorTextureViews.length];
            for (int index = 0; index < colorTextureViews.length; index++) {
                if (colorTextureViews[index] != null) {
                    slotBytes[index] = ((MetalGpuTexture) colorTextureViews[index].texture()).pixelSize();
                    slotClear[index] = index < clearColorEnabled.length && clearColorEnabled[index] != 0;
                }
            }
            int depthBytes = renderDepthTexture != null
                    ? renderDepthTexture.pixelSize()
                    : 0;
            RenderGraphTelemetry.onEncoderCreated(
                label, width, height, slotBytes, slotClear, colorStoreKilled,
                    depthBytes, depthClear, deferredDepthStore
            );
        } catch (RuntimeException ignored) {
            // Telemetry must never break encoding; a missing estimate is
            // preferable to a failed frame.
        }
    }

    private static boolean hasClearColor(final int[] clearColorEnabled) {
        for (int enabled : clearColorEnabled) {
            if (enabled != 0) {
                return true;
            }
        }
        return false;
    }

    private static int countKilled(final boolean[] flags) {
        int killed = 0;
        for (boolean flag : flags) {
            if (flag) {
                killed++;
            }
        }
        return killed;
    }

    private static int countKilled(final MemorySegment[] attachments) {
        int killed = 0;
        for (MemorySegment attachment : attachments) {
            if (attachment != null && !MetalPipelineSupport.sameHandle(attachment, MemorySegment.NULL)) {
                killed++;
            }
        }
        return killed;
    }

    private static boolean sameAttachmentHandles(final MemorySegment[] first, final MemorySegment[] second) {
        if (first.length != second.length) {
            return false;
        }
        for (int index = 0; index < first.length; index++) {
            if (!MetalPipelineSupport.sameHandle(first[index], second[index])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public @NonNull RenderPassBackend createRenderPass(final RenderPassDescriptor descriptor) {
        List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments = descriptor.colorAttachments();
        int maxColorAttachments = Math.min(
                com.mojang.blaze3d.pipeline.ColorTargetState.MAX_COLOR_TARGETS,
                device.getDeviceInfo().limits().maxColorAttachments()
        );
        if (colorAttachments.size() > maxColorAttachments) {
            throw new IllegalArgumentException(
                    "Metal render pass has " + colorAttachments.size()
                            + " color slots but the backend limit is " + maxColorAttachments
            );
        }
        RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment = descriptor.depthAttachment();
        if (colorAttachments.isEmpty() && depthAttachment == null) {
            throw new IllegalArgumentException("Metal render pass has no color or depth attachment");
        }

        GpuTextureView extentTexture = null;
        for (RenderPassDescriptor.Attachment<Optional<Vector4fc>> colorAttachment : colorAttachments) {
            if (colorAttachment != null) {
                extentTexture = colorAttachment.textureView();
                break;
            }
        }
        if (extentTexture == null && depthAttachment != null) {
            extentTexture = depthAttachment.textureView();
        }
        if (extentTexture == null) {
            throw new IllegalArgumentException("Metal render pass contains only unused color slots and no depth attachment");
        }

        MetalGpuTextureView[] colorTextureViews = new MetalGpuTextureView[colorAttachments.size()];
        Vector4fc[] clearColors = new Vector4fc[colorAttachments.size()];
        boolean hasColorClear = false;
        for (int index = 0; index < colorAttachments.size(); index++) {
            RenderPassDescriptor.Attachment<Optional<Vector4fc>> colorAttachment = colorAttachments.get(index);
            if (colorAttachment == null) {
                continue;
            }
            GpuTextureView colorTexture = colorAttachment.textureView();
            if (colorTexture.isClosed()) {
                throw new IllegalStateException("Color texture " + index + " is closed");
            }
            if ((colorTexture.texture().usage() & GpuTexture.USAGE_RENDER_ATTACHMENT) == 0) {
                throw new IllegalStateException("Color texture " + index + " must have USAGE_RENDER_ATTACHMENT");
            }
            if (colorTexture.texture().getDepthOrLayers() > 1) {
                throw new UnsupportedOperationException("Color texture " + index + " has multiple layers");
            }
            if (colorTexture.getWidth(0) != extentTexture.getWidth(0) || colorTexture.getHeight(0) != extentTexture.getHeight(0)) {
                throw new IllegalArgumentException("Color texture " + index + " dimensions do not match the first non-null attachment");
            }

            MetalGpuTexture colorTex = (MetalGpuTexture) colorTexture.texture();
            Optional<Vector4fc> colorClear = colorAttachment.clearValue();
            Vector4fc pendingColor = pendingColorClears.get(colorTex);
            if (pendingColor != null && isFullTextureView(colorTexture) && colorClear.isEmpty()) {
                pendingColorClears.remove(colorTex);
                colorClear = Optional.of(pendingColor);
            } else if (pendingColor != null && colorClear.isEmpty()) {
                flushPendingClear(colorTex);
            } else {
                pendingColorClears.remove(colorTex);
            }
            if (colorClear.isPresent()) {
                clearColors[index] = new Vector4f(colorClear.get());
                hasColorClear = true;
            }
            colorTex.markContentsDirty();
            colorTextureViews[index] = (MetalGpuTextureView) colorTexture;
        }

        GpuTextureView depthTexture = depthAttachment == null ? null : depthAttachment.textureView();
        OptionalDouble depthClear = depthAttachment == null ? OptionalDouble.empty() : depthAttachment.clearValue();
        if (depthAttachment != null) {
            if (depthTexture.isClosed()) {
                throw new IllegalStateException("Depth texture is closed");
            }
            if ((depthTexture.texture().usage() & GpuTexture.USAGE_RENDER_ATTACHMENT) == 0) {
                throw new IllegalStateException("Depth texture must have USAGE_RENDER_ATTACHMENT");
            }
            if (depthTexture.texture().getDepthOrLayers() > 1) {
                throw new UnsupportedOperationException("Depth texture has multiple layers");
            }
            if (depthTexture.getWidth(0) != extentTexture.getWidth(0) || depthTexture.getHeight(0) != extentTexture.getHeight(0)) {
                throw new IllegalArgumentException("Depth texture dimensions do not match the first non-null color attachment");
            }
            MetalGpuTexture metalDepth = (MetalGpuTexture) depthTexture.texture();
            Double pendingDepth = pendingDepthClears.get(metalDepth);
            if (pendingDepth != null && isFullTextureView(depthTexture) && depthClear.isEmpty()) {
                pendingDepthClears.remove(metalDepth);
                depthClear = OptionalDouble.of(pendingDepth);
            } else if (pendingDepth != null && depthClear.isEmpty()) {
                flushPendingClear(metalDepth);
            } else {
                pendingDepthClears.remove(metalDepth);
            }
            metalDepth.markContentsDirty();
        }

        assert descriptor.renderArea != null;
        RenderPass.RenderArea renderArea = descriptor.renderArea;
        if (renderArea == null) {
            throw new IllegalArgumentException("RenderPassDescriptor.renderArea must be provided");
        }
        long renderRight = (long) renderArea.x() + renderArea.width();
        long renderBottom = (long) renderArea.y() + renderArea.height();
        if (renderArea.x() < 0 || renderArea.y() < 0
                || renderArea.width() <= 0 || renderArea.height() <= 0
                || renderRight > extentTexture.getWidth(0)
                || renderBottom > extentTexture.getHeight(0)) {
            throw new IllegalArgumentException(
                    "Metal render area " + renderArea + " is outside attachment extent "
                            + extentTexture.getWidth(0) + "x" + extentTexture.getHeight(0)
            );
        }
        MetalRenderPass renderPass = new MetalRenderPass(
                device,
                this,
                descriptor.label(),
                colorTextureViews,
                depthTexture,
                renderArea,
                hasColorClear ? clearColors : null,
                depthClear.isPresent(),
                depthClear.isPresent()
                        ? MetalIrisDepthConvention.hardwareClear(depthClear.getAsDouble())
                        : 0.0,
                beginContractPass(descriptor, colorTextureViews, depthTexture, renderArea, hasColorClear, depthClear.isPresent())
        );
        currentRenderPass = renderPass;
        renderPass.pushDebugGroup(descriptor.label());
        return renderPass;
    }

    @Override
    public void submitRenderPass() {
        if (currentRenderPass != null) {
            currentRenderPass.materializePendingClear();
            currentRenderPass.finishTiming();
            currentRenderPass.finishContractPass();
            currentRenderPass.popDebugGroup();
            currentRenderPass = null;
        }
    }

    private long beginContractPass(
            final RenderPassDescriptor descriptor,
            final MetalGpuTextureView[] colorTextureViews,
            @Nullable final GpuTextureView depthTexture,
            final RenderPass.RenderArea renderArea,
            final boolean hasColorClear,
            final boolean hasDepthClear
    ) {
        if (!RenderContractRuntime.enabled()) {
            return -1L;
        }
        List<AttachmentBindingRecord> colors = new ArrayList<>();
        for (int slot = 0; slot < colorTextureViews.length; slot++) {
            MetalGpuTextureView view = colorTextureViews[slot];
            if (view == null) continue;
            MetalGpuTexture texture = (MetalGpuTexture) view.texture();
            colors.add(new AttachmentBindingRecord(
                    slot,
                    contractResource(texture, view.baseMipLevel()),
                    AttachmentSemantic.COLOR,
                    hasColorClear ? "clear" : "load",
                    "store",
                    true
            ));
        }
        AttachmentBindingRecord depthBinding = null;
        if (depthTexture != null) {
            MetalGpuTexture texture = (MetalGpuTexture) depthTexture.texture();
            depthBinding = new AttachmentBindingRecord(
                    0,
                    contractResource(texture, depthTexture.baseMipLevel()),
                    AttachmentSemantic.DEPTH,
                    hasDepthClear ? "clear" : "load",
                    "store",
                    true
            );
        }
        String label = descriptor.label() == null ? "" : descriptor.label().get();
        Map<String, String> metadata = new java.util.LinkedHashMap<>();
        metadata.put("backend", "metal");
        metadata.put("sourceLabel", label);
        metadata.put("validationRunId", System.getProperty("metallum.renderContract.runId", "minecraft-current"));
        metadata.put("frameId", Long.toString(RenderContractRuntime.currentFrameId()));
        metadata.put("commandBufferSubmissionId", Long.toString(currentSubmitIndex));
        metadata.put("nativeEncoderGeneration", Long.toString(encoderGeneration));
        long passToken = RenderContractRuntime.beginRenderPass(
                SemanticPassIdResolver.resolve(label, PassType.RENDER),
                PassType.RENDER,
                colors,
                depthBinding,
                null,
                new ViewportRecord(renderArea.x(), renderArea.y(), renderArea.width(), renderArea.height()),
                ScissorRecord.disabled(),
                "unbound",
                List.of(),
                metadata
        );
        beginContractTraceGroup(passToken);
        return passToken;
    }

    static ResourceIdentity contractResource(final MetalGpuTexture texture, final int mipLevel) {
        MetalAllocationIdentity allocation = texture.allocationIdentity();
        return RenderContractRuntime.identifyAllocation(
                texture.getLabel(),
                allocation.allocationId(),
                allocation.generation(),
                texture.allocationDebugId(),
                texture.getFormat().toString(),
                texture.getWidth(mipLevel),
                texture.getHeight(mipLevel),
                texture.getDepthOrLayers(),
                mipLevel,
                1,
                texture.usage()
        );
    }

    static ResourceIdentity contractResource(final MetalGpuBuffer buffer) {
        MetalAllocationIdentity allocation = buffer.allocationIdentity();
        return RenderContractRuntime.identifyAllocation(
                buffer.logicalLabel(),
                allocation.allocationId(),
                allocation.generation(),
                buffer.allocationDebugId(),
                "BUFFER",
                Math.toIntExact(Math.min(buffer.allocationSize(), Integer.MAX_VALUE)),
                1,
                1,
                0,
                1,
                buffer.usage()
        );
    }

    private void recordPresentAndMaybeCapture(
            final MetalGpuTexture source,
            final GpuTextureView textureView
    ) {
        if (!RenderContractRuntime.enabled()) {
            return;
        }
        ResourceIdentity identity = contractResource(source, textureView.baseMipLevel());
        RenderContractRuntime.recordTransfer(
                PassType.PRESENT,
                "metallum/present",
                ProducerType.PRESENT,
                List.of(identity),
                Map.of(
                        "captureRepresents", "PRE_PRESENT_DRAWABLE_CONTENT",
                        "orientation", "backend-native-texture"
                ),
                Map.of("source", identity.stableKey())
        );
        long frameId = RenderContractRuntime.currentFrameId();
        if (RenderContractRuntime.consumeFinalDrawableCapture(frameId)) {
            scheduleFinalDrawableCapture(source, frameId);
        }
    }

    private void scheduleFinalDrawableCapture(final MetalGpuTexture source, final long frameId) {
        int width = source.getWidth(0);
        int height = source.getHeight(0);
        int byteCount = Math.multiplyExact(Math.multiplyExact(width, height), source.pixelSize());
        CapturePoint point = new CapturePoint(frameId, "metallum/present", CapturePointKind.FINAL_DRAWABLE, -1);
        RenderContractRuntime.ReadbackRequest request = new RenderContractRuntime.ReadbackRequest(
                "final-drawable",
                source.allocationId(),
                source.allocationDebugId(),
                source.getFormat().toString(),
                source.pixelSize(),
                width,
                height,
                source.getDepthOrLayers(),
                0,
                1,
                source.usage(),
                AttachmentSemantic.COLOR
        );
        RenderContractRuntime.requestReadbacks(point, List.of(request), List.of());
        MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "Render-contract final drawable readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                byteCount
        );
        copyTextureToBuffer(source, buffer, 0L, () -> {
            try {
                ByteBuffer mapped = buffer.currentStorage().limit(byteCount).slice();
                byte[] bytes = new byte[byteCount];
                mapped.get(bytes);
                RenderContractRuntime.recordReadback(
                        point,
                        "final-drawable",
                        source.allocationId(),
                        source.allocationDebugId(),
                        source.getFormat().toString(),
                        source.pixelSize(),
                        width,
                        height,
                        source.getDepthOrLayers(),
                        0,
                        1,
                        source.usage(),
                        bytes,
                        List.of()
                );
            } finally {
                buffer.close();
            }
        }, 0);
    }

    void presentTextureToDrawable(final MemorySegment layer, final GpuTextureView textureView) {
        MetalGpuTexture source = (MetalGpuTexture) textureView.texture();
        recordPresentAndMaybeCapture(source, textureView);
        MetalFxManager.FrameGenerationInput frameInput = MetalFxManager.frameGenerationInput(source);
        if (frameInput != null) {
            flushPendingClear(source);
            flushPendingClear(frameInput.sceneColor());
            flushPendingClear(frameInput.nativeSceneColor());
            flushPendingClear(frameInput.depth());
            flushPendingClear(frameInput.motion());
            submitRenderPass();
            endEncoder();
            MTLCommandBuffer frameCommandBuffer = commandBuffer();
            boolean queued = MetalNativeBridge.metallum_metalfx_frame_generation_encode(
                    frameCommandBuffer.nativeHandle(),
                    device.metalDeviceHandle(),
                    layer,
                    frameInput.sceneColor().nativeHandle(),
                    frameInput.nativeSceneColor().nativeHandle(),
                    frameInput.uiColor().nativeHandle(),
                    frameInput.depth().nativeHandle(),
                    frameInput.motion().nativeHandle(),
                    frameInput.inputWidth(),
                    frameInput.inputHeight(),
                    frameInput.jitterX(),
                    frameInput.jitterY(),
                    frameInput.fieldOfView(),
                    frameInput.nearPlane(),
                    frameInput.farPlane(),
                    frameInput.aspectRatio(),
                    frameInput.deltaSeconds(),
                    frameInput.reset(),
                    fence
            );
            if (queued) {
                MetalFxManager.recordFrameGenerationQueued();
                return;
            }
            MetalFxManager.disableFrameGeneration("native frame generation encode failed");
        }
        flushPendingClear(source);
        submitRenderPass();
        endEncoder();
        MTLCommandBuffer commandBuffer = commandBuffer();
        commandBuffer.encodePresentTextureToDrawable(layer, source.nativeHandle(), fence);
    }

    boolean clearMotionInputs(
            final MetalGpuTexture objectMotion,
            final MetalGpuTexture objectValidity,
            final int inputWidth,
            final int inputHeight
    ) {
        submitRenderPass();
        endEncoder();
        objectMotion.markContentsDirty();
        objectValidity.markContentsDirty();
        return MetalNativeBridge.metallum_metalfx_clear_motion_inputs(
                commandBuffer().nativeHandle(),
                objectMotion.nativeHandle(),
                objectValidity.nativeHandle(),
                inputWidth,
                inputHeight,
                fence
        );
    }

    boolean encodeMetalFx(
            final MetalFxConfig.Mode mode,
            final MetalGpuTexture color,
            @Nullable final MetalGpuTexture depth,
            @Nullable final MetalGpuTexture motion,
            @Nullable final MetalGpuTexture reactive,
            final MetalGpuTexture output,
            final org.joml.Matrix4f currentViewProjection,
            final org.joml.Matrix4f inverseCurrentViewProjection,
            final org.joml.Matrix4f previousViewProjection,
            final org.joml.Vector2f pixelJitter,
            final int inputWidth,
            final int inputHeight,
            final boolean reset,
            final boolean depthReversed,
            final boolean preserveReactiveMask
    ) {
        flushPendingClear(color);
        if (depth != null) flushPendingClear(depth);
        submitRenderPass();
        endEncoder();
        output.markContentsDirty();
        if (motion != null) motion.markContentsDirty();
        if (reactive != null) reactive.markContentsDirty();
        return MetalNativeBridge.metallum_metalfx_encode(
                commandBuffer().nativeHandle(),
                device.metalDeviceHandle(),
                mode == MetalFxConfig.Mode.TEMPORAL ? color.nativeHandle() : color.nativeHandle(),
                depth == null ? MemorySegment.NULL : depth.nativeHandle(),
                motion == null ? MemorySegment.NULL : motion.nativeHandle(),
                reactive == null ? MemorySegment.NULL : reactive.nativeHandle(),
                output.nativeHandle(),
                currentViewProjection == null ? null : currentViewProjection.get(currentViewProjectionBuffer),
                inverseCurrentViewProjection == null ? null : inverseCurrentViewProjection.get(inverseViewProjectionBuffer),
                previousViewProjection == null ? null : previousViewProjection.get(previousViewProjectionBuffer),
                pixelJitter.x,
                pixelJitter.y,
                inputWidth,
                inputHeight,
                reset,
                depthReversed,
                preserveReactiveMask,
                fence
        );
    }

    boolean encodeMetalFxV2(
            final MetalGpuTexture color,
            final MetalGpuTexture depth,
            @Nullable final MetalGpuTexture handDepth,
            final float handReactiveBoost,
            final MetalGpuTexture cameraMotion,
            final MetalGpuTexture objectMotion,
            final MetalGpuTexture objectValidity,
            final MetalGpuTexture disocclusion,
            final MetalGpuTexture motion,
            final MetalGpuTexture reactive,
            final MetalGpuTexture output,
            final org.joml.Matrix4f currentViewProjection,
            final org.joml.Matrix4f inverseCurrentViewProjection,
            final org.joml.Matrix4f previousViewProjection,
            final org.joml.Vector2f pixelJitter,
            final int inputWidth,
            final int inputHeight,
            final boolean reset,
            final boolean depthReversed,
            final boolean preserveReactiveMask,
            final boolean emitMotionDiagnostics
    ) {
        flushPendingClear(color);
        flushPendingClear(depth);
        if (handDepth != null) flushPendingClear(handDepth);
        flushPendingClear(cameraMotion);
        flushPendingClear(objectMotion);
        flushPendingClear(objectValidity);
        flushPendingClear(disocclusion);
        flushPendingClear(motion);
        flushPendingClear(reactive);
        submitRenderPass();
        endEncoder();
        cameraMotion.markContentsDirty();
        objectMotion.markContentsDirty();
        objectValidity.markContentsDirty();
        disocclusion.markContentsDirty();
        motion.markContentsDirty();
        reactive.markContentsDirty();
        output.markContentsDirty();
        return MetalNativeBridge.metallum_metalfx_encode_v2(
                commandBuffer().nativeHandle(),
                device.metalDeviceHandle(),
                color.nativeHandle(),
                depth.nativeHandle(),
                handDepth == null ? MemorySegment.NULL : handDepth.nativeHandle(),
                cameraMotion.nativeHandle(),
                objectMotion.nativeHandle(),
                objectValidity.nativeHandle(),
                disocclusion.nativeHandle(),
                motion.nativeHandle(),
                reactive.nativeHandle(),
                output.nativeHandle(),
                currentViewProjection.get(currentViewProjectionBuffer),
                inverseCurrentViewProjection.get(inverseViewProjectionBuffer),
                previousViewProjection.get(previousViewProjectionBuffer),
                pixelJitter.x,
                pixelJitter.y,
                handReactiveBoost,
                inputWidth,
                inputHeight,
                reset,
                depthReversed,
                preserveReactiveMask,
                emitMotionDiagnostics,
                fence
        );
    }

    boolean encodeTransparencyReactiveMask(
            @Nullable final MetalGpuTexture translucent,
            @Nullable final MetalGpuTexture itemEntity,
            @Nullable final MetalGpuTexture particles,
            @Nullable final MetalGpuTexture weather,
            @Nullable final MetalGpuTexture clouds,
            final MetalGpuTexture reactive,
            final int inputWidth,
            final int inputHeight
    ) {
        if (translucent != null) flushPendingClear(translucent);
        if (itemEntity != null) flushPendingClear(itemEntity);
        if (particles != null) flushPendingClear(particles);
        if (weather != null) flushPendingClear(weather);
        if (clouds != null) flushPendingClear(clouds);
        flushPendingClear(reactive);
        submitRenderPass();
        endEncoder();
        reactive.markContentsDirty();
        return MetalNativeBridge.metallum_metalfx_mark_transparency(
                commandBuffer().nativeHandle(),
                device.metalDeviceHandle(),
                translucent == null ? MemorySegment.NULL : translucent.nativeHandle(),
                itemEntity == null ? MemorySegment.NULL : itemEntity.nativeHandle(),
                particles == null ? MemorySegment.NULL : particles.nativeHandle(),
                weather == null ? MemorySegment.NULL : weather.nativeHandle(),
                clouds == null ? MemorySegment.NULL : clouds.nativeHandle(),
                reactive.nativeHandle(),
                inputWidth,
                inputHeight
        );
    }

    boolean encodeCutoutReactiveMask(
            final MetalGpuTexture cutoutCoverage,
            final MetalGpuTexture reactive,
            final int inputWidth,
            final int inputHeight,
            final int radius
    ) {
        flushPendingClear(cutoutCoverage);
        flushPendingClear(reactive);
        submitRenderPass();
        endEncoder();
        cutoutCoverage.markContentsDirty();
        reactive.markContentsDirty();
        return MetalNativeBridge.metallum_metalfx_apply_cutout_reactive(
                commandBuffer().nativeHandle(),
                cutoutCoverage.nativeHandle(),
                reactive.nativeHandle(),
                inputWidth,
                inputHeight,
                radius,
                fence
        );
    }

    boolean encodeHandOverlayMotion(
            final MetalGpuTexture handDepth,
            final MetalGpuTexture objectMotion,
            final MetalGpuTexture objectValidity,
            final MetalGpuTexture reactive,
            final int inputWidth,
            final int inputHeight,
            final float reactiveBoost
    ) {
        flushPendingClear(handDepth);
        flushPendingClear(objectMotion);
        flushPendingClear(objectValidity);
        flushPendingClear(reactive);
        submitRenderPass();
        endEncoder();
        objectMotion.markContentsDirty();
        objectValidity.markContentsDirty();
        reactive.markContentsDirty();
        return MetalNativeBridge.metallum_metalfx_encode_hand_overlay(
                commandBuffer().nativeHandle(),
                handDepth.nativeHandle(),
                objectMotion.nativeHandle(),
                objectValidity.nativeHandle(),
                reactive.nativeHandle(),
                inputWidth,
                inputHeight,
                reactiveBoost,
                fence
        );
    }

    boolean encodeTextureCopy(final MetalGpuTexture source, final MetalGpuTexture destination, final boolean linear) {
        return encodeTextureCopy(source, destination, linear, ProducerType.COPY, "metallum/texture-copy");
    }

    boolean encodeTextureCopy(
            final MetalGpuTexture source,
            final MetalGpuTexture destination,
            final boolean linear,
            final ProducerType producerType,
            final String semanticPassId
    ) {
        flushPendingClear(source);
        submitRenderPass();
        endEncoder();
        destination.markContentsDirty();
        boolean encoded = MetalNativeBridge.metallum_encode_texture_copy(
                commandBuffer().nativeHandle(),
                source.nativeHandle(),
                destination.nativeHandle(),
                linear,
                fence
        );
        if (RenderContractRuntime.enabled()) {
            ResourceIdentity sourceIdentity = contractResource(source, 0);
            ResourceIdentity destinationIdentity = contractResource(destination, 0);
            RenderContractRuntime.recordTransfer(
                    producerType == ProducerType.RESOLVE ? PassType.RESOLVE : PassType.COPY,
                    semanticPassId,
                    producerType,
                    List.of(destinationIdentity),
                    Map.of("linear", Boolean.toString(linear), "encoded", Boolean.toString(encoded)),
                    Map.of("source", sourceIdentity.stableKey())
            );
        }
        return encoded;
    }

    @Override
    public void clearColorTexture(final @NonNull GpuTexture colorTexture, final @NonNull Vector4fc clearColor) {
        pendingColorClears.put((MetalGpuTexture) colorTexture, new Vector4f(clearColor));
    }

    @Override
    public void clearColorAndDepthTextures(final @NonNull GpuTexture colorTexture, final @NonNull Vector4fc clearColor, final @NonNull GpuTexture depthTexture, final double clearDepth) {
        MetalGpuTexture color = (MetalGpuTexture) colorTexture;
        MetalGpuTexture depth = (MetalGpuTexture) depthTexture;
        pendingColorClears.put(color, new Vector4f(clearColor));
        pendingDepthClears.put(depth, clearDepth);
    }

    @Override
    public void clearColorAndDepthTextures(
            final @NonNull GpuTexture colorTexture,
            final @NonNull Vector4fc clearColor,
            final @NonNull GpuTexture depthTexture,
            final double clearDepth,
            final int regionX,
            final int regionY,
            final int regionWidth,
            final int regionHeight
    ) {
        MetalGpuTexture color = (MetalGpuTexture) colorTexture;
        MetalGpuTexture depth = (MetalGpuTexture) depthTexture;
        Vector4fc clearColorCopy = new Vector4f(clearColor);
        if (isFullTextureRegion(color, depth, regionX, regionY, regionWidth, regionHeight)) {
            pendingColorClears.put(color, clearColorCopy);
            pendingDepthClears.put(depth, clearDepth);
            return;
        }
        color.markContentsDirty();
        depth.markContentsDirty();
        submitRenderPass();
        endEncoder();
        commandBuffer().clearColorDepthTexturesRegion(
                color.nativeHandle(),
                clearColorCopy.x(),
                clearColorCopy.y(),
                clearColorCopy.z(),
                clearColorCopy.w(),
                depth.nativeHandle(),
                MetalIrisDepthConvention.hardwareClear(clearDepth),
                regionX,
                regionY,
                regionWidth,
                regionHeight,
                fence
        );
        if (RenderContractRuntime.enabled()) {
            ResourceIdentity colorIdentity = contractResource(color, 0);
            ResourceIdentity depthIdentity = contractResource(depth, 0);
            RenderContractRuntime.recordTransfer(
                    PassType.RENDER,
                    "metallum/clear-region",
                    ProducerType.CLEAR,
                    List.of(colorIdentity, depthIdentity),
                    Map.of("region", regionX + "," + regionY + "," + regionWidth + "," + regionHeight),
                    Map.of()
            );
        }
    }

    @Override
    public void clearDepthTexture(final @NonNull GpuTexture depthTexture, final double clearDepth) {
        pendingDepthClears.put((MetalGpuTexture) depthTexture, clearDepth);
    }

    @Override
    public void writeToBuffer(final GpuBufferSlice destination, final ByteBuffer data) {
        MetalGpuBuffer buffer = (MetalGpuBuffer) destination.buffer();
        int length = data.remaining();

        if (buffer.isDynamic()) {
            orphanWrite(buffer, destination.offset(), data);
            return;
        }

        // Heap buffers have no stable native address; the transient-memory
        // staging upload memcpys from memAddress(data) and would SIGBUS the JVM.
        if (!data.isDirect()) {
            throw new IllegalArgumentException("writeToBuffer requires a direct ByteBuffer");
        }

        GpuBufferSlice staging = transientMemory.uploadStaging(data, 4L, GpuBuffer.USAGE_COPY_SRC);
        MetalGpuBuffer stagingBuffer = (MetalGpuBuffer) staging.buffer();

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromBufferToBuffer(
                stagingBuffer.nativeHandle(),
                staging.offset(),
                buffer.nativeHandle(),
                destination.offset(),
                length
        );
    }

    private void orphanWrite(final MetalGpuBuffer buffer, final long offset, final ByteBuffer data) {
        long size = buffer.allocationSize();
        MemorySegment old = buffer.nativeHandle();
        MemorySegment fresh = acquireDynamicBacking(size, buffer.resourceOptions());
        if (fresh.address() == 0L) {
            return;
        }
        ByteBuffer freshStorage = MetalNativeBridge.nativeByteBufferView(
                MetalNativeBridge.metallum_get_buffer_contents(fresh), size).order(ByteOrder.nativeOrder());

        if (offset != 0 || data.remaining() != buffer.size()) {
            ByteBuffer previous = buffer.currentStorage();
            previous.clear();
            freshStorage.duplicate().put(previous);
        }

        ByteBuffer dst = freshStorage.duplicate().order(ByteOrder.nativeOrder());
        dst.position(Math.toIntExact(offset));
        dst.put(data.duplicate());

        buffer.swapBacking(fresh, freshStorage);
        recycleDynamicBacking(old, size, buffer.resourceOptions());
    }

    private MemorySegment acquireDynamicBacking(final long size, final long resourceOptions) {
        final long key = MetalDevice.composePoolKey(size, resourceOptions);
        final java.util.ArrayDeque<MemorySegment> bucket = dynamicBackingPool.get(key);
        if (bucket != null && !bucket.isEmpty()) {
            return bucket.pop();
        }
        final MemorySegment handle = MetalNativeBridge.metallum_create_buffer(device.metalDeviceHandle(), size, resourceOptions);
        if (MetalNativeBridge.isNullHandle(handle)) {
            Metallum.LOGGER.warn("dynamic backing OOM, skipping uniform update this frame");
            return MemorySegment.NULL;
        }
        return handle;
    }

    private void recycleDynamicBacking(final MemorySegment handle, final long size, final long resourceOptions) {
        queueForDestroy(() -> {
            final long key = MetalDevice.composePoolKey(size, resourceOptions);
            java.util.ArrayDeque<MemorySegment> bucket = dynamicBackingPool.computeIfAbsent(key, k -> new java.util.ArrayDeque<>());
            if (bucket.size() < MAX_POOLED_DYNAMIC_BACKINGS_PER_SIZE) {
                bucket.push(handle);
            } else {
                MetalNativeBridge.metallum_release_object(handle);
            }
        });
    }

    @Override
    public void copyToBuffer(final GpuBufferSlice source, final GpuBufferSlice target) {
        MetalGpuBuffer sourceBuffer = (MetalGpuBuffer) source.buffer();
        MetalGpuBuffer targetBuffer = (MetalGpuBuffer) target.buffer();
        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromBufferToBuffer(
                sourceBuffer.nativeHandle(),
                source.offset(),
                targetBuffer.nativeHandle(),
                target.offset(),
                source.length()
        );
    }

    @Override
    public void writeToTexture(
            final @NonNull GpuTexture destination,
            final @NonNull ByteBuffer source,
            final int mipLevel,
            final int depthOrLayer,
            final int destX,
            final int destY,
            final int width,
            final int height
    ) {
        MetalGpuTexture metalDst = (MetalGpuTexture) destination;
        flushPendingClearForWrite(metalDst);

        // Heap buffers have no stable native address; the transient-memory
        // staging upload memcpys from memAddress(source) and would SIGBUS.
        if (!source.isDirect()) {
            throw new IllegalArgumentException("writeToTexture requires a direct ByteBuffer");
        }

        int pixelSize = metalDst.pixelSize();
        int rowBytes = width * pixelSize;
        int bytesPerImage = rowBytes * height;
        GpuBufferSlice slice = transientMemory.uploadStaging(source.duplicate().limit(bytesPerImage), pixelSize, GpuBuffer.USAGE_COPY_SRC);

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromBufferToTexture(
                ((MetalGpuBuffer) slice.buffer()).nativeHandle(),
                slice.offset(),
                metalDst.nativeHandle(),
                mipLevel,
                depthOrLayer,
                destX,
                destY,
                width,
                height,
                rowBytes,
                bytesPerImage
        );
    }

    void writeToTextureVolume(
            final MetalGpuTexture destination,
            final ByteBuffer source,
            final int mipLevel,
            final int destX,
            final int destY,
            final int destZ,
            final int width,
            final int height,
            final int depth
    ) {
        flushPendingClearForWrite(destination);
        if (!source.isDirect()) {
            throw new IllegalArgumentException("writeToTextureVolume requires a direct ByteBuffer");
        }
        int pixelSize = destination.pixelSize();
        int rowBytes = Math.multiplyExact(width, pixelSize);
        int bytesPerImage = Math.multiplyExact(rowBytes, height);
        int byteCount = Math.multiplyExact(bytesPerImage, depth);
        GpuBufferSlice slice = transientMemory.uploadStaging(
                source.duplicate().limit(byteCount), pixelSize, GpuBuffer.USAGE_COPY_SRC
        );
        blitCommandEncoder().copyFromBufferToTextureVolume(
                ((MetalGpuBuffer) slice.buffer()).nativeHandle(), slice.offset(), destination.nativeHandle(),
                mipLevel, 0, destX, destY, destZ, width, height, depth, rowBytes, bytesPerImage
        );
    }

    void copyTextureVolumeToBuffer(
            final MetalGpuTexture source,
            final MetalGpuBuffer destination,
            final long destinationOffset,
            final int mipLevel,
            final int x,
            final int y,
            final int z,
            final int width,
            final int height,
            final int depth,
            final Runnable callback
    ) {
        endEncoder();
        flushPendingClear(source);
        int rowBytes = Math.multiplyExact(width, source.pixelSize());
        int bytesPerImage = Math.multiplyExact(rowBytes, height);
        blitCommandEncoder().copyFromTextureToBufferVolume(
                source.nativeHandle(), destination.nativeHandle(), destinationOffset,
                mipLevel, 0, x, y, z, width, height, depth, rowBytes, bytesPerImage
        );
        endEncoder();
        queueForDestroy(callback);
    }

    @Override
    public void copyBufferToTexture(
            final @NonNull GpuBufferSlice source,
            final int sourceX,
            final int sourceY,
            final int sourceWidth,
            final int sourceHeight,
            final @NonNull GpuTexture destination,
            final int destinationX,
            final int destinationY,
            final int copyWidth,
            final int copyHeight,
            final int mipLevel,
            final int arrayLayer
    ) {
        MetalGpuTexture metalDst = (MetalGpuTexture) destination;
        flushPendingClearForWrite(metalDst);

        int texelSize = destination.getFormat().blockSize();
        long skipBytes = (sourceX + (long) sourceY * sourceWidth) * texelSize;
        long rowBytes = (long) sourceWidth * texelSize;

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromBufferToTexture(
                ((MetalGpuBuffer) source.buffer()).nativeHandle(),
                source.offset() + skipBytes,
                metalDst.nativeHandle(),
                mipLevel,
                arrayLayer,
                destinationX,
                destinationY,
                copyWidth,
                copyHeight,
                rowBytes,
                rowBytes * sourceHeight
        );
    }

    @Override
    public void copyTextureToBuffer(final @NonNull GpuTexture source, final @NonNull GpuBuffer destination, final long offset, final @NonNull Runnable callback, final int mipLevel) {
        copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
    }

    @Override
    public void copyTextureToBuffer(
            final @NonNull GpuTexture source,
            final @NonNull GpuBuffer destination,
            final long offset,
            final @NonNull Runnable callback,
            final int mipLevel,
            final int x,
            final int y,
            final int width,
            final int height
    ) {
        MetalGpuTexture texture = (MetalGpuTexture) source;
        // Reads a GPU-written texture: never join an upload batch (see
        // blitCommandEncoder) — a fresh encoder's fence wait covers all prior
        // encoders including the one that produced the source.
        endEncoder();
        flushPendingClear(texture);
        MetalGpuBuffer buffer = (MetalGpuBuffer) destination;
        int bytesPerPixel = texture.pixelSize();
        int rowBytes = width * bytesPerPixel;
        int bytesPerImage = rowBytes * height;

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromTextureToBuffer(
                texture.nativeHandle(),
                buffer.nativeHandle(),
                offset,
                mipLevel,
                0,
                x,
                y,
                width,
                height,
                rowBytes,
                bytesPerImage
        );

        endEncoder();
        queueForDestroy(callback);
    }

    @Override
    public void copyTextureToTexture(
            final @NonNull GpuTexture source,
            final @NonNull GpuTexture destination,
            final int mipLevel,
            final int destX,
            final int destY,
            final int sourceX,
            final int sourceY,
            final int width,
            final int height
    ) {
        MetalGpuTexture srcTexture = (MetalGpuTexture) source;
        MetalGpuTexture dstTexture = (MetalGpuTexture) destination;
        // Reads a GPU-written texture: never join an upload batch (see
        // blitCommandEncoder).
        endEncoder();
        flushPendingClear(srcTexture);
        flushPendingClearForWrite(dstTexture);
        dstTexture.markContentsDirty();
        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromTextureToTexture(
                srcTexture.nativeHandle(),
                dstTexture.nativeHandle(),
                mipLevel,
                sourceX,
                sourceY,
                destX,
                destY,
                width,
                height
        );
        endEncoder();
        if (RenderContractRuntime.enabled()) {
            ResourceIdentity sourceIdentity = contractResource(srcTexture, mipLevel);
            ResourceIdentity destinationIdentity = contractResource(dstTexture, mipLevel);
            RenderContractRuntime.recordTransfer(
                    PassType.COPY,
                    "metallum/texture-copy",
                    ProducerType.COPY,
                    List.of(destinationIdentity),
                    Map.of("width", Integer.toString(width), "height", Integer.toString(height)),
                    Map.of("source", sourceIdentity.stableKey())
            );
        }
    }

    @Override
    public @NonNull GpuFence createFence() {
        return new MetalFence(this, currentSubmitIndex);
    }

    void queueForDestroy(final Runnable destroyAction) {
        destroyQueue.add(destroyAction);
    }

    boolean awaitSubmitCompletion(final long submitIndex, final long timeoutMs) {
        long target = submitIndex;
        if (submitIndex == currentSubmitIndex) {
            if (commandBuffer != null) {
                // GL fence semantics (glClientWaitSync with the flush bit):
                // waiting on a fence whose commands were never flushed must
                // flush them, not fail. Sodium's staging buffer relies on
                // this when its ring wraps within a single frame of uploads.
                submit();
            } else {
                // Nothing has been encoded into this submit; the fence
                // covers work already handed to the GPU, and the in-order
                // queue makes the previous submit the completion witness.
                target = submitIndex - 1;
            }
        }
        for (InFlight f : inFlight) {
            if (f != null && f.index == target) {
                return awaitInFlightCompletion(f, timeoutMs);
            }
        }
        return true;
    }

    private boolean awaitInFlightCompletion(final InFlight inFlight, final long timeoutMs) {
        if (MetalNativeBridge.metallum_semaphore_wait(
                inFlight.completedSemaphore,
                Math.max(timeoutMs, 0L)
        ) != 0) {
            return false;
        }
        inFlight.complete();
        return true;
    }

    void close() {
        submitRenderPass();
        endEncoder();
        for (SubmitCallback callback : currentSubmitCallbacks) {
            callback.failed.run();
        }
        currentSubmitCallbacks.clear();
        for (int slot = 0; slot < inFlight.length; slot++) {
            InFlight f = inFlight[slot];
            if (f != null) {
                awaitInFlightCompletion(f, Long.MAX_VALUE);
                f.buffer.close();
                inFlight[slot] = null;
            }
        }
        for (int slot = 0; slot < submitSemaphores.length; slot++) {
            if (!MetalNativeBridge.isNullHandle(submitSemaphores[slot])) {
                MetalNativeBridge.metallum_release_object(submitSemaphores[slot]);
                submitSemaphores[slot] = MemorySegment.NULL;
            }
        }
        if (commandBuffer != null) {
            commandBuffer.close();
            commandBuffer = null;
        }
        transientMemory.close();
        device.queueResourceRelease(fence);
        if (SPLIT_FENCE) {
            // Drop Swift's retained reference before the Java owner releases.
            MetalNativeBridge.metallum_set_transfer_fence(MemorySegment.NULL);
            device.queueResourceRelease(transferFence);
        }
        destroyQueue.close();
        for (java.util.ArrayDeque<MemorySegment> bucket : dynamicBackingPool.values()) {
            for (MemorySegment handle : bucket) {
                MetalNativeBridge.metallum_release_object(handle);
            }
        }
        dynamicBackingPool.clear();
    }

    void waitForSubmittedGpuWork() {
        if (commandBuffer != null || currentRenderPass != null || currentEncoder != null) {
            submit();
        } else {
            endEncoder();
        }
        for (InFlight submitted : inFlight) {
            if (submitted != null) {
                awaitInFlightCompletion(submitted, Long.MAX_VALUE);
            }
        }
    }

    @Override
    public void writeTimestamp(final @NonNull GpuQueryPool pool, final int index) {
        if (pool instanceof MetalGpuQueryPool metalPool && index >= 0 && index < pool.size()) {
            metalPool.setValue(index, device.getTimestampNow());
        }
    }

    private void flushPendingClearForWrite(final MetalGpuTexture texture) {
        flushPendingClear(texture);
        texture.markContentsDirty();
    }

    void flushPendingClear(final MetalGpuTexture texture) {
        Vector4fc colorClear = pendingColorClears.remove(texture);
        Double depthClear = pendingDepthClears.remove(texture);
        if (colorClear == null && depthClear == null) {
            return;
        }

        if (texture.clearIsRedundant(colorClear, depthClear)) {
            return;
        }

        endEncoder();
        MTLRenderCommandEncoder encoder = commandBuffer().makeRenderCommandEncoder(
                colorClear != null ? texture.nativeHandle() : null,
                depthClear != null ? texture.nativeHandle() : null,
                // Metal 4 carries the render-target dimensions explicitly.
                // Metal 3 load-action clears historically ignored this
                // viewport-sized hint and cleared the full attachment, but a
                // 1x1 Metal 4 pass only initializes one pixel. Use the full
                // resource extent so delayed clears remain deterministic when
                // no later full-frame writer happens to mask the bug.
                texture.getWidth(0), texture.getHeight(0),
                colorClear != null ? 1 : 0,
                colorClear != null ? colorClear.x() : 0.0F,
                colorClear != null ? colorClear.y() : 0.0F,
                colorClear != null ? colorClear.z() : 0.0F,
                colorClear != null ? colorClear.w() : 0.0F,
                depthClear != null ? 1 : 0,
                depthClear != null ? MetalIrisDepthConvention.hardwareClear(depthClear) : 1.0
        );
        waitRenderFences(encoder);
        encoderGeneration++;
        currentEncoder = encoder;
        texture.recordMaterializedClear(colorClear, depthClear);
    }

    private static boolean isFullTextureView(final GpuTextureView textureView) {
        return textureView.baseMipLevel() == 0
                && textureView.mipLevels() >= textureView.texture().getMipLevels()
                && textureView.texture().getDepthOrLayers() == 1;
    }

    private static boolean isFullTextureRegion(
            final MetalGpuTexture color,
            final MetalGpuTexture depth,
            final int x,
            final int y,
            final int width,
            final int height
    ) {
        return x == 0
                && y == 0
                && width == color.getWidth(0)
                && height == color.getHeight(0)
                && width == depth.getWidth(0)
                && height == depth.getHeight(0);
    }

    private static final class InFlight {
        private final long index;
        private final MTLCommandBuffer buffer;
        private final MemorySegment completedSemaphore;
        private final List<SubmitCallback> callbacks;
        private boolean completionHandled;

        private InFlight(
                final long index,
                final MTLCommandBuffer buffer,
                final MemorySegment completedSemaphore,
                final List<SubmitCallback> callbacks
        ) {
            this.index = index;
            this.buffer = buffer;
            this.completedSemaphore = completedSemaphore;
            this.callbacks = callbacks;
        }

        private void complete() {
            if (completionHandled) {
                return;
            }
            completionHandled = true;
            MetalGpuTimingRecorder.record(index, buffer.gpuStartTime(), buffer.gpuEndTime());
            if (!buffer.completedSuccessfully()) {
                for (SubmitCallback callback : callbacks) {
                    callback.failed.run();
                }
            }
        }
    }

    private record SubmitCallback(Runnable committed, Runnable failed) {
    }
}
