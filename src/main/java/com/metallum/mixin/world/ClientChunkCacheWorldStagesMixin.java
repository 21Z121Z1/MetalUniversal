package com.metallum.mixin.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.metallum.client.validation.telemetry.VanillaWorldStageTelemetry;
import com.metallum.client.validation.telemetry.WorldStageRecorder.Stage;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ClientChunkCache.class)
abstract class ClientChunkCacheWorldStagesMixin {
    @Shadow @Final private ClientLevel level;

    @WrapMethod(method = "replaceWithPacketData")
    private LevelChunk metallum$chunkInstall(int x, int z, ClientboundLevelChunkPacketData data,
                                            Operation<LevelChunk> original) {
        var recorder = VanillaWorldStageTelemetry.recorder();
        long context = VanillaWorldStageTelemetry.contextId(level);
        long start = System.nanoTime();
        boolean completed = false;
        LevelChunk result = null;
        try {
            result = original.call(x, z, data);
            completed = true;
            return result;
        } finally {
            recorder.record(Stage.CHUNK_INSTALL, context, start, System.nanoTime(), completed,
                    -1, -1, 1, completed ? (result == null ? 0 : 1) : -1);
        }
    }
}
