package com.metallum.client.storage;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSaveSkipPolicyTest {
    @Test
    void acceptsIdenticalChunkData() {
        CompoundTag stored = sample(10L, 3);
        CompoundTag proposed = sample(10L, 3);

        assertTrue(ChunkSaveSkipPolicy.equivalentExceptLastUpdate(stored, proposed));
    }

    @Test
    void acceptsLastUpdateAsTheOnlyDifference() {
        CompoundTag stored = sample(10L, 3);
        CompoundTag proposed = sample(99L, 3);

        assertTrue(ChunkSaveSkipPolicy.equivalentExceptLastUpdate(stored, proposed));
    }

    @Test
    void rejectsAnyOtherRootOrNestedDifference() {
        assertFalse(ChunkSaveSkipPolicy.equivalentExceptLastUpdate(sample(10L, 3), sample(99L, 4)));

        CompoundTag stored = sample(10L, 3);
        CompoundTag proposed = sample(99L, 3);
        proposed.getCompoundOrEmpty("Heightmaps").putLong("WORLD_SURFACE", 42L);

        assertFalse(ChunkSaveSkipPolicy.equivalentExceptLastUpdate(stored, proposed));
    }

    @Test
    void rejectsLastUpdatePresenceMismatch() {
        CompoundTag stored = sample(10L, 3);
        CompoundTag proposed = sample(99L, 3);
        proposed.remove("LastUpdate");

        assertFalse(ChunkSaveSkipPolicy.equivalentExceptLastUpdate(stored, proposed));
    }

    @Test
    void ignoresRootInsertionOrder() {
        CompoundTag stored = new CompoundTag();
        stored.putLong("LastUpdate", 1L);
        stored.putInt("DataVersion", 2);

        CompoundTag proposed = new CompoundTag();
        proposed.putInt("DataVersion", 2);
        proposed.putLong("LastUpdate", 7L);

        assertTrue(ChunkSaveSkipPolicy.equivalentExceptLastUpdate(stored, proposed));
    }

    private static CompoundTag sample(long lastUpdate, int inhabitedMarker) {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 4435);
        root.putLong("LastUpdate", lastUpdate);
        root.putInt("InhabitedMarker", inhabitedMarker);

        CompoundTag heightmaps = new CompoundTag();
        heightmaps.putLong("WORLD_SURFACE", 7L);
        root.put("Heightmaps", heightmaps);
        return root;
    }
}
