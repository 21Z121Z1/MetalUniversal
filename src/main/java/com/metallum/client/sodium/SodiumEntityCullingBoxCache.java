package com.metallum.client.sodium;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

public final class SodiumEntityCullingBoxCache {
    private record Entry(EntityRenderer<?, ?> renderer, Entity entity, int partialBits, AABB box) {}

    private static final ThreadLocal<Entry> CURRENT = new ThreadLocal<>();

    private SodiumEntityCullingBoxCache() {
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static void put(EntityRenderer<?, ?> renderer, Entity entity, float partialTicks, AABB box) {
        CURRENT.set(new Entry(renderer, entity, Float.floatToRawIntBits(partialTicks), box));
    }

    public static AABB take(EntityRenderer<?, ?> renderer, Entity entity, float partialTicks) {
        Entry entry = CURRENT.get();
        CURRENT.remove();
        if (entry == null
                || entry.renderer() != renderer
                || entry.entity() != entity
                || entry.partialBits() != Float.floatToRawIntBits(partialTicks)) {
            return null;
        }
        return entry.box();
    }
}
