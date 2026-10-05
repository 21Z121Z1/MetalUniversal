package com.metallum.client.chunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.world.level.chunk.DataLayer;

public final class LightSnapshotVerifier {
    private LightSnapshotVerifier() {}

    public static void requireEquivalent(
            LayeredLightSnapshotMap candidate,
            Long2ObjectOpenHashMap<DataLayer> vanilla
    ) {
        for (Long2ObjectMap.Entry<DataLayer> entry : vanilla.long2ObjectEntrySet()) {
            if (candidate.get(entry.getLongKey()) != entry.getValue()) {
                mismatch(entry.getLongKey());
            }
        }
        for (LongIterator iterator = candidate.base().keySet().iterator(); iterator.hasNext();) {
            long key = iterator.nextLong();
            if (!candidate.delta().containsKey(key)
                    && candidate.get(key) != vanilla.get(key)) {
                mismatch(key);
            }
        }
        for (LongIterator iterator = candidate.delta().keySet().iterator(); iterator.hasNext();) {
            long key = iterator.nextLong();
            if (candidate.get(key) != vanilla.get(key)) {
                mismatch(key);
            }
        }
    }

    private static void mismatch(long key) {
        LightSnapshotTelemetry.mismatch();
        throw new IllegalStateException("Light snapshot verification diverged at section " + key);
    }
}
