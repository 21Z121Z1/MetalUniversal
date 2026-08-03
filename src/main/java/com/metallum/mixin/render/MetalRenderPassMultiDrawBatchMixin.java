package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalGpuBuffer;
import com.metallum.client.metal.render.MetalTerrainIcbScope;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.bridge.MetalTerrainIcbBridge;
import com.metallum.client.metal.render.mtl.MTLIndexType;
import com.metallum.client.metal.render.mtl.MTLPrimitiveType;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.metallum.client.metal.render.mtl.MetalCommandPacketTelemetry;
import com.metallum.client.metal.render.mtl.MetalHotPathTelemetry;
import com.metallum.client.metal.render.mtl.MetalRenderStateFlushable;
import com.metallum.client.validation.contract.ProducerType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.foreign.MemorySegment;
import java.nio.IntBuffer;
import java.util.Map;

/**
 * Converts Mojang's interleaved indexed multi-draw records into one native
 * batch call. A separate default-off pilot may encode sufficiently large
 * Sodium terrain batches through a Metal 3 indirect command buffer.
 */
@Mixin(targets = "com.metallum.client.metal.render.MetalRenderPass")
public abstract class MetalRenderPassMultiDrawBatchMixin {
    private static final boolean ENABLED = !"false".equalsIgnoreCase(
            System.getProperty("metallum.opt.nativeMultiDrawBatch", "true")
    );
    private static final boolean NO_TRACE_FAST_PATH = !"false".equalsIgnoreCase(
            System.getProperty("metallum.opt.noTraceDrawFastPath", "true")
    );
    private static final int THRESHOLD = Math.max(
            2,
            Integer.getInteger("metallum.opt.nativeMultiDrawBatchThreshold", 4)
    );
    private static final boolean TERRAIN_ICB_ENABLED = Boolean.getBoolean(
            "metallum.opt.terrainIcbPilot"
    );
    private static final boolean METAL4_REQUESTED = Boolean.getBoolean(
            "metallum.opt.metal4"
    );
    private static final int TERRAIN_ICB_THRESHOLD = Math.max(
            16,
            Integer.getInteger("metallum.opt.terrainIcbMinDraws", 64)
    );

    @Shadow
    @Final
    private long contractPassToken;

    @Shadow
    @Nullable
    private GpuBuffer indexBuffer;

    @Shadow
    private MTLIndexType indexType;

    @Invoker("renderEncoder")
    protected abstract MTLRenderCommandEncoder metallum$invokeRenderEncoder();

    @Invoker("bindDrawState")
    protected abstract void metallum$invokeBindDrawState(MTLRenderCommandEncoder encoder);

    @Invoker("primitiveTopology")
    protected abstract MTLPrimitiveType metallum$invokePrimitiveTopology();

    @Invoker("drawIndexedNative")
    protected abstract void metallum$invokeDrawIndexedNative(
            MTLRenderCommandEncoder encoder,
            MetalGpuBuffer nativeIndexBuffer,
            int firstIndex,
            int indexCount,
            int baseVertex,
            int instanceCount,
            MTLIndexType indexType,
            int baseInstance
    );

    @Invoker("recordProducer")
    protected abstract void metallum$invokeRecordProducer(
            ProducerType type,
            Map<String, String> parameters
    );

    @Inject(
            method = "multiDrawIndexed(Ljava/nio/IntBuffer;III)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void metallum$batchInterleavedIndexedDraws(
            final IntBuffer drawParameters,
            final int instanceCount,
            final int firstInstance,
            final int drawCount,
            final CallbackInfo ci
    ) {
        final boolean noTrace = NO_TRACE_FAST_PATH && this.contractPassToken < 0L;
        if (drawCount < 0
                || drawCount > Integer.MAX_VALUE / 3
                || drawParameters.limit() < drawCount * 3
                || !(this.indexBuffer instanceof MetalGpuBuffer nativeIndexBuffer)) {
            return;
        }

        if (drawCount == 0) {
            if (noTrace) {
                ci.cancel();
            }
            return;
        }

        MTLPrimitiveType primitiveType = this.metallum$invokePrimitiveTopology();
        boolean batchEligible = ENABLED
                && primitiveType != MTLPrimitiveType.TriangleFan
                && drawCount >= THRESHOLD;

        MetalMultiDrawScratch scratch = null;
        int emitted = 0;
        if (batchEligible) {
            scratch = MetalMultiDrawScratch.CURRENT.get();
            scratch.ensureCapacity(drawCount);
            for (int draw = 0; draw < drawCount; draw++) {
                int base = draw * 3;
                int firstIndex = drawParameters.get(base);
                int indexCount = drawParameters.get(base + 1);
                int baseVertex = drawParameters.get(base + 2);
                if (indexCount <= 0) {
                    continue;
                }
                if (firstIndex < 0) {
                    batchEligible = false;
                    break;
                }
                long firstIndexOffset = (long) firstIndex * this.indexType.bytes;
                scratch.put(emitted++, firstIndexOffset, indexCount, baseVertex);
            }
            if (emitted < THRESHOLD) {
                batchEligible = false;
            }
        }

        // Exact validation metadata remains owned by the target method. Avoid
        // opening an encoder merely to discover that this mixin will fall back.
        if (!batchEligible && !noTrace) {
            return;
        }

        MTLRenderCommandEncoder encoder = this.metallum$invokeRenderEncoder();
        this.metallum$invokeBindDrawState(encoder);

        if (batchEligible) {
            MemorySegment nativeHandle = ((MetalGpuBufferNativeHandleAccessor) nativeIndexBuffer)
                    .metallum$invokeNativeHandle();
            ((MetalRenderStateFlushable) encoder).metallum$flushPendingRenderState();

            boolean encodedByIcb = false;
            boolean attemptIcb = TERRAIN_ICB_ENABLED
                    && !METAL4_REQUESTED
                    && MetalTerrainIcbScope.active()
                    && instanceCount > 0
                    && emitted >= TERRAIN_ICB_THRESHOLD
                    && MetalTerrainIcbBridge.available();
            if (attemptIcb) {
                MetalCommandPacketTelemetry.terrainIcbAttempt(emitted);
                encodedByIcb = MetalTerrainIcbBridge.encodeIndexedBatch(
                        encoder.handle(),
                        primitiveType.value,
                        this.indexType.value,
                        nativeHandle,
                        scratch.firstIndexOffsets(),
                        scratch.indexCounts(),
                        scratch.vertexOffsets(),
                        emitted,
                        instanceCount,
                        firstInstance
                );
                if (encodedByIcb) {
                    MetalCommandPacketTelemetry.terrainIcbAccepted();
                } else {
                    MetalCommandPacketTelemetry.terrainIcbFallback();
                }
            }

            if (!encodedByIcb) {
                MetalNativeBridge.MTLRenderCommandEncoder_multiDrawIndexed(
                        encoder.handle(),
                        primitiveType.value,
                        this.indexType.value,
                        nativeHandle,
                        scratch.firstIndexOffsets(),
                        scratch.indexCounts(),
                        scratch.vertexOffsets(),
                        emitted,
                        instanceCount,
                        firstInstance
                );
            }
            MetalHotPathTelemetry.recordNativeMultiDrawBatch(emitted);
        } else {
            for (int draw = 0; draw < drawCount; draw++) {
                int base = draw * 3;
                int firstIndex = drawParameters.get(base);
                int indexCount = drawParameters.get(base + 1);
                int baseVertex = drawParameters.get(base + 2);
                if (indexCount > 0) {
                    this.metallum$invokeDrawIndexedNative(
                            encoder,
                            nativeIndexBuffer,
                            firstIndex,
                            indexCount,
                            baseVertex,
                            instanceCount,
                            this.indexType,
                            firstInstance
                    );
                }
            }
        }

        if (!noTrace) {
            this.metallum$invokeRecordProducer(
                    ProducerType.MULTI_DRAW,
                    Map.of(
                            "drawCount", Integer.toString(drawCount),
                            "instanceCount", Integer.toString(instanceCount)
                    )
            );
        }
        ci.cancel();
    }
}
