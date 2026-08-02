package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisOpenGlUniformTrace;
import net.irisshaders.iris.uniforms.custom.CustomUniformFixedInputUniformsHolder;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures Iris fixed-input suppliers independently of OpenGL active-uniform optimization. */
@Mixin(value = CustomUniforms.class, remap = false)
public abstract class IrisFixedUniformSupplierTraceMixin {
    @Shadow @Final private CustomUniformFixedInputUniformsHolder inputHolder;

    @Inject(method = "update", at = @At("HEAD"))
    private void metallum$beginFixedInputTracking(final CallbackInfo callbackInfo) {
        IrisOpenGlUniformTrace.beginFixedInputTracking(this.inputHolder.getAll());
    }

    @Inject(method = "update", at = @At("RETURN"))
    private void metallum$recordFixedInputs(final CallbackInfo callbackInfo) {
        IrisOpenGlUniformTrace.endFixedInputTracking();
    }
}
