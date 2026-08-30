package com.metallum.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Stores only primitive owner coordinates; candidate keys remain coordinate based. */
@Mixin(SectionRenderDataStorage.class)
public abstract class SectionRenderDataStorageOwnerMixin {
    @Unique
    private boolean metallum$hasOwner;

    @Unique
    private int metallum$regionX;

    @Unique
    private int metallum$regionY;

    @Unique
    private int metallum$regionZ;

    @Unique
    private int metallum$baseChunkX;

    @Unique
    private int metallum$baseChunkY;

    @Unique
    private int metallum$baseChunkZ;

    @Unique
    private boolean metallum$translucent;

    public void metallum$setOwner(
            final int regionX,
            final int regionY,
            final int regionZ,
            final int baseChunkX,
            final int baseChunkY,
            final int baseChunkZ,
            final boolean translucent
    ) {
        metallum$regionX = regionX;
        metallum$regionY = regionY;
        metallum$regionZ = regionZ;
        metallum$baseChunkX = baseChunkX;
        metallum$baseChunkY = baseChunkY;
        metallum$baseChunkZ = baseChunkZ;
        metallum$translucent = translucent;
        metallum$hasOwner = true;
    }

    public boolean metallum$hasOwner() {
        return metallum$hasOwner;
    }

    public int metallum$regionX() {
        return metallum$regionX;
    }

    public int metallum$regionY() {
        return metallum$regionY;
    }

    public int metallum$regionZ() {
        return metallum$regionZ;
    }

    public int metallum$baseChunkX() {
        return metallum$baseChunkX;
    }

    public int metallum$baseChunkY() {
        return metallum$baseChunkY;
    }

    public int metallum$baseChunkZ() {
        return metallum$baseChunkZ;
    }

    public boolean metallum$isTranslucent() {
        return metallum$translucent;
    }
}
