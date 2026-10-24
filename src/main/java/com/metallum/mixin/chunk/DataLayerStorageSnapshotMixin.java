package com.metallum.mixin.chunk;

import com.metallum.client.chunk.LayeredLightSnapshotMap;
import com.metallum.client.chunk.LightSnapshotState;
import com.metallum.client.chunk.LightSnapshotTelemetry;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DataLayerStorageMap.class)
public abstract class DataLayerStorageSnapshotMixin implements LightSnapshotState {
    @Shadow @Final protected Long2ObjectOpenHashMap<DataLayer> map;

    @Unique private final LongOpenHashSet metallum$changedLightSections = new LongOpenHashSet();
    @Unique private Long2ObjectOpenHashMap<DataLayer> metallum$lightBase;
    @Unique private Long2ObjectOpenHashMap<DataLayer> metallum$lightDelta;

    @Inject(method = "setLayer", at = @At("TAIL"))
    private void metallum$recordSet(long section, DataLayer layer, CallbackInfo ci) {
        this.metallum$changedLightSections.add(section);
    }

    @Inject(method = "removeLayer", at = @At("TAIL"))
    private void metallum$recordRemove(long section, CallbackInfoReturnable<DataLayer> cir) {
        this.metallum$changedLightSections.add(section);
    }

    @Inject(method = "copyDataLayer", at = @At("TAIL"))
    private void metallum$recordCopy(long section, CallbackInfoReturnable<DataLayer> cir) {
        this.metallum$changedLightSections.add(section);
    }

    @Override
    public LayeredLightSnapshotMap metallum$buildLightSnapshot() {
        if (this.map instanceof LayeredLightSnapshotMap) {
            throw new IllegalStateException("Published light snapshot cannot produce another snapshot");
        }

        int changed = this.metallum$changedLightSections.size();
        boolean rebase = this.metallum$lightBase == null
                || this.metallum$lightDelta == null
                || this.metallum$lightDelta.size() + changed
                > Math.max(512, this.metallum$lightBase.size() >>> 3);

        if (rebase) {
            this.metallum$lightBase = this.map.clone();
            this.metallum$lightDelta = new Long2ObjectOpenHashMap<>(0);
            LightSnapshotTelemetry.rebase();
        } else if (changed != 0) {
            Long2ObjectOpenHashMap<DataLayer> next = this.metallum$lightDelta.clone();
            for (LongIterator iterator = this.metallum$changedLightSections.iterator(); iterator.hasNext();) {
                long key = iterator.nextLong();
                DataLayer value = this.map.get(key);
                next.put(key, value == null ? LayeredLightSnapshotMap.REMOVED : value);
            }
            this.metallum$lightDelta = next;
            LightSnapshotTelemetry.delta();
        }

        this.metallum$changedLightSections.clear();
        return new LayeredLightSnapshotMap(this.metallum$lightBase, this.metallum$lightDelta);
    }

    @Override
    public Long2ObjectOpenHashMap<DataLayer> metallum$rawLightMap() {
        return this.map;
    }
}
