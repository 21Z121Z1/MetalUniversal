package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisOpenGlUniformTrace;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records the fixed Iris ProgramUniforms update boundary for the GL oracle. */
@Mixin(value = ProgramUniforms.class, remap = false)
public abstract class IrisOpenGlProgramUniformsTraceMixin {
    @Inject(method = "update", at = @At("HEAD"))
    private void metallum$recordUpdateStart(final CallbackInfo callbackInfo) {
        IrisOpenGlUniformTrace.recordProgramUpdate((ProgramUniforms) (Object) this, "start");
    }

    @Inject(method = "update", at = @At("RETURN"))
    private void metallum$recordUpdateEnd(final CallbackInfo callbackInfo) {
        IrisOpenGlUniformTrace.recordProgramUpdate((ProgramUniforms) (Object) this, "end");
    }
}
