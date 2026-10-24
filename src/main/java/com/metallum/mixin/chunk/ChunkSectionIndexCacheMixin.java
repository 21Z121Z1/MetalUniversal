package com.metallum.mixin.chunk;

import com.metallum.client.chunk.ChunkPipelineOptions;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkAccess.class)
public abstract class ChunkSectionIndexCacheMixin implements LevelHeightAccessor {
    @Shadow @Final protected LevelHeightAccessor levelHeightAccessor;

    @Unique private int metallum$minSectionY;
    @Unique private boolean metallum$sectionIndexReady;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void metallum$cacheMinSectionY(CallbackInfo ci) {
        if (!ChunkPipelineOptions.sectionIndexCacheEnabled()) return;
        this.metallum$minSectionY = SectionPos.blockToSectionCoord(this.levelHeightAccessor.getMinY());
        this.metallum$sectionIndexReady = true;
    }

    @Override
    public int getSectionIndex(int blockY) {
        int minimum = this.metallum$sectionIndexReady
                ? this.metallum$minSectionY
                : this.getMinSectionY();
        return SectionPos.blockToSectionCoord(blockY) - minimum;
    }
}
