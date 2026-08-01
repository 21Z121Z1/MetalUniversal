package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisOpenGlUniformTrace;
import net.irisshaders.iris.uniforms.custom.cached.CachedUniform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records fixed suppliers at Iris's actual cache update boundary. */
@Mixin(value = CachedUniform.class, remap = false)
public abstract class IrisCachedUniformUpdateTraceMixin {
    @Inject(method = "update", at = @At("RETURN"))
    private void metallum$recordFixedInputUpdate(final CallbackInfo callbackInfo) {
        IrisOpenGlUniformTrace.recordFixedInputUpdate((CachedUniform) (Object) this);
    }
}
