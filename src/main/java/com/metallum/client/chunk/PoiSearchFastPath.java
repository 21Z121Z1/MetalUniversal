package com.metallum.client.chunk;

import com.metallum.mixin.chunk.PoiSectionFastPathAccessor;
import com.metallum.mixin.chunk.SectionStorageFastPathAccessor;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Eager loop implementation of AcquirePoi's immediately-consumed sorted POI
 * search. Iteration order follows the storage traversal before the stable sort.
 */
public final class PoiSearchFastPath {
    private PoiSearchFastPath() {}

    public static Stream<Pair<Holder<PoiType>, BlockPos>> search(
            PoiManager manager,
            Predicate<Holder<PoiType>> typePredicate,
            Predicate<BlockPos> positionPredicate,
            BlockPos center,
            int radius,
            PoiManager.Occupancy occupancy
    ) {
        SectionStorageFastPathAccessor storage = (SectionStorageFastPathAccessor) manager;
        LevelHeightAccessor height = storage.metallum$levelHeightAccessor();

        int minSectionY = height.getMinSectionY();
        int maxSectionY = height.getMaxSectionY();
        int chunkRadius = Math.floorDiv(radius, 16) + 1;
        ChunkPos centerChunk = ChunkPos.containing(center);
        long radiusSquared = (long) radius * radius;
        Predicate<? super PoiRecord> occupancyPredicate = occupancy.getTest();

        List<Pair<Holder<PoiType>, BlockPos>> result = new ArrayList<>();
        for (int z = centerChunk.z() - chunkRadius; z <= centerChunk.z() + chunkRadius; z++) {
            for (int x = centerChunk.x() - chunkRadius; x <= centerChunk.x() + chunkRadius; x++) {
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    Optional<?> maybeSection = storage.metallum$getOrLoad(SectionPos.asLong(x, sectionY, z));
                    if (maybeSection.isEmpty()) continue;

                    Map<Holder<PoiType>, Set<PoiRecord>> byType =
                            ((PoiSectionFastPathAccessor) maybeSection.get()).metallum$recordsByType();
                    for (Map.Entry<Holder<PoiType>, Set<PoiRecord>> entry : byType.entrySet()) {
                        Holder<PoiType> type = entry.getKey();
                        if (!typePredicate.test(type)) continue;

                        for (PoiRecord record : entry.getValue()) {
                            if (!occupancyPredicate.test(record)) continue;
                            BlockPos position = record.getPos();
                            if (Math.abs(position.getX() - center.getX()) > radius
                                    || Math.abs(position.getZ() - center.getZ()) > radius
                                    || position.distSqr(center) > radiusSquared
                                    || !positionPredicate.test(position)) {
                                continue;
                            }
                            result.add(Pair.of(type, position));
                        }
                    }
                }
            }
        }

        result.sort(Comparator.comparingDouble(pair -> pair.getSecond().distSqr(center)));
        PoiSearchFastPathTelemetry.fastCall();
        return result.stream();
    }

    public static Stream<Pair<Holder<PoiType>, BlockPos>> verify(
            PoiManager manager,
            Predicate<Holder<PoiType>> typePredicate,
            Predicate<BlockPos> positionPredicate,
            BlockPos center,
            int radius,
            PoiManager.Occupancy occupancy
    ) {
        List<BlockPos> requested = new ArrayList<>();
        List<Boolean> decisions = new ArrayList<>();

        List<Pair<Holder<PoiType>, BlockPos>> reference =
                manager.findAllClosestFirstWithType(
                        typePredicate,
                        position -> {
                            boolean accepted = positionPredicate.test(position);
                            requested.add(position.immutable());
                            decisions.add(accepted);
                            return accepted;
                        },
                        center,
                        radius,
                        occupancy
                ).toList();

        int[] cursor = {0};
        boolean[] sameOrder = {true};
        List<Pair<Holder<PoiType>, BlockPos>> candidate =
                search(
                        manager,
                        typePredicate,
                        position -> {
                            int index = cursor[0]++;
                            if (index >= requested.size() || !requested.get(index).equals(position)) {
                                sameOrder[0] = false;
                                return false;
                            }
                            return decisions.get(index);
                        },
                        center,
                        radius,
                        occupancy
                ).toList();

        boolean match = sameOrder[0]
                && cursor[0] == requested.size()
                && reference.equals(candidate);
        if (!match) {
            PoiSearchFastPathTelemetry.mismatch();
            throw new IllegalStateException(
                    "POI fast-path verification diverged: reference=" + reference.size()
                            + " candidate=" + candidate.size()
                            + " filterCalls=" + requested.size()
                            + " replayed=" + cursor[0]
            );
        }
        PoiSearchFastPathTelemetry.verified(requested.size());
        return reference.stream();
    }
}
