package com.metallum.mixin.world;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.validation.telemetry.VanillaWorldStageTelemetry;
import com.metallum.client.validation.telemetry.WorldStageRecorder.Stage;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.PacketProcessor;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MinecraftServer.class)
abstract class MinecraftServerWorldStagesMixin {
    @WrapOperation(method = "processPacketsAndTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/PacketProcessor;processQueuedPackets()V"))
    private void metallum$serverPackets(PacketProcessor processor, Operation<Void> original) {
        if (!((Object) this instanceof IntegratedServer)) {
            original.call(processor);
            return;
        }
        var recorder = VanillaWorldStageTelemetry.recorder();
        long context = VanillaWorldStageTelemetry.contextId(this);
        long start = System.nanoTime();
        boolean completed = false;
        try {
            original.call(processor);
            completed = true;
        } finally {
            recorder.record(Stage.SERVER_PACKETS, context, start, System.nanoTime(), completed, -1, -1, -1, -1);
        }
    }
}
