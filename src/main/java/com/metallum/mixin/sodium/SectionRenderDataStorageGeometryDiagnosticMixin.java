package com.metallum.mixin.sodium;

import com.metallum.Metallum;
import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferSegment;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** Records Sodium's section allocation metadata without changing it. */
@Mixin(SectionRenderDataStorage.class)
public abstract class SectionRenderDataStorageGeometryDiagnosticMixin {
    @Unique
    private static final boolean METALLUM$ENABLED = Boolean.parseBoolean(
            System.getProperty("metallum.opt.sodiumSectionGeometryDiagnostic", "false")
    );
    @Unique
    private static final int METALLUM$LIMIT = Integer.getInteger(
            "metallum.opt.sodiumSectionGeometryDiagnosticAllocationLimit", 96
    );
    @Unique
    private static final AtomicInteger METALLUM$COUNT = new AtomicInteger();

    @Inject(method = "setVertexData", at = @At("RETURN"), remap = false)
    private void metallum$traceVertexData(
            final int sectionIndex,
            final GlBufferSegment allocation,
            final int[] vertexCounts,
            final CallbackInfo ci
    ) {
        if (!METALLUM$ENABLED || METALLUM$COUNT.getAndIncrement() >= METALLUM$LIMIT) {
            return;
        }

        long pMeshData = ((SectionRenderDataStorage) (Object) this).getDataPointer(sectionIndex);
        Metallum.LOGGER.warn(
                "[metallum] Sodium vertex allocation storage={} sectionIndex={} "
                        + "segment={} offset={} length={} pMeshData=0x{} baseVertex={} "
                        + "counts={}",
                System.identityHashCode(this),
                sectionIndex,
                allocation == null ? "null" : Integer.toHexString(System.identityHashCode(allocation)),
                allocation == null ? -1L : allocation.getOffset(),
                allocation == null ? -1L : allocation.getLength(),
                Long.toHexString(pMeshData),
                pMeshData == 0L ? -1L : SectionRenderDataUnsafe.getBaseVertex(pMeshData),
                Arrays.toString(vertexCounts)
        );
    }

    @Inject(method = "setIndexData", at = @At("RETURN"), remap = false)
    private void metallum$traceIndexData(
            final int sectionIndex,
            final GlBufferSegment allocation,
            final CallbackInfo ci
    ) {
        if (!METALLUM$ENABLED || METALLUM$COUNT.getAndIncrement() >= METALLUM$LIMIT) {
            return;
        }

        long pMeshData = ((SectionRenderDataStorage) (Object) this).getDataPointer(sectionIndex);
        Metallum.LOGGER.warn(
                "[metallum] Sodium index allocation storage={} sectionIndex={} "
                        + "segment={} offset={} length={} pMeshData=0x{} baseElement={}",
                System.identityHashCode(this),
                sectionIndex,
                allocation == null ? "null" : Integer.toHexString(System.identityHashCode(allocation)),
                allocation == null ? -1L : allocation.getOffset(),
                allocation == null ? -1L : allocation.getLength(),
                Long.toHexString(pMeshData),
                pMeshData == 0L ? -1L : SectionRenderDataUnsafe.getBaseElement(pMeshData)
        );
    }

    @Inject(method = "onBufferResized", at = @At("RETURN"), remap = false)
    private void metallum$traceVertexBufferResize(final CallbackInfo ci) {
        if (METALLUM$ENABLED) {
            Metallum.LOGGER.warn(
                    "[metallum] Sodium vertex arena resize propagated storage={}",
                    System.identityHashCode(this)
            );
        }
    }

    @Inject(method = "onIndexBufferResized", at = @At("RETURN"), remap = false)
    private void metallum$traceIndexBufferResize(final CallbackInfo ci) {
        if (METALLUM$ENABLED) {
            Metallum.LOGGER.warn(
                    "[metallum] Sodium index arena resize propagated storage={}",
                    System.identityHashCode(this)
            );
        }
    }
}
