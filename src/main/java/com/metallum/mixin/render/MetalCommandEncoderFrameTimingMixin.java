package com.metallum.mixin.render;

import com.metallum.client.performance.FrameStutterRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records the complete submit path, including any in-flight-slot wait. */
@Mixin(targets = "com.metallum.client.metal.render.MetalCommandEncoder", remap = false)
public abstract class MetalCommandEncoderFrameTimingMixin {
    @Unique
    private long metallum$submitBeginNanos;

    @Inject(method = "submit", at = @At("HEAD"), remap = false)
    private void metallum$beginSubmit(CallbackInfo ci) {
        if (FrameStutterRecorder.runtime().isEnabled()) {
            metallum$submitBeginNanos = System.nanoTime();
        }
    }

    @Inject(method = "submit", at = @At("RETURN"), remap = false)
    private void metallum$endSubmit(CallbackInfo ci) {
        if (metallum$submitBeginNanos <= 0L) return;
        long end = System.nanoTime();
        FrameStutterRecorder.runtime().recordCommandSubmit(metallum$submitBeginNanos, end);
        metallum$submitBeginNanos = 0L;
    }
}
