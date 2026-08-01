package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisOpenGlUniformTrace;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.gl.state.ValueUpdateNotifier;
import net.irisshaders.iris.gl.uniform.Uniform;
import net.irisshaders.iris.gl.uniform.UniformType;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/** Captures Iris's logical OpenGL uniform name before the GL call path erases it. */
@Mixin(value = ProgramUniforms.Builder.class, remap = false)
public abstract class IrisOpenGlUniformBuilderTraceMixin {
    @Shadow @Final private String name;
    @Shadow @Final private int program;
    @Shadow @Final private Map<Integer, String> locations;
    @Shadow @Final private Map<String, UniformType> uniformNames;

    @Inject(
            method = "addUniform(Lnet/irisshaders/iris/gl/uniform/UniformUpdateFrequency;"
                    + "Lnet/irisshaders/iris/gl/uniform/Uniform;)"
                    + "Lnet/irisshaders/iris/gl/program/ProgramUniforms$Builder;",
            at = @At("RETURN")
    )
    private void metallum$registerUniform(
            final UniformUpdateFrequency frequency,
            final Uniform uniform,
            final CallbackInfoReturnable<ProgramUniforms.Builder> cir
    ) {
        IrisOpenGlUniformTrace.register(
                uniform, this.name, this.program,
                this.locations.get(uniform.getLocation()),
                this.uniformType(uniform),
                frequency.name()
        );
    }

    @Inject(
            method = "addDynamicUniform(Lnet/irisshaders/iris/gl/uniform/Uniform;"
                    + "Lnet/irisshaders/iris/gl/state/ValueUpdateNotifier;)"
                    + "Lnet/irisshaders/iris/gl/program/ProgramUniforms$Builder;",
            at = @At("RETURN")
    )
    private void metallum$registerDynamicUniform(
            final Uniform uniform,
            final ValueUpdateNotifier notifier,
            final CallbackInfoReturnable<ProgramUniforms.Builder> cir
    ) {
        IrisOpenGlUniformTrace.register(
                uniform, this.name, this.program,
                this.locations.get(uniform.getLocation()),
                this.uniformType(uniform),
                "DYNAMIC"
        );
    }

    private UniformType uniformType(final Uniform uniform) {
        String uniformName = this.locations.get(uniform.getLocation());
        return uniformName == null ? null : this.uniformNames.get(uniformName);
    }
}
