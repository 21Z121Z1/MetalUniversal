package com.metallum.mixin.render;

import com.metallum.client.performance.FrameStutterRecorder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Times the single MetalDevice compile funnel on render and prewarm threads. */
@Mixin(targets = "com.metallum.client.metal.render.MetalDevice", remap = false)
public abstract class MetalDevicePipelineCompileTimingMixin {
    @Unique
    private static final ThreadLocal<Long> METALLUM_COMPILE_BEGIN = new ThreadLocal<>();

    @Inject(method = "compileWithIrisOverride", at = @At("HEAD"), remap = false)
    private void metallum$beginPipelineCompile(
            RenderPipeline pipeline,
            ShaderSource source,
            CallbackInfoReturnable<Object> cir
    ) {
        if (FrameStutterRecorder.runtime().isEnabled()) {
            METALLUM_COMPILE_BEGIN.set(System.nanoTime());
        }
    }

    @Inject(method = "compileWithIrisOverride", at = @At("RETURN"), remap = false)
    private void metallum$endPipelineCompile(
            RenderPipeline pipeline,
            ShaderSource source,
            CallbackInfoReturnable<Object> cir
    ) {
        Long begin = METALLUM_COMPILE_BEGIN.get();
        METALLUM_COMPILE_BEGIN.remove();
        if (begin == null) return;
        long end = System.nanoTime();
        String name = Thread.currentThread().getName();
        boolean background = "metallum-pso-prewarm".equals(name);
        FrameStutterRecorder.runtime().recordPipelineCompile(
                pipeline == null ? "unknown" : pipeline.getLocation().toString(),
                end - begin,
                background
        );
    }
}
