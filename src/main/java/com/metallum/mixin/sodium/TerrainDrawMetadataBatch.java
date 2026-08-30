package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.TerrainDrawMetadataStore;

/** Narrow batch seam used by the Sodium producer and the draw snapshot. */
public interface TerrainDrawMetadataBatch {
    TerrainDrawMetadataStore metallum$terrainDrawMetadata();

    void metallum$setTerrainDrawMetadata(TerrainDrawMetadataStore store);
}
