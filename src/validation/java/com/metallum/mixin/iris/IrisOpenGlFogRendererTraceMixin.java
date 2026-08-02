package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisOpenGlUniformTrace;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.util.FogStorage;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records the native Iris fog lifecycle without changing the returned data. */
@Mixin(value = FogRenderer.class, remap = false)
public abstract class IrisOpenGlFogRendererTraceMixin {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void metallum$recordFogSetup(final CallbackInfoReturnable<FogData> cir) {
        FogData data = cir.getReturnValue();
        if (data != null) {
            FogParameters stored = ((FogStorage) (Object) this).sodium$getFogParameters();
            IrisOpenGlUniformTrace.recordFogSetup(data, stored);
        }
    }
}
