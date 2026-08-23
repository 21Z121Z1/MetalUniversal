package com.metallum.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Stores only primitive owner coordinates; candidate keys remain coordinate based. */
@Mixin(SectionRenderDataStorage.class)
public abstract class SectionRenderDataStorageOwnerMixin implements SectionRenderDataStorageOwner {
    @Unique
    private boolean metallum$hasOwner;

    @Unique
    private int metallum$regionX;

    @Unique
    private int metallum$regionY;

    @Unique
    private int metallum$regionZ;

    @Unique
    private boolean metallum$translucent;

    @Override
    public void metallum$setOwner(
            final int regionX,
            final int regionY,
            final int regionZ,
            final boolean translucent
    ) {
        metallum$regionX = regionX;
        metallum$regionY = regionY;
        metallum$regionZ = regionZ;
        metallum$translucent = translucent;
        metallum$hasOwner = true;
    }

    @Override
    public boolean metallum$hasOwner() {
        return metallum$hasOwner;
    }

    @Override
    public int metallum$regionX() {
        return metallum$regionX;
    }

    @Override
    public int metallum$regionY() {
        return metallum$regionY;
    }

    @Override
    public int metallum$regionZ() {
        return metallum$regionZ;
    }

    @Override
    public boolean metallum$isTranslucent() {
        return metallum$translucent;
    }
}
