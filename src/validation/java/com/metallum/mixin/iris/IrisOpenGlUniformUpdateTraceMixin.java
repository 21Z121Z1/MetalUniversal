package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisOpenGlUniformTrace;
import net.irisshaders.iris.gl.uniform.FloatUniform;
import net.irisshaders.iris.gl.uniform.IntUniform;
import net.irisshaders.iris.gl.uniform.Matrix3Uniform;
import net.irisshaders.iris.gl.uniform.MatrixFromFloatArrayUniform;
import net.irisshaders.iris.gl.uniform.MatrixUniform;
import net.irisshaders.iris.gl.uniform.Uniform;
import net.irisshaders.iris.gl.uniform.Vector2IntegerJomlUniform;
import net.irisshaders.iris.gl.uniform.Vector2Uniform;
import net.irisshaders.iris.gl.uniform.Vector3IntegerUniform;
import net.irisshaders.iris.gl.uniform.Vector3Uniform;
import net.irisshaders.iris.gl.uniform.Vector4ArrayUniform;
import net.irisshaders.iris.gl.uniform.Vector4IntegerJomlUniform;
import net.irisshaders.iris.gl.uniform.Vector4Uniform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records each concrete Iris OpenGL uniform after Iris updates its cached value. */
@Mixin(value = {
        FloatUniform.class,
        IntUniform.class,
        Matrix3Uniform.class,
        MatrixFromFloatArrayUniform.class,
        MatrixUniform.class,
        Vector2IntegerJomlUniform.class,
        Vector2Uniform.class,
        Vector3IntegerUniform.class,
        Vector3Uniform.class,
        Vector4ArrayUniform.class,
        Vector4IntegerJomlUniform.class,
        Vector4Uniform.class
}, remap = false)
public abstract class IrisOpenGlUniformUpdateTraceMixin {
    @Inject(method = "update", at = @At("RETURN"))
    private void metallum$recordUniform(final CallbackInfo ci) {
        IrisOpenGlUniformTrace.record((Uniform) (Object) this);
    }
}
