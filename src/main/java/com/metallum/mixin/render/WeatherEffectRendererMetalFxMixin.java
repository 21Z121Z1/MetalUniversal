package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalFxManager;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Uses the extracted rain/snow column lists as authoritative weather activity. */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMetalFxMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void metallum$observeWeather(
            final Vec3 cameraPosition,
            final WeatherRenderState renderState,
            final CallbackInfo ci
    ) {
        int samples = renderState == null
                ? 0
                : renderState.rainColumns.size() + renderState.snowColumns.size();
        MetalFxManager.observeReactiveParticlesWeather(samples);
    }
}
