package com.metallum.mixin.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.metallum.client.validation.telemetry.VanillaWorldStageTelemetry;
import com.metallum.client.validation.telemetry.WorldStageRecorder.Stage;
import java.util.function.BooleanSupplier;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(IntegratedServer.class)
abstract class IntegratedServerWorldStagesMixin {
    @Shadow private boolean paused;

    @WrapMethod(method = "tickServer")
    private void metallum$serverTick(BooleanSupplier haveTime, Operation<Void> original) {
        var recorder = VanillaWorldStageTelemetry.recorder();
        long context = VanillaWorldStageTelemetry.contextId(this);
        long start = System.nanoTime();
        long token = recorder.begin(Stage.SERVER_TICK, context, start, -1);
        boolean completed = false;
        try {
            original.call(haveTime);
            completed = true;
        } finally {
            recorder.end(token, System.nanoTime(), completed, -1, 1, paused ? 1 : 0);
        }
    }
}
