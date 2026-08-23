package com.metallum.mixin.sodium;

/** Stable region/pass ownership attached to one Sodium terrain storage. */
public interface SectionRenderDataStorageOwner {
    void metallum$setOwner(int regionX, int regionY, int regionZ, boolean translucent);

    boolean metallum$hasOwner();

    int metallum$regionX();

    int metallum$regionY();

    int metallum$regionZ();

    boolean metallum$isTranslucent();
}
