package com.metallum.mixin.render;

import com.metallum.client.metal.fx.MetalFxConfig;
import com.metallum.client.metal.fx.MetalFxWarningScreen;
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
 * {@link VideoSettingsScreen}.
 *
 * <p><b>Why {@code rebuildWidgets} and not {@code init}.</b> In Minecraft
 * 26.2 the {@code VideoSettingsScreen} class no longer overrides
 * {@link Screen#init}. Mixin can only inject into methods declared by the
 * target class itself (not inherited ones), so {@code @Inject(method = "init")}
 * fails at runtime with
 * {@code "could not find any targets matching 'init'"}, which crashes the
 * whole screen — the video settings becomes impossible to open.
 *
 * <p>{@code rebuildWidgets()} is the 26.x widget-layout entry point that
 * every concrete Screen subclass overrides to populate its UI. Injecting at
 * its {@code TAIL} guarantees all default widgets (the options list, the
 * "Done" button) have already been added, so our button lands on top of
 * them and stays eligible for rendering, narration, and tab-ordering via
 * the standard {@link Screen#addRenderableWidget} path.
 *
 * <p>The button is placed at the bottom-right of the screen so it doesn't
 * disturb the existing options list, and is only added when the active GPU
 * backend is Metal — on OpenGL/Vulkan it would be misleading to show
 * MetalFX controls.
 *
 * <p>Clicking the button routes through
 * {@link MetalFxWarningScreen#openIfNotAcknowledged(Screen)}: the first
 * time the user opens MetalFX settings they see a warning dialog with
 * the official Apple MetalFX system/chip requirements and explicit
 * Enable / Do Not Enable choices. On subsequent opens the warning is
 * skipped and the options screen is shown directly.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends Screen {
    protected VideoSettingsScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "rebuildWidgets", at = @At("TAIL"))
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
                button -> MetalFxWarningScreen.openIfNotAcknowledged((Screen) (Object) this)
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
