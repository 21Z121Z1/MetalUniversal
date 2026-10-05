package com.metallum.mixin.chunk;

import com.metallum.client.chunk.ChunkPipelineOptions;
import com.metallum.client.chunk.LayeredLightSnapshotMap;
import com.metallum.client.chunk.LightSnapshotState;
import com.metallum.client.chunk.LightSnapshotTelemetry;
import com.metallum.client.chunk.LightSnapshotVerifier;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.chunk.DataLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.world.level.lighting.BlockLightSectionStorage$BlockDataLayerStorageMap")
public abstract class BlockLightSnapshotMixin {
    @Redirect(
            method = "copy",
            at = @At(
                    value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;"
                            + "clone()Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;"
            )
    )
    private Long2ObjectOpenHashMap<DataLayer> metallum$copyMap(Long2ObjectOpenHashMap<DataLayer> map) {
        if (ChunkPipelineOptions.lightSnapshotMode() == ChunkPipelineOptions.Mode.FAST) {
            LayeredLightSnapshotMap snapshot =
                    ((LightSnapshotState) this).metallum$buildLightSnapshot();
            LightSnapshotTelemetry.fastSnapshot();
            return snapshot;
        }
        return map.clone();
    }

    @Inject(method = "copy", at = @At("RETURN"))
    private void metallum$verifySnapshot(CallbackInfoReturnable<Object> cir) {
        if (ChunkPipelineOptions.lightSnapshotMode() != ChunkPipelineOptions.Mode.VERIFY) return;
        LayeredLightSnapshotMap candidate =
                ((LightSnapshotState) this).metallum$buildLightSnapshot();
        Long2ObjectOpenHashMap<DataLayer> vanilla =
                ((LightSnapshotState) cir.getReturnValue()).metallum$rawLightMap();
        LightSnapshotVerifier.requireEquivalent(candidate, vanilla);
        LightSnapshotTelemetry.verifiedSnapshot();
    }
}
