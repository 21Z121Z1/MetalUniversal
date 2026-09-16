#!/usr/bin/env python3
"""Migrate Metal window/surface ownership from GLFW to Minecraft 26.3 SDL3.

The device is created independently from presentation. A CAMetalLayer is acquired
only when RenderPearl asks the backend to create a surface for an SDL_Window.
macOS uses SDL_Metal_CreateView/SDL_Metal_GetLayer; the existing iOS host-view
path remains intact. The one-shot script fails closed if any expected source
shape has drifted.
"""
from pathlib import Path


def one(text: str, old: str, new: str, path: Path) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match for {old[:80]!r}, found {count}")
    return text.replace(old, new, 1)


def rewrite_backend() -> None:
    path = Path("src/main/java/com/metallum/client/metal/render/MetalBackend.java")
    path.write_text(r'''package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
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
''')


def patch_device() -> None:
    path = Path("src/main/java/com/metallum/client/metal/render/MetalDevice.java")
    text = path.read_text()

    text = one(
        text,
        "import java.util.function.Supplier;\n",
        "import java.util.function.BooleanSupplier;\nimport java.util.function.Supplier;\n",
        path,
    )
    text = one(
        text,
        "    private final MemorySegment metalDeviceHandle;\n    private final MemorySegment metalLayer;\n    private final MemorySegment cocoaView;\n",
        "    private final MetalBackend backend;\n    private final MemorySegment metalDeviceHandle;\n    private MemorySegment metalLayer = MemorySegment.NULL;\n    private boolean presentationInitialized;\n",
        path,
    )
    text = one(text, "    private final boolean metal4MainRenderer;\n", "    private boolean metal4MainRenderer;\n", path)
    text = one(text, "    private ShaderSource activeShaderSource;\n", "    @Nullable\n    private ShaderSource activeShaderSource;\n", path)

    old_sig = '''    MetalDevice(
            final ShaderSource defaultShaderSource,
            final GpuDebugOptions debugOptions,
            final MemorySegment metalDeviceHandle,
            final MemorySegment metalLayer,
            final String deviceName,
            final MemorySegment cocoaView
    ) {
        this.activeShaderSource = defaultShaderSource;
        this.debugOptions = debugOptions;
        this.metalDeviceHandle = metalDeviceHandle;
        this.metalLayer = metalLayer;
        this.cocoaView = cocoaView;
        final boolean presentationBacked = hasPresentationLayer(metalLayer);
'''
    new_sig = '''    MetalDevice(
            final MetalBackend backend,
            final GpuDebugOptions debugOptions,
            final MemorySegment metalDeviceHandle,
            final String deviceName
    ) {
        this.backend = backend;
        this.activeShaderSource = null;
        this.debugOptions = debugOptions;
        this.metalDeviceHandle = metalDeviceHandle;
'''
    text = one(text, old_sig, new_sig, path)

    old_main = '''        boolean metal4MainRenderer = this.metal4Available && METAL4_MAIN_RENDERER;
        // Before metallum_init_pipelines and before any texture or buffer exists:
        // resources created earlier would never enter the set.
        if ((RESIDENCY_SET || metal4MainRenderer)
                && !this.commandQueue.enableResidencySet(metalDeviceHandle)) {
            if (metal4MainRenderer && VISIBILITY_PROBE_FALLBACK_ALLOWED) {
                Metallum.LOGGER.warn(
                        "[metallum] terrain visibility probe Metal 4 residency unavailable; falling back"
                );
                metal4MainRenderer = false;
            } else if (metal4MainRenderer) {
                throw new IllegalStateException("Metal 4 main renderer requires explicit residency");
            }
            if (!metal4MainRenderer) {
                Metallum.LOGGER.warn("[metallum] residency set unavailable; residency stays automatic");
            }
        }
        if (metal4MainRenderer
                && MetalNativeBridge.metallum_metal4_main_renderer_enable(
                        metalDeviceHandle,
                        metalLayer
                ) == 0) {
            if (VISIBILITY_PROBE_FALLBACK_ALLOWED) {
                Metallum.LOGGER.warn(
                        "[metallum] terrain visibility probe Metal 4 main renderer unavailable; falling back"
                );
                metal4MainRenderer = false;
            } else {
                throw new IllegalStateException("Metal 4 main renderer initialization failed");
            }
        }
        this.metal4MainRenderer = metal4MainRenderer;
        // metallum_init_pipelines eagerly builds the swapchain/present PSO and
        // associated presentation samplers. An offscreen MetalDevice has no
        // CAMetalLayer by contract, so do not initialize presentation-only state
        // for it. Render/compute pipelines continue to compile through their
        // normal lazy shipping paths; production devices with a real layer keep
        // the existing eager presentation prewarm unchanged.
        if (presentationBacked) {
            MetalNativeBridge.metallum_init_pipelines(metalDeviceHandle);
        }
'''
    new_main = '''        boolean metal4MainRendererRequested = this.metal4Available && METAL4_MAIN_RENDERER;
        // Explicit residency is device state and can be established before a
        // presentation surface exists. The main render encoder itself is
        // enabled later, once createSurface has a real CAMetalLayer.
        if ((RESIDENCY_SET || metal4MainRendererRequested)
                && !this.commandQueue.enableResidencySet(metalDeviceHandle)) {
            if (metal4MainRendererRequested && VISIBILITY_PROBE_FALLBACK_ALLOWED) {
                Metallum.LOGGER.warn(
                        "[metallum] terrain visibility probe Metal 4 residency unavailable; falling back"
                );
                metal4MainRendererRequested = false;
            } else if (metal4MainRendererRequested) {
                throw new IllegalStateException("Metal 4 main renderer requires explicit residency");
            }
            if (!metal4MainRendererRequested) {
                Metallum.LOGGER.warn("[metallum] residency set unavailable; residency stays automatic");
            }
        }
        this.metal4MainRenderer = false;
'''
    text = one(text, old_main, new_main, path)
    text = text.replace(
        "this.metal4Available && (METAL4_COMPILER || metal4MainRenderer)",
        "this.metal4Available && (METAL4_COMPILER || metal4MainRendererRequested)",
    )
    text = text.replace(
        "metal4Compiler && (METAL4_PRESENT || metal4MainRenderer)",
        "metal4Compiler && (METAL4_PRESENT || metal4MainRendererRequested)",
    )
    text = text.replace(
        "                metal4MainRenderer,\n                METAL4_BARRIER\n",
        "                metal4MainRendererRequested,\n                METAL4_BARRIER\n",
    )

    old_metalfx = '''        current = this;
        // MetalFX owns presentation/HUD/frame-generation state. It is not part
        // of a layerless offscreen device's resource graph and its startup path
        // requires a real CAMetalLayer.
        if (presentationBacked) {
            MetalFxManager.initialize(this);
        }
'''
    text = one(text, old_metalfx, "        current = this;\n", path)

    old_surface = '''    @Override
    public @NonNull GpuSurfaceBackend createSurface(final long windowHandle) {
        return new MetalSurface(this, this.metalLayer);
    }

    MemorySegment metalLayerHandle() {
        return this.metalLayer;
    }
'''
    new_surface = '''    @Override
    public synchronized @NonNull GpuSurfaceBackend createSurface(
            final long windowHandle,
            final @NonNull BooleanSupplier isIconified
    ) {
        if (this.presentationInitialized) {
            throw new IllegalStateException("MetalDevice supports one live presentation surface");
        }

        MetalBackend.SurfaceBinding binding = this.backend.createSurfaceBinding(windowHandle, this.metalDeviceHandle);
        this.metalLayer = binding.layer();
        if (!hasPresentationLayer(this.metalLayer)) {
            throw new IllegalStateException("Metal surface binding returned a null CAMetalLayer");
        }

        boolean requestedMainRenderer = this.metal4Available && METAL4_MAIN_RENDERER;
        if (requestedMainRenderer
                && MetalNativeBridge.metallum_metal4_main_renderer_enable(
                        this.metalDeviceHandle,
                        this.metalLayer
                ) == 0) {
            if (VISIBILITY_PROBE_FALLBACK_ALLOWED) {
                Metallum.LOGGER.warn(
                        "[metallum] terrain visibility probe Metal 4 main renderer unavailable; falling back"
                );
            } else {
                if (binding.sdlMetalView() != 0L) {
                    org.lwjgl.sdl.SDLMetal.SDL_Metal_DestroyView(binding.sdlMetalView());
                }
                this.metalLayer = MemorySegment.NULL;
                throw new IllegalStateException("Metal 4 main renderer initialization failed");
            }
        } else {
            this.metal4MainRenderer = requestedMainRenderer;
        }

        MetalNativeBridge.metallum_init_pipelines(this.metalDeviceHandle);
        MetalFxManager.initialize(this);
        this.presentationInitialized = true;
        return new MetalSurface(this, this.metalLayer, binding.sdlMetalView());
    }

    synchronized void presentationSurfaceClosed(final long sdlMetalView) {
        if (sdlMetalView != 0L) {
            org.lwjgl.sdl.SDLMetal.SDL_Metal_DestroyView(sdlMetalView);
        }
        this.presentationInitialized = false;
        this.metalLayer = MemorySegment.NULL;
    }

    MemorySegment metalLayerHandle() {
        return this.metalLayer;
    }
'''
    text = one(text, old_surface, new_surface, path)

    old_close = '''        this.clearPipelineCache();
        this.drainBufferPool();
        if (!MetalNativeBridge.isNullHandle(this.cocoaView)) {
            try {
                MetalNativeBridge.metallum_NSView_clearLayer(this.cocoaView);
            } catch (Throwable ignored) {
            }
        }
        this.commandQueue.close();
'''
    new_close = '''        this.clearPipelineCache();
        this.drainBufferPool();
        this.commandQueue.close();
'''
    text = one(text, old_close, new_close, path)

    if "presentationBacked" in text or "cocoaView" in text:
        raise SystemExit(f"{path}: stale pre-SDL presentation ownership remains")
    path.write_text(text)


def patch_surface() -> None:
    path = Path("src/main/java/com/metallum/client/metal/render/MetalSurface.java")
    text = path.read_text()
    text = one(
        text,
        "    private final MemorySegment metalLayer;\n",
        "    private final MemorySegment metalLayer;\n    private final long sdlMetalView;\n    private boolean closed;\n",
        path,
    )
    text = one(
        text,
        "    MetalSurface(final MetalDevice device, final MemorySegment metalLayer) {\n        this.device = device;\n        this.metalLayer = metalLayer;\n    }\n",
        "    MetalSurface(final MetalDevice device, final MemorySegment metalLayer, final long sdlMetalView) {\n        this.device = device;\n        this.metalLayer = metalLayer;\n        this.sdlMetalView = sdlMetalView;\n    }\n",
        path,
    )
    text = one(
        text,
        "    @Override\n    public void close() {\n    }\n",
        '''    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.device.presentationSurfaceClosed(this.sdlMetalView);
    }
''',
        path,
    )
    path.write_text(text)


def patch_bridge() -> None:
    path = Path("src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java")
    text = path.read_text()
    text = one(
        text,
        '            createMetalLayer = downcall(lookup, "metallum_create_metal_layer", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, DOUBLE));\n',
        '            createMetalLayer = downcall(lookup, "metallum_create_metal_layer", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, DOUBLE));\n            configureExistingMetalLayer = downcall(lookup, "metallum_configure_existing_metal_layer", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, DOUBLE));\n',
        path,
    )
    text = one(
        text,
        "    private static final MethodHandle createMetalLayer;\n",
        "    private static final MethodHandle createMetalLayer;\n    private static final MethodHandle configureExistingMetalLayer;\n",
        path,
    )
    marker = '''    public static void metallum_NSView_setMetalLayer(final MemorySegment view, final MemorySegment layer) {
'''
    method = '''    public static int metallum_configure_existing_metal_layer(
            final MemorySegment layer,
            final MemorySegment device,
            final double contentsScale
    ) {
        try {
            return (int) configureExistingMetalLayer.invokeExact(segment(layer), segment(device), contentsScale);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_configure_existing_metal_layer", throwable);
        }
    }

'''
    text = one(text, marker, method + marker, path)
    path.write_text(text)


def patch_swift() -> None:
    path = Path("src/main/native/MetallumNative.swift")
    text = path.read_text()
    marker = '''@_cdecl("metallum_NSView_setMetalLayer")
'''
    func = '''/// Configures a CAMetalLayer owned by SDL without taking ownership of it.
/// SDL_Metal_DestroyView remains the sole lifetime authority for this layer.
@_cdecl("metallum_configure_existing_metal_layer")
public func metallum_configure_existing_metal_layer(
    _ rawLayer: UnsafeMutableRawPointer?,
    _ device: MTLDevice,
    _ contentsScale: Double
) -> Int32 {
    guard let rawLayer else { return 0 }
    let layer = Unmanaged<CAMetalLayer>.fromOpaque(rawLayer).takeUnretainedValue()
    layer.device = device
    layer.framebufferOnly = true
    layer.isOpaque = true
    if contentsScale > 0 {
        layer.contentsScale = CGFloat(contentsScale)
    }
    setMetalHudProperties(layer, enabled: false)
    return 1
}

'''
    text = one(text, marker, func + marker, path)
    path.write_text(text)


def main() -> None:
    rewrite_backend()
    patch_device()
    patch_surface()
    patch_bridge()
    patch_swift()
    print("Migrated Metal presentation ownership to SDL3/RenderPearl surface lifecycle")


if __name__ == "__main__":
    main()
