package com.metallum.mixin.sodium;

/** Stable region/pass ownership attached to one Sodium terrain storage. */
public interface SectionRenderDataStorageOwner {
    void metallum$setOwner(
            int regionX,
            int regionY,
            int regionZ,
            int baseChunkX,
            int baseChunkY,
            int baseChunkZ,
            boolean translucent
    );

    boolean metallum$hasOwner();

    int metallum$regionX();

    int metallum$regionY();

    int metallum$regionZ();

    int metallum$baseChunkX();

    int metallum$baseChunkY();

    int metallum$baseChunkZ();

    boolean metallum$isTranslucent();
}
