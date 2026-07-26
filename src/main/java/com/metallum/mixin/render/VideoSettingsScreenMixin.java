package com.metallum.mixin.render;

import com.metallum.client.metal.fx.MetalFxConfig;
import com.metallum.client.metal.fx.MetalFxOptionsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects a "MetalFX Settings..." button into the vanilla
 * {@link VideoSettingsScreen}. The button is placed at the bottom-right
 * of the screen so it doesn't disturb the existing options list, and is
 * only added when the active GPU backend is Metal — on OpenGL/Vulkan it
 * would be misleading to show MetalFX controls.
 *
 * <p>Injects at the tail of {@code init()} so all default widgets have
 * already been laid out; we then add our button via the standard
 * {@link Screen#addRenderableWidget} entry point, which keeps it
 * eligible for rendering, narration, and tab-ordering.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends Screen {
    protected VideoSettingsScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void metallum$addMetalFxButton(CallbackInfo ci) {
        if (!metallum$isMetalBackend()) {
            return;
        }
        // Force a capability query in case the user opened the screen before
        // the first frame was rendered. Safe to call repeatedly — it caches.
        // We can't get the MetalDevice handle from here, so the query happens
        // lazily on the MetalDevice ctor; this just makes sure the config is
        // loaded so the options screen reflects persisted state.
        MetalFxConfig.reload();

        int buttonWidth = 200;
        int buttonHeight = 20;
        int x = this.width - buttonWidth - 8;
        int y = this.height - buttonHeight - 28; // above the "Done" button row

        this.addRenderableWidget(Button.builder(
                Component.translatable("metallum.fx.button.open"),
                button -> Minecraft.getInstance().setScreenAndShow(new MetalFxOptionsScreen((Screen) (Object) this))
        ).bounds(x, y, buttonWidth, buttonHeight).build());
    }

    private boolean metallum$isMetalBackend() {
        try {
            var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
            if (device == null) {
                return false;
            }
            return "Metal".equals(device.getDeviceInfo().backendName());
        } catch (Throwable t) {
            // Backend not initialised yet — err on the side of showing the
            // button; the options screen handles unsupported devices.
            return false;
        }
    }
}
