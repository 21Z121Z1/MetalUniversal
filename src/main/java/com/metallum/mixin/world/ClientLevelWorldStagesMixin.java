package com.metallum.mixin.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.validation.telemetry.VanillaWorldStageTelemetry;
import com.metallum.client.validation.telemetry.WorldStageRecorder.Stage;
import java.util.Deque;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientLevel.class)
abstract class ClientLevelWorldStagesMixin {
    @Shadow @Final private Deque<Runnable> lightUpdateQueue;

    @WrapMethod(method = "queueLightUpdate")
    private void metallum$lightEnqueue(Runnable update, Operation<Void> original) {
        var recorder = VanillaWorldStageTelemetry.recorder();
        long context = VanillaWorldStageTelemetry.contextId(this);
        int before = lightUpdateQueue.size();
        long start = System.nanoTime();
        boolean completed = false;
        try {
            original.call(update);
            completed = true;
        } finally {
            recorder.record(Stage.LIGHT_ENQUEUE, context, start, System.nanoTime(), completed,
                    before, lightUpdateQueue.size(), completed ? 1 : 0, -1);
        }
    }

    @WrapMethod(method = "pollLightUpdates")
    private void metallum$lightPoll(Operation<Void> original) {
        var recorder = VanillaWorldStageTelemetry.recorder();
        long context = VanillaWorldStageTelemetry.contextId(this);
        int before = lightUpdateQueue.size();
        long start = System.nanoTime();
        boolean completed = false;
        try {
            original.call();
            completed = true;
        } finally {
            // Reentrant callbacks can enqueue more work. Queue delta is not a task count.
            recorder.record(Stage.LIGHT_POLL, context, start, System.nanoTime(), completed,
                    before, lightUpdateQueue.size(), -1, -1);
        }
    }

    @WrapOperation(method = "pollLightUpdates", at = @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V"))
    private void metallum$lightTask(Runnable update, Operation<Void> original) {
        var recorder = VanillaWorldStageTelemetry.recorder();
        long context = VanillaWorldStageTelemetry.contextId(this);
        int before = lightUpdateQueue.size();
        long start = System.nanoTime();
        boolean completed = false;
        try {
            original.call(update);
            completed = true;
        } finally {
            recorder.record(Stage.LIGHT_TASK, context, start, System.nanoTime(), completed,
                    before, lightUpdateQueue.size(), 1, -1);
        }
    }

    @WrapMethod(method = "update")
    private void metallum$lightUpdate(Operation<Void> original) {
        var recorder = VanillaWorldStageTelemetry.recorder();
        long context = VanillaWorldStageTelemetry.contextId(this);
        int before = lightUpdateQueue.size();
        long start = System.nanoTime();
        boolean completed = false;
        try {
            original.call();
            completed = true;
        } finally {
            recorder.record(Stage.LIGHT_UPDATE, context, start, System.nanoTime(), completed,
                    before, lightUpdateQueue.size(), -1, -1);
        }
    }
}
