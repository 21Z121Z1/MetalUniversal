package com.metallum.mixin.sodium;

import com.metallum.client.metal.fx.MetalFxConfig;
import com.metallum.client.metal.fx.MetalFxWarningScreen;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects a "MetalFX Settings..." button into Sodium's video settings
 * screen. Sodium replaces the vanilla {@code VideoSettingsScreen} with
 * its own custom UI ({@code net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen}).
 *
 * <p><b>Why {@code init} and not {@code extractRenderState}/{@code mouseClicked}.</b>
 * The previous implementation injected into Sodium's {@code extractRenderState}
 * and {@code mouseClicked} methods with {@code remap = false} and relied on
 * the mixin config's {@code defaultRequire: 1}. If Sodium 0.9.0 for MC 26.2
 * changed either method's name or signature, the mixin failed to apply and
 * <em>crashed the entire screen</em> — making video settings impossible to
 * open whenever Sodium was installed.
 *
 * <p>Injecting into {@code init} at {@code TAIL} with {@code require = 0}
 * is far more robust:
 * <ul>
 *   <li>{@code init} is the standard {@link Screen} lifecycle method that
 *       every screen overrides to build its widgets — Sodium's screen is
 *       no exception.</li>
 *   <li>{@code require = 0} means the injection is silently skipped if the
 *       method isn't found, instead of crashing the game.</li>
 *   <li>Adding a standard {@link Button} via {@link Screen#addRenderableWidget}
 *       gives us proper rendering, click handling, narration, and
 *       tab-ordering for free — no custom draw code or click interception
 *       needed.</li>
 * </ul>
 *
 * <p>The button is placed at the top-right corner, consistent with the
 * vanilla {@code VideoSettingsScreenMixin}'s placement.
 *
 * <p>The mixin is only applied when Sodium is loaded (gated by the
 * {@code MetallumMixinConfigPlugin}'s {@code .sodium.} package rule)
 * and only renders the button when the active GPU backend is Metal.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen")
public abstract class SodiumVideoSettingsScreenMixin extends Screen {
    protected SodiumVideoSettingsScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void metallum$addMetalFxButton(CallbackInfo ci) {
        if (!metallum$isMetalBackend()) {
            return;
        }
        MetalFxConfig.reload();

        int buttonWidth = 150;
        int buttonHeight = 20;
        int x = this.width - buttonWidth - 8;
        int y = 6;

        this.addRenderableWidget(Button.builder(
                Component.translatable("metallum.fx.button.open"),
                button -> MetalFxWarningScreen.openIfNotAcknowledged((Screen) (Object) this)
        ).bounds(x, y, buttonWidth, buttonHeight).build());
    }

    private boolean metallum$isMetalBackend() {
        try {
            var device = RenderSystem.getDevice();
            if (device == null) {
                return false;
            }
            return "Metal".equals(device.getDeviceInfo().backendName());
        } catch (Throwable t) {
            return false;
        }
    }
}
