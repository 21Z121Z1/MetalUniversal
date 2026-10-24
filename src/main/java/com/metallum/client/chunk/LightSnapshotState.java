package com.metallum.client.chunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.chunk.DataLayer;

public interface LightSnapshotState {
    LayeredLightSnapshotMap metallum$buildLightSnapshot();

    Long2ObjectOpenHashMap<DataLayer> metallum$rawLightMap();
}
