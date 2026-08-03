package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalGpuBuffer;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLIndexType;
import com.metallum.client.metal.render.mtl.MTLPrimitiveType;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.metallum.client.metal.render.mtl.MetalHotPathTelemetry;
import com.metallum.client.validation.contract.ProducerType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import org.jspecify.annotations.Nullable;
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
 * Converts Mojang's interleaved indexed multi-draw records into one existing
 * native Metal multi-draw call. This removes one Java/FFM/draw crossing per
 * command while preserving one pipeline, descriptor and attachment state.
 */
@Mixin(targets = "com.metallum.client.metal.render.MetalRenderPass")
public abstract class MetalRenderPassMultiDrawBatchMixin {
    private static final boolean ENABLED = !"false".equalsIgnoreCase(
            System.getProperty("metallum.opt.nativeMultiDrawBatch", "true")
    );
    private static final int THRESHOLD = Math.max(
            2,
            Integer.getInteger("metallum.opt.nativeMultiDrawBatchThreshold", 4)
    );

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

    @Invoker("recordProducer")
    protected abstract void metallum$invokeRecordProducer(ProducerType type, Map<String, String> parameters);

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
        if (!ENABLED
                || drawCount < THRESHOLD
                || drawCount > Integer.MAX_VALUE / 3
                || drawParameters.capacity() < drawCount * 3
                || !(this.indexBuffer instanceof MetalGpuBuffer)) {
            return;
        }

        MTLPrimitiveType primitiveType = this.metallum$invokePrimitiveTopology();
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            return;
        }

        MetalMultiDrawScratch scratch = MetalMultiDrawScratch.CURRENT.get();
        scratch.ensureCapacity(drawCount);
        int emitted = 0;
        for (int draw = 0; draw < drawCount; draw++) {
            int base = draw * 3;
            int firstIndex = drawParameters.get(base);
            int indexCount = drawParameters.get(base + 1);
            int baseVertex = drawParameters.get(base + 2);
            if (indexCount <= 0) {
                continue;
            }
            // The old loop would pass a negative byte offset to Metal. Keep the
            // conservative path so existing validation/error behavior is not
            // silently replaced by a different failure mode.
            if (firstIndex < 0) {
                return;
            }
            long firstIndexOffset = (long) firstIndex * this.indexType.bytes;
            scratch.put(emitted++, firstIndexOffset, indexCount, baseVertex);
        }
        if (emitted < THRESHOLD) {
            return;
        }

        MTLRenderCommandEncoder encoder = this.metallum$invokeRenderEncoder();
        this.metallum$invokeBindDrawState(encoder);
        MemorySegment nativeIndexBuffer = ((MetalGpuBufferNativeHandleAccessor) this.indexBuffer)
                .metallum$invokeNativeHandle();
        MetalNativeBridge.MTLRenderCommandEncoder_multiDrawIndexed(
                encoder.handle(),
                primitiveType.value,
                this.indexType.value,
                nativeIndexBuffer,
                scratch.firstIndexOffsets(),
                scratch.indexCounts(),
                scratch.vertexOffsets(),
                emitted,
                instanceCount,
                firstInstance
        );
        MetalHotPathTelemetry.recordNativeMultiDrawBatch(emitted);
        this.metallum$invokeRecordProducer(
                ProducerType.MULTI_DRAW,
                Map.of(
                        "drawCount", Integer.toString(drawCount),
                        "instanceCount", Integer.toString(instanceCount),
                        "nativeBatchCount", Integer.toString(emitted)
                )
        );
        ci.cancel();
    }
}
