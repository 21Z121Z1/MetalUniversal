package com.metallum.mixin.chunk;

import com.metallum.client.chunk.ChunkPipelineOptions;
import com.metallum.client.chunk.PoiSearchFastPath;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.behavior.AcquirePoi;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Predicate;
import java.util.stream.Stream;

@Mixin(AcquirePoi.class)
public abstract class AcquirePoiFastPathMixin {
    @Redirect(
            method = "lambda$create$3",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/village/poi/PoiManager;"
                            + "findAllClosestFirstWithType(Ljava/util/function/Predicate;"
                            + "Ljava/util/function/Predicate;Lnet/minecraft/core/BlockPos;I"
                            + "Lnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;)"
                            + "Ljava/util/stream/Stream;"
            )
    )
    private static Stream<Pair<Holder<PoiType>, BlockPos>> metallum$searchPoi(
            PoiManager manager,
            Predicate<Holder<PoiType>> typePredicate,
            Predicate<BlockPos> positionPredicate,
            BlockPos center,
            int radius,
            PoiManager.Occupancy occupancy
    ) {
        return switch (ChunkPipelineOptions.poiSearchMode()) {
            case VERIFY -> PoiSearchFastPath.verify(
                    manager, typePredicate, positionPredicate, center, radius, occupancy
            );
            case FAST -> PoiSearchFastPath.search(
                    manager, typePredicate, positionPredicate, center, radius, occupancy
            );
            case OFF -> manager.findAllClosestFirstWithType(
                    typePredicate, positionPredicate, center, radius, occupancy
            );
        };
    }
}
