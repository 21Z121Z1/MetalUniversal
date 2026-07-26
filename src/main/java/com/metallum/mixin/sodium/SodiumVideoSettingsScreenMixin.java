package com.metallum.mixin.sodium;

import com.metallum.client.metal.fx.MetalFxConfig;
import com.metallum.client.metal.fx.MetalFxOptionsScreen;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects a "MetalFX Settings..." button into Sodium's video settings
 * screen. Sodium replaces the vanilla {@code VideoSettingsScreen} with
 * its own custom UI ({@code net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen}),
 * which renders a flat list of options without using the vanilla
 * {@code OptionsList} widget system. We therefore cannot use the vanilla
 * {@code addRenderableWidget} path — instead we inject into Sodium's
 * render loop and draw + handle clicks on a custom button ourselves.
 *
 * <p>The button is drawn at the top-right corner of the screen, above
 * Sodium's option list, mirroring how mods like Iris/Oculus surface their
 * settings entry point on Sodium's screen. Click handling is done by
 * intercepting {@code mouseClicked} when the pointer is within the
 * button bounds.
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

    private static final int BUTTON_WIDTH = 150;
    private static final int BUTTON_HEIGHT = 18;
    private static final int BUTTON_X_OFFSET = 8;
    private static final int BUTTON_Y_OFFSET = 4;

    private int metallum$getButtonX() {
        return this.width - BUTTON_WIDTH - BUTTON_X_OFFSET;
    }

    private int metallum$getButtonY() {
        return BUTTON_Y_OFFSET;
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

    private boolean metallum$isMouseOverButton(double mouseX, double mouseY) {
        int x = metallum$getButtonX();
        int y = metallum$getButtonY();
        return mouseX >= x && mouseX <= x + BUTTON_WIDTH
                && mouseY >= y && mouseY <= y + BUTTON_HEIGHT;
    }

    @Inject(method = "render", at = @At("TAIL"), remap = false)
    private void metallum$renderMetalFxButton(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!metallum$isMetalBackend()) {
            return;
        }
        int x = metallum$getButtonX();
        int y = metallum$getButtonY();
        boolean hovered = metallum$isMouseOverButton(mouseX, mouseY);

        // Draw a vanilla-style button background: filled rectangle with a
        // lighter border when hovered, darker when not. The look matches
        // Minecraft's flat widget theme used on the options screens.
        Component label = Component.translatable("metallum.fx.button.open");
        int bgColor = hovered ? 0xFFC0C0C0 : 0xFFA0A0A0;
        int borderColor = hovered ? 0xFFFFFFFF : 0xFFE0E0E0;
        graphics.fill(x, y, x + BUTTON_WIDTH, y + BUTTON_HEIGHT, bgColor);
        graphics.fill(x, y, x + BUTTON_WIDTH, y + 1, borderColor);
        graphics.fill(x, y + BUTTON_HEIGHT - 1, x + BUTTON_WIDTH, y + BUTTON_HEIGHT, borderColor);
        graphics.fill(x, y, x + 1, y + BUTTON_HEIGHT, borderColor);
        graphics.fill(x + BUTTON_WIDTH - 1, y, x + BUTTON_WIDTH, y + BUTTON_HEIGHT, borderColor);
        graphics.drawCenteredString(this.font, label, x + BUTTON_WIDTH / 2, y + (BUTTON_HEIGHT - 8) / 2, 0xFF000000);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, remap = false)
    private void metallum$onMouseClicked(double mouseX, double mouseY, int button, CallbackInfo ci) {
        if (!metallum$isMetalBackend()) {
            return;
        }
        if (button != 0) {
            return;
        }
        if (!metallum$isMouseOverButton(mouseX, mouseY)) {
            return;
        }
        MetalFxConfig.reload();
        Minecraft.getInstance().setScreen(new MetalFxOptionsScreen((Screen) (Object) this));
        ci.setReturnValue(true);
    }
}
