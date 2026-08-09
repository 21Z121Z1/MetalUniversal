package com.metallum.mixin.sodium;

import com.metallum.Metallum;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.iterator.ByteIterator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded tracing for the Sodium section-to-draw-command handoff.
 *
 * <p>This is intentionally observational. It records the section data pointer
 * visible to {@code fillCommandBuffer}, the decoded allocation metadata, and
 * the same pointer when Sodium enters its local/shared command helpers. No
 * command values are changed.</p>
 */
@Mixin(DefaultChunkRenderer.class)
public abstract class DefaultChunkRendererSectionGeometryDiagnosticMixin {
    @Unique
    private static final boolean METALLUM$ENABLED = Boolean.parseBoolean(
            System.getProperty("metallum.opt.sodiumSectionGeometryDiagnostic", "false")
    );
    @Unique
    private static final int METALLUM$FILL_LIMIT = Integer.getInteger(
            "metallum.opt.sodiumSectionGeometryDiagnosticFillLimit", 16
    );
    @Unique
    private static final int METALLUM$COMMAND_LIMIT = Integer.getInteger(
            "metallum.opt.sodiumSectionGeometryDiagnosticCommandLimit", 128
    );
    @Unique
    private static final int METALLUM$ALLOCATION_LIMIT = Integer.getInteger(
            "metallum.opt.sodiumSectionGeometryDiagnosticAllocationLimit", 96
    );
    @Unique
    private static final AtomicInteger METALLUM$FILL_COUNT = new AtomicInteger();
    @Unique
    private static final AtomicInteger METALLUM$COMMAND_COUNT = new AtomicInteger();
    @Unique
    private static final ThreadLocal<Metallum$FillContext> METALLUM$FILL_CONTEXT = new ThreadLocal<>();

    @Inject(method = "fillCommandBuffer", at = @At("HEAD"), remap = false)
    private static void metallum$traceFillCommandBuffer(
            final MultiDrawBatch batch,
            final RenderRegion region,
            final SectionRenderDataStorage storage,
            final ChunkRenderList renderList,
            final CameraTransform cameraTransform,
            final TerrainRenderPass pass,
            final boolean flag0,
            final boolean flag1,
            final CallbackInfo ci
    ) {
        if (!METALLUM$ENABLED) {
            return;
        }

        int fillId = METALLUM$FILL_COUNT.getAndIncrement();
        if (fillId >= METALLUM$FILL_LIMIT) {
            return;
        }

        Metallum$FillContext context = new Metallum$FillContext(
                fillId,
                region.getChunkX(),
                region.getChunkY(),
                region.getChunkZ(),
                System.identityHashCode(storage),
                pass.isTranslucent()
        );
        METALLUM$FILL_CONTEXT.set(context);

        Metallum.LOGGER.warn(
                "[metallum] Sodium section fill #{} region=({}, {}, {}) storage={} "
                        + "translucent={} listGeometry={} batchClass={} batchSize={} batchFilled={} flags=({}, {})",
                fillId,
                context.regionX,
                context.regionY,
                context.regionZ,
                context.storageId,
                context.translucent,
                renderList.getSectionsWithGeometryCount(),
                batch.getClass().getSimpleName(),
                batch.size,
                batch.isFilled,
                flag0,
                flag1
        );

        ByteIterator sections = renderList.sectionsWithGeometryIterator(pass.isTranslucent());
        int loggedSections = 0;
        while (sections.hasNext() && loggedSections < METALLUM$COMMAND_LIMIT) {
            int sectionIndex = sections.nextByteAsInt();
            long pMeshData = storage.getDataPointer(sectionIndex);
            metallum$logSectionData(
                    "visible",
                    context,
                    sectionIndex,
                    pMeshData,
                    -1,
                    -1
            );
            loggedSections++;
        }
    }

    @Inject(method = "fillCommandBuffer", at = @At("RETURN"), remap = false)
    private static void metallum$clearFillCommandBufferTrace(
            final MultiDrawBatch batch,
            final RenderRegion region,
            final SectionRenderDataStorage storage,
            final ChunkRenderList renderList,
            final CameraTransform cameraTransform,
            final TerrainRenderPass pass,
            final boolean flag0,
            final boolean flag1,
            final CallbackInfo ci
    ) {
        if (METALLUM$ENABLED) {
            METALLUM$FILL_CONTEXT.remove();
        }
    }

    @Inject(method = "addLocalIndexedDrawCommands", at = @At("HEAD"), remap = false)
    private static void metallum$traceLocalCommands(
            final MultiDrawBatch batch,
            final long pMeshData,
            final int mask,
            final CallbackInfo ci
    ) {
        metallum$traceCommand("local", batch, pMeshData, mask);
    }

    @Inject(method = "addSharedIndexedDrawCommands", at = @At("HEAD"), remap = false)
    private static void metallum$traceSharedCommands(
            final MultiDrawBatch batch,
            final long pMeshData,
            final int mask,
            final CallbackInfo ci
    ) {
        metallum$traceCommand("shared", batch, pMeshData, mask);
    }

    @Unique
    private static void metallum$traceCommand(
            final String kind,
            final MultiDrawBatch batch,
            final long pMeshData,
            final int mask
    ) {
        if (!METALLUM$ENABLED) {
            return;
        }

        Metallum$FillContext context = METALLUM$FILL_CONTEXT.get();
        if (context == null) {
            return;
        }

        int commandId = METALLUM$COMMAND_COUNT.getAndIncrement();
        if (commandId >= METALLUM$COMMAND_LIMIT) {
            return;
        }

        metallum$logSectionData(kind, context, -1, pMeshData, mask, batch.size);
    }

    @Unique
    private static void metallum$logSectionData(
            final String source,
            final Metallum$FillContext context,
            final int sectionIndex,
            final long pMeshData,
            final int mask,
            final int batchSize
    ) {
        if (pMeshData == 0L) {
            Metallum.LOGGER.warn(
                    "[metallum] Sodium section {} fill#{} region=({}, {}, {}) storage={} "
                            + "sectionIndex={} pMeshData=0 mask={} batchSize={}",
                    source,
                    context.fillId,
                    context.regionX,
                    context.regionY,
                    context.regionZ,
                    context.storageId,
                    sectionIndex,
                    mask,
                    batchSize
            );
            return;
        }

        long[] vertexCounts = new long[6];
        for (int facing = 0; facing < vertexCounts.length; facing++) {
            vertexCounts[facing] = SectionRenderDataUnsafe.getVertexCount(pMeshData, facing);
        }

        Metallum.LOGGER.warn(
                "[metallum] Sodium section {} fill#{} region=({}, {}, {}) storage={} "
                        + "sectionIndex={} pMeshData=0x{} localIndex={} baseVertex={} baseElement={} "
                        + "sliceMask=0x{} mask=0x{} batchSize={} vertexCounts={}",
                source,
                context.fillId,
                context.regionX,
                context.regionY,
                context.regionZ,
                context.storageId,
                sectionIndex,
                Long.toHexString(pMeshData),
                SectionRenderDataUnsafe.isLocalIndex(pMeshData),
                SectionRenderDataUnsafe.getBaseVertex(pMeshData),
                SectionRenderDataUnsafe.getBaseElement(pMeshData),
                Integer.toHexString(SectionRenderDataUnsafe.getSliceMask(pMeshData)),
                Integer.toHexString(mask),
                batchSize,
                Arrays.toString(vertexCounts)
        );
    }

    @Unique
    private static final class Metallum$FillContext {
        private final int fillId;
        private final int regionX;
        private final int regionY;
        private final int regionZ;
        private final int storageId;
        private final boolean translucent;

        private Metallum$FillContext(
                final int fillId,
                final int regionX,
                final int regionY,
                final int regionZ,
                final int storageId,
                final boolean translucent
        ) {
            this.fillId = fillId;
            this.regionX = regionX;
            this.regionY = regionY;
            this.regionZ = regionZ;
            this.storageId = storageId;
            this.translucent = translucent;
        }
    }
}
