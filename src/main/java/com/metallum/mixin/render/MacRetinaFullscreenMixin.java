package com.metallum.mixin.render;

import com.metallum.Metallum;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
public abstract class MacRetinaFullscreenMixin {
    @Unique
    private static final boolean METALLUM_RETINA_FULLSCREEN = Boolean.parseBoolean(
            System.getProperty("metallum.window.retinaFullscreen", "false")
    );

    @Shadow @Final private long handle;
    @Shadow private boolean fullscreen;
    @Shadow private int x;
    @Shadow private int y;
    @Shadow private int width;
    @Shadow private int height;
    @Shadow private int windowedX;
    @Shadow private int windowedY;
    @Shadow private int windowedWidth;
    @Shadow private int windowedHeight;

    @Unique
    private boolean metallum$retinaFullscreenActive;

    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/platform/Window;createWindow(Lcom/mojang/blaze3d/systems/GpuBackend;IILjava/lang/String;J)J"
            ),
            index = 4
    )
    private long metallum$keepInitialFullscreenWindowOffMonitor(final long monitor) {
        return METALLUM_RETINA_FULLSCREEN ? 0L : monitor;
    }

    @Inject(method = "setMode", at = @At("HEAD"), cancellable = true)
    private void metallum$setRetinaFullscreenMode(final CallbackInfo ci) {
        if (!METALLUM_RETINA_FULLSCREEN) {
            return;
        }

        if (!this.fullscreen) {
            if (this.metallum$retinaFullscreenActive) {
                this.metallum$restoreWindowedMode();
            }
            ci.cancel();
            return;
        }

        Window window = (Window) (Object) this;
        Monitor monitor = window.findBestMonitor();
        if (monitor == null) {
            Metallum.LOGGER.warn("Retina fullscreen could not find a display; remaining windowed");
            this.fullscreen = false;
            if (this.metallum$retinaFullscreenActive) {
                this.metallum$restoreWindowedMode();
            }
            ci.cancel();
            return;
        }

        if (!this.metallum$retinaFullscreenActive) {
            this.windowedX = this.x;
            this.windowedY = this.y;
            this.windowedWidth = Math.max(1, this.width);
            this.windowedHeight = Math.max(1, this.height);
        }

        int[] workX = new int[1];
        int[] workY = new int[1];
        int[] workWidth = new int[1];
        int[] workHeight = new int[1];
        GLFW.glfwGetMonitorWorkarea(monitor.monitor(), workX, workY, workWidth, workHeight);
        if (workWidth[0] <= 0 || workHeight[0] <= 0) {
            Metallum.LOGGER.warn("Retina fullscreen display returned an invalid work area; remaining windowed");
            this.fullscreen = false;
            if (this.metallum$retinaFullscreenActive) {
                this.metallum$restoreWindowedMode();
            }
            ci.cancel();
            return;
        }

        this.x = workX[0];
        this.y = workY[0];
        this.width = workWidth[0];
        this.height = workHeight[0];
        GLFW.glfwSetWindowAttrib(this.handle, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
        GLFW.glfwSetWindowMonitor(
                this.handle,
                0L,
                this.x,
                this.y,
                this.width,
                this.height,
                GLFW.GLFW_DONT_CARE
        );
        if (!this.metallum$retinaFullscreenActive) {
            int[] framebufferWidth = new int[1];
            int[] framebufferHeight = new int[1];
            GLFW.glfwGetFramebufferSize(this.handle, framebufferWidth, framebufferHeight);
            Metallum.LOGGER.info(
                    "Retina borderless fullscreen active: logical={}x{}, framebuffer={}x{}",
                    this.width,
                    this.height,
                    framebufferWidth[0],
                    framebufferHeight[0]
            );
        }
        this.metallum$retinaFullscreenActive = true;
        ci.cancel();
    }

    @Unique
    private void metallum$restoreWindowedMode() {
        this.x = this.windowedX;
        this.y = this.windowedY;
        this.width = Math.max(1, this.windowedWidth);
        this.height = Math.max(1, this.windowedHeight);
        GLFW.glfwSetWindowAttrib(this.handle, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
        GLFW.glfwSetWindowMonitor(
                this.handle,
                0L,
                this.x,
                this.y,
                this.width,
                this.height,
                GLFW.GLFW_DONT_CARE
        );
        this.metallum$retinaFullscreenActive = false;
    }
}
