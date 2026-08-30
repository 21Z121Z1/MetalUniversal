package com.metallum.client.metal.render;

/**
 * Producer-owned lifetime for the optional terrain ICB path.
 *
 * <p>The Sodium mixin implements this only on its real
 * {@code VKIndirectDrawBatch}.  Keeping the owner on that batch bounds the
 * residency to the batch's existing clear/delete lifetime; there is no
 * process-wide cache or generic indirect-draw ownership.</p>
 */
public interface TerrainIcbProducer {
    TerrainIcbOwner metallum$terrainIcbOwner();

    void metallum$closeTerrainIcbOwner();
}
