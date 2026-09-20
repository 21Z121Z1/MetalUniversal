package com.metallum.mixin.world;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.validation.telemetry.VanillaWorldStageTelemetry;
import com.metallum.client.validation.telemetry.WorldStageRecorder.Stage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.PacketProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
abstract class MinecraftWorldStagesMixin {
    @WrapOperation(method = "runTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/PacketProcessor;processQueuedPackets()V"))
    private void metallum$clientPackets(PacketProcessor processor, Operation<Void> original) {
        var recorder = VanillaWorldStageTelemetry.recorder();
        long context = VanillaWorldStageTelemetry.contextId(this);
        long start = System.nanoTime();
        boolean completed = false;
        try {
            original.call(processor);
            completed = true;
        } finally {
            recorder.record(Stage.CLIENT_PACKETS, context, start, System.nanoTime(), completed, -1, -1, -1, -1);
        }
    }
}
