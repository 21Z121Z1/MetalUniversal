package com.metallum.mixin.iris;

import com.metallum.client.metal.render.MetalIrisCompat;
import net.irisshaders.iris.gl.GLDebug;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code GLDebug.reloadDebugState} installs KHR/ARB/AMD GL debug callbacks;
 * none of those entry points exist without a GL context.
 */
@Mixin(value = GLDebug.class, remap = false)
public abstract class IrisGlDebugCompatMixin {
    @Inject(method = "reloadDebugState", at = @At("HEAD"), cancellable = true)
    private static void metallum$skipGlDebugCallbacks(final CallbackInfo ci) {
        if (MetalIrisCompat.holdIrisDormant()) {
            ci.cancel();
        }
    }

    // Runtime debug-group/name entry points are invoked from Iris's Hud and
    // renderer mixins on every backend; with reloadDebugState cancelled their
    // GL debug state never initializes, so they must no-op while dormant.
    @Inject(method = "pushGroup", at = @At("HEAD"), cancellable = true)
    private static void metallum$skipPushGroup(final CallbackInfo ci) {
        if (MetalIrisCompat.holdIrisDormant()) {
            ci.cancel();
        }
    }

    @Inject(method = "popGroup", at = @At("HEAD"), cancellable = true)
    private static void metallum$skipPopGroup(final CallbackInfo ci) {
        if (MetalIrisCompat.holdIrisDormant()) {
            ci.cancel();
        }
    }

    @Inject(method = "nameObject", at = @At("HEAD"), cancellable = true)
    private static void metallum$skipNameObject(final CallbackInfo ci) {
        if (MetalIrisCompat.holdIrisDormant()) {
            ci.cancel();
        }
    }
}
