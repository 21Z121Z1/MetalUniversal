package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLMetal;
import org.lwjgl.sdl.SDLVideo;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public final class MetalBackend implements GpuBackend {
    /** Publishes Minecraft's complete RenderPearl source provider for lazy Metal PSO compilation. */
    public static ShaderSource captureShaderSource(final ShaderSource shaderSource) {
        ShaderSource effectiveSource = MetalShaderSourceAdapters.withClasspathFallback(shaderSource);
        MetalDevice.captureShaderSource(effectiveSource);
        return effectiveSource;
    }

    @Override
    public @NonNull String getName() {
        return "Metal";
    }

    @Override
    public void loadLibrary() {
        // The bundled Metal bridge is loaded by MetalNativeBridge itself.  Keep
        // SPIRV-Cross selection ahead of any Spvc class initialization on iOS.
        MetalNativeBridge.ensureSpvcLibraryConfigured();
    }

    @Override
    public void unloadLibrary() {
        // The FFM bridge may be shared by live native objects and cannot be
        // safely dlclose'd independently of the process. Resource ownership is
        // handled by MetalDevice/MetalSurface instead.
    }

    @Override
    public long createWindow(
            final @Nullable String title,
            final int width,
            final int height,
            final long flags
    ) {
        // SDL owns the native NSWindow. SDL_WINDOW_METAL is the supported SDL3
        // contract for obtaining its CAMetalLayer later from createSurface().
        return SDLVideo.SDL_CreateWindow(title, width, height, flags | SDLVideo.SDL_WINDOW_METAL);
    }

    @Override
    public @NonNull GpuDevice createDevice(final @NonNull GpuDebugOptions debugOptions)
            throws BackendCreationException {
        MetalNativeBridge.ensureSpvcLibraryConfigured();

        MemorySegment deviceHandle = MetalNativeBridge.metallum_create_system_default_device();
        if (MetalNativeBridge.isNullHandle(deviceHandle)) {
            throw new BackendCreationException(
                    "MTLCreateSystemDefaultDevice returned null",
                    BackendCreationException.Reason.OTHER
            );
        }

        String deviceName = MetalNativeBridge.metallum_copy_device_name(deviceHandle);
        if (deviceName.isBlank()) {
            deviceName = "<unknown Metal device>";
        }
        Metallum.LOGGER.info("Metal device: {}", deviceName);

        try {
            return new FrontendGpuDevice(new MetalDevice(this, debugOptions, deviceHandle, deviceName));
        } catch (Throwable throwable) {
            MetalNativeBridge.metallum_release_object(deviceHandle);
            throw new BackendCreationException(
                    "Metal device initialization failed: " + throwable.getMessage(),
                    BackendCreationException.Reason.OTHER
            );
        }
    }

    SurfaceBinding createSurfaceBinding(final long windowHandle, final MemorySegment deviceHandle) {
        if (windowHandle == 0L) {
            throw new IllegalArgumentException("Metal surface requires a non-null SDL window");
        }

        if (MetalNativeBridge.isIOS()) {
            MemorySegment view = readIOSSurfacePointer();
            double scale = readIOSScreenScale();
            MemorySegment layer = MetalNativeBridge.metallum_ios_get_view_metal_layer(
                    view, deviceHandle, scale > 0.0 ? scale : 1.0
            );
            if (MetalNativeBridge.isNullHandle(layer)) {
                throw new IllegalStateException("metallum_ios_get_view_metal_layer returned null");
            }
            return new SurfaceBinding(layer, 0L);
        }

        long metalView = SDLMetal.SDL_Metal_CreateView(windowHandle);
        if (metalView == 0L) {
            throw new IllegalStateException("SDL_Metal_CreateView returned null");
        }
        long layerAddress = SDLMetal.SDL_Metal_GetLayer(metalView);
        if (layerAddress == 0L) {
            SDLMetal.SDL_Metal_DestroyView(metalView);
            throw new IllegalStateException("SDL_Metal_GetLayer returned null");
        }

        MemorySegment layer = MemorySegment.ofAddress(layerAddress);
        if (MetalNativeBridge.metallum_configure_existing_metal_layer(layer, deviceHandle, 0.0) == 0) {
            SDLMetal.SDL_Metal_DestroyView(metalView);
            throw new IllegalStateException("Failed to configure SDL CAMetalLayer for MetalUniversal");
        }
        return new SurfaceBinding(layer, metalView);
    }

    record SurfaceBinding(MemorySegment layer, long sdlMetalView) {
    }

    private static MemorySegment readIOSSurfacePointer() {
        String raw = System.getProperty("metallum.ios.view.pointer");
        if (raw == null || raw.isBlank()) {
            raw = System.getProperty("pojav.view.pointer");
        }
        if (raw == null || raw.isBlank()) {
            MemorySegment nativeView = MetalNativeBridge.metallum_ios_find_surface_view();
            if (!MetalNativeBridge.isNullHandle(nativeView)) {
                return nativeView;
            }
            throw new IllegalStateException(
                    "Could not locate the iOS surface view. Set "
                            + "-Dmetallum.ios.view.pointer=<hex> for unsupported launchers."
            );
        }

        String value = raw.trim();
        String hex = value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value;
        try {
            MemorySegment view = MemorySegment.ofAddress(Long.parseUnsignedLong(hex, 16));
            if (!MetalNativeBridge.isNullHandle(view)) {
                return view;
            }
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalStateException("Invalid iOS surface pointer: " + value);
    }

    private static double readIOSScreenScale() {
        String raw = System.getProperty("metallum.ios.screen.scale");
        if (raw == null || raw.isBlank()) {
            return 2.0;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ignored) {
            return 2.0;
        }
    }
}
