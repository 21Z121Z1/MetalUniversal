package com.metallum.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortItemsProvider;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded correctness A/B for the Sodium visible-list/cache boundary.
 *
 * <p>Sodium normally invalidates a region's cached multi-draw batch when the
 * visible section set or relative camera section changes. This diagnostic
 * switch clears those cached batches after every visible-list preparation so a
 * real-client run can distinguish stale batch reuse from a deeper Metal
 * buffer/allocation problem. It is deliberately dormant unless explicitly
 * enabled with {@code metallum.opt.sodiumAlwaysRebuildTerrainBatches=true}.
 */
@Mixin(ChunkRenderList.class)
public abstract class ChunkRenderListTerrainBatchDiagnosticMixin {
    private static final boolean FORCE_REBUILD = Boolean.parseBoolean(
            System.getProperty("metallum.opt.sodiumAlwaysRebuildTerrainBatches", "false")
    );
    private static final AtomicInteger LOG_COUNT = new AtomicInteger();

    @Inject(method = "prepareForRender", at = @At("RETURN"), remap = false)
    private void metallum$forceTerrainBatchRebuild(
            final SectionPos cameraSection,
            final SortItemsProvider sortItemsProvider,
            final CallbackInfo ci
    ) {
        if (!FORCE_REBUILD) {
            return;
        }

        ChunkRenderList list = (ChunkRenderList) (Object) this;
        list.getRegion().clearAllCachedBatches();

        int logId = LOG_COUNT.getAndIncrement();
        if (logId < 8) {
            com.metallum.Metallum.LOGGER.warn(
                    "[metallum] Sodium terrain batch rebuild diagnostic #{} region=({}, {}, {}) "
                            + "cameraSection=({}, {}, {}) visibleGeometrySections={}",
                    logId,
                    list.getRegion().getChunkX(),
                    list.getRegion().getChunkY(),
                    list.getRegion().getChunkZ(),
                    cameraSection.getX(),
                    cameraSection.getY(),
                    cameraSection.getZ(),
                    list.getSectionsWithGeometryCount()
            );
        }
    }
}
