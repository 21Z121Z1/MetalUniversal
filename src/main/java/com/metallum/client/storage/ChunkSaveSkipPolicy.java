package com.metallum.client.storage;

import net.minecraft.nbt.CompoundTag;

import java.util.Objects;

/**
 * Conservative semantic equivalence rule for a chunk rewrite.
 *
 * <p>The existing and proposed root compounds must have exactly the same keys.
 * Every value except the root {@code LastUpdate} tag must compare equal using
 * Minecraft's own NBT equality. Missing {@code LastUpdate} on either side is
 * not treated as equivalent to a present value.
 */
public final class ChunkSaveSkipPolicy {
    private static final String LAST_UPDATE = "LastUpdate";

    private ChunkSaveSkipPolicy() {
    }

    public static boolean equivalentExceptLastUpdate(CompoundTag stored, CompoundTag proposed) {
        if (stored == null || proposed == null) {
            return false;
        }
        if (stored.contains(LAST_UPDATE) != proposed.contains(LAST_UPDATE)) {
            return false;
        }
        if (stored.size() != proposed.size()) {
            return false;
        }

        for (String key : stored.keySet()) {
            if (LAST_UPDATE.equals(key)) {
                continue;
            }
            if (!proposed.contains(key) || !Objects.equals(stored.get(key), proposed.get(key))) {
                return false;
            }
        }

        return true;
    }
}
