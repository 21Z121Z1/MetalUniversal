package com.metallum.mixin.chunk;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;

@Mixin(SectionStorage.class)
public interface SectionStorageFastPathAccessor {
    @Invoker("getOrLoad")
    Optional<?> metallum$getOrLoad(long sectionPos);

    @Accessor("levelHeightAccessor")
    LevelHeightAccessor metallum$levelHeightAccessor();
}
