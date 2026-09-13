package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalEntityMotionCapture;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NameTagFeatureRenderer.Submit.class)
public abstract class NameTagFeatureSubmitMetalFxMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void metallum$captureEntityOwner(
            final Matrix4fc pose,
            final float x,
            final float y,
            final Component text,
            final int lightCoords,
            final int color,
            final int backgroundColor,
            final Font.DisplayMode displayMode,
            final CallbackInfo ci
    ) {
        MetalEntityMotionCapture.captureModelSubmit(this);
    }
}
