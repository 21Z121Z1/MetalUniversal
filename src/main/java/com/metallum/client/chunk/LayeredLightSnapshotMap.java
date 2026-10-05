package com.metallum.client.chunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.chunk.DataLayer;

/**
 * Read-only published light snapshot backed by an immutable base plus an
 * immutable cumulative delta. Updating maps never use this type.
 */
public final class LayeredLightSnapshotMap extends Long2ObjectOpenHashMap<DataLayer> {
    public static final DataLayer REMOVED = new DataLayer();

    private final Long2ObjectOpenHashMap<DataLayer> base;
    private final Long2ObjectOpenHashMap<DataLayer> delta;

    public LayeredLightSnapshotMap(
            Long2ObjectOpenHashMap<DataLayer> base,
            Long2ObjectOpenHashMap<DataLayer> delta
    ) {
        super(0);
        this.base = base;
        this.delta = delta;
    }

    @Override
    public DataLayer get(long key) {
        DataLayer changed = this.delta.get(key);
        if (changed != null) {
            return changed == REMOVED ? null : changed;
        }
        return this.base.get(key);
    }

    @Override
    public boolean containsKey(long key) {
        return get(key) != null;
    }

    Long2ObjectOpenHashMap<DataLayer> base() {
        return this.base;
    }

    Long2ObjectOpenHashMap<DataLayer> delta() {
        return this.delta;
    }

    @Override
    public DataLayer put(long key, DataLayer value) {
        throw new UnsupportedOperationException("Published light snapshot is read-only");
    }

    @Override
    public DataLayer remove(long key) {
        throw new UnsupportedOperationException("Published light snapshot is read-only");
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("Published light snapshot is read-only");
    }

    @Override
    public LayeredLightSnapshotMap clone() {
        throw new UnsupportedOperationException("Published light snapshot is not cloned");
    }
}
