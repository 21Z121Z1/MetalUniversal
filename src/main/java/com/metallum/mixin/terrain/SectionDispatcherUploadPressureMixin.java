package com.metallum.mixin.terrain;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.metallum.client.terrain.VanillaTerrainUploadPressure;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;

/** CPU upload-call timing only; vanilla callbacks do not prove GPU completion. */
@Mixin(SectionRenderDispatcher.class)
abstract class SectionDispatcherUploadPressureMixin {
    @WrapMethod(method = "uploadTerrainBuffersToGpu")
    private void metallum$measureUploadCall(final Operation<Void> original) {
        long start = System.nanoTime();
        boolean completed = false;
        try {
            original.call();
            completed = true;
        } finally {
            VanillaTerrainUploadPressure.counters().recordUploadCall(System.nanoTime() - start, completed);
        }
    }
}
