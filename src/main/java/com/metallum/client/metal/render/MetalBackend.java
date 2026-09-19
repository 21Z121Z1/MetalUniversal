package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public class MetalBackend implements GpuBackend {
    @Override
    public @NonNull String getName() {
        return "Metal";
    }

    @Override
    public void loadLibrary() throws BackendCreationException {
        // Metal 由系统框架提供（Metal.framework / QuartzCore.framework），
        // 无需像 Vulkan 那样预加载独立的 loader 动态库。
    }

    @Override
    public void unloadLibrary() {
        // 对称操作：系统框架不归我们卸载。
    }

    /**
     * 创建承载 Metal 绘制目标的窗口。
     *
     * <p>MC 26.3 起窗口层由 GLFW 迁移到 SDL，因此这里必须走 SDL API：
     * {@link SDLVideo#SDL_WINDOW_METAL} 让 SDL 创建出可附加 CAMetalLayer 的原生窗口，
     * 返回值是 SDL 的 {@code SDL_Window*} 句柄（非 Cocoa 句柄）。
     *
     * <p>Cocoa 侧的 {@code NSWindow}/{@code NSView} 在 {@link #createDevice} 中
     * 通过 {@code SDL_GetWindowProperties} 反查得到。
     */
    @Override
    public long createWindow(final @NonNull String title, final int width, final int height, final long flags) {
        final long window = SDLVideo.SDL_CreateWindow(title, width, height, SDLVideo.SDL_WINDOW_METAL | flags);
        if (window != 0L) {
            this.lastCreatedWindow = window;
        }
        return window;
    }

    @Override
    public @NonNull GpuDevice createDevice(final @NonNull GpuDebugOptions debugOptions) throws BackendCreationException {
        // iOS: 必须在任何 Spvc 类加载之前设置 Configuration.SPVC_LIBRARY_NAME，
        // 否则 LWJGL 会通过 dlsym(RTLD_DEFAULT) 拿到 MoltenVK 的精简版 SPIRV-Cross
        // 符号（无 MSL 后端），导致 spvc_context_create_compiler(SPVC_BACKEND_MSL)
        // 失败 -4 "Invalid backend"。详见 MetalNativeBridge.ensureSpvcLibraryConfigured。
        MetalNativeBridge.ensureSpvcLibraryConfigured();

        MemorySegment deviceHandle;
        MemorySegment cocoaWindow;
        MemorySegment cocoaView;
        MemorySegment metalLayer;
        String deviceName;
        deviceHandle = MetalNativeBridge.metallum_create_system_default_device();
        if (MetalNativeBridge.isNullHandle(deviceHandle)) {
            throw new BackendCreationException("MTLCreateSystemDefaultDevice returned null", BackendCreationException.Reason.OTHER);
        }

        deviceName = MetalNativeBridge.metallum_copy_device_name(deviceHandle);
        if (deviceName.isBlank()) deviceName = "<unknown Metal device>";

        double scale;
        if (MetalNativeBridge.isIOS()) {
            // iOS: SDL/UIKit 不暴露可用的 Cocoa 窗口句柄。宿主启动器
            // (e.g. PojavLauncher) 持有 UIWindow/UIView，并通过系统属性发布
            // view 指针（以及可选的 backing scale），以便我们附加 CAMetalLayer。
            cocoaWindow = MemorySegment.NULL;
            cocoaView = readIOSSurfacePointer();
            scale = readIOSScreenScale();
        } else {
            // macOS: 26.3 的窗口由 SDL 创建，NSWindow/NSView 需要通过 SDL 的
            // 窗口属性反查（SDL_PROP_WINDOW_COCOA_WINDOW_POINTER / ..._VIEW_POINTER）。
            // 这里用本后端自己创建的 SDL 窗口句柄；SDL 在 macOS 上可能为每个窗口
            // 维护独立的 property store，因此再兜底取一次当前键盘焦点窗口。
            final long sdlWindow = resolveSdlWindow();
            if (sdlWindow == 0L) {
                throw new BackendCreationException("No SDL window available to query Cocoa handles from", BackendCreationException.Reason.OTHER);
            }

            cocoaWindow = readCocoaHandle(sdlWindow, SDLVideo.SDL_PROP_WINDOW_COCOA_WINDOW_POINTER);
            if (MetalNativeBridge.isNullHandle(cocoaWindow)) {
                throw new BackendCreationException("SDL reported no Cocoa NSWindow handle", BackendCreationException.Reason.OTHER);
            }

            // SDL 只发布 Cocoa 的 NSWindow*，不发布 NSView*（它只为自家的 Metal
            // 渲染器暴露 view tag）。因此 NSView 由原生侧通过 contentView 取出。
            cocoaView = MetalNativeBridge.metallum_NSWindow_contentView(cocoaWindow);
            if (MetalNativeBridge.isNullHandle(cocoaView)) {
                throw new BackendCreationException("metallum_NSWindow_contentView returned null", BackendCreationException.Reason.OTHER);
            }

            scale = MetalNativeBridge.metallum_NSWindow_backingScaleFactor(cocoaWindow);
        }
        if (scale <= 0.0) scale = 1.0;


        if (MetalNativeBridge.isIOS()) {
            // iOS: GameSurfaceView already overrides +layerClass to return
            // CAMetalLayer.class, so cocoaView.layer IS a CAMetalLayer. Use it
            // directly as the render target — this matches what Amethyst's own
            // Vulkan path does in pojavCreateContext (Natives/egl_bridge.m:
            // `return SurfaceViewController.surface.layer`). Creating a new
            // CAMetalLayer and attaching it as a sublayer does NOT work
            // reliably and results in a black screen with audio playing.
            metalLayer = MetalNativeBridge.metallum_ios_get_view_metal_layer(cocoaView, deviceHandle, scale);
            if (MetalNativeBridge.isNullHandle(metalLayer)) {
                throw new BackendCreationException("metallum_ios_get_view_metal_layer returned null", BackendCreationException.Reason.OTHER);
            }
            // No metallum_NSView_setMetalLayer call needed — the layer is
            // already view.layer and is attached to the view by the launcher.
        } else {
            metalLayer = MetalNativeBridge.metallum_create_metal_layer(deviceHandle, scale);
            if (MetalNativeBridge.isNullHandle(metalLayer)) {
                throw new BackendCreationException("Failed to create CAMetalLayer", BackendCreationException.Reason.OTHER);
            }

            MetalNativeBridge.metallum_NSView_setMetalLayer(cocoaView, metalLayer);
        }

        Metallum.LOGGER.info("Metal device: {}", deviceName);

        try {
            // 26.3 起设备抽象分为两层：后端实现（MetalDevice / GpuDeviceBackend）
            // 必须由 FrontendGpuDevice 包装后才能作为公开的 GpuDevice 使用。
            // 这与官方 Vulkan 路径完全一致（VulkanBackend.createDevice 同样
            // `new FrontendGpuDevice(new VulkanDevice(...))`）。
            final MetalDevice metalDevice =
                    new MetalDevice(debugOptions, deviceHandle, metalLayer, deviceName, cocoaView);
            return new FrontendGpuDevice(metalDevice);
        } catch (Throwable throwable) {
            throw new BackendCreationException("Metal device initialization failed: " + throwable.getMessage(), BackendCreationException.Reason.OTHER);
        }
    }

    /**
     * 本后端最近一次通过 {@link #createWindow} 创建的 SDL 窗口句柄。
     *
     * <p>SDL 的 {@code SDL_GetWindowProperties} 需要一个 {@code SDL_Window*}，
     * 而 26.3 的 {@code createDevice} 不再把窗口句柄传进来，因此这里自行记录。
     */
    private long lastCreatedWindow;

    /**
     * 解析用于查询 Cocoa 句柄的 SDL 窗口。
     *
     * <p>优先使用本后端创建的窗口；若该窗口不可用（例如窗口由其他组件创建），
     * 则退回当前键盘焦点窗口。
     */
    private long resolveSdlWindow() {
        if (this.lastCreatedWindow != 0L && SDLVideo.SDL_GetWindowProperties(this.lastCreatedWindow) != 0) {
            return this.lastCreatedWindow;
        }
        return SDLKeyboard.SDL_GetKeyboardFocus();
    }

    /**
     * 从 SDL 窗口属性中取出 Cocoa 侧的原生句柄（{@code NSWindow*} / {@code NSView*}）。
     *
     * <p>SDL 把这些指针以指针属性挂在窗口的 property store 上，键名形如
     * {@code SDL.window.cocoa.window}。取不到时返回 {@link MemorySegment#NULL}。
     */
    private static MemorySegment readCocoaHandle(final long sdlWindow, final String propertyName) {
        final long address = SDLProperties.SDL_GetPointerProperty(
                SDLVideo.SDL_GetWindowProperties(sdlWindow), propertyName, 0L);
        return MemorySegment.ofAddress(address);
    }

    /**
     * Reads the host-provided {@code UIView} pointer on iOS. The host launcher
     * (PojavLauncher) owns the {@code UIView} that backs the game surface and
     * exposes its address via a system property so the mod can attach a
     * {@code CAMetalLayer} to it.
     *
     * <p>Recognised properties (in order of preference):
     * <ul>
     *   <li>{@code metallum.ios.view.pointer} – hex address of the UIView</li>
     *   <li>{@code pojav.view.pointer} – legacy PojavLauncher property</li>
     * </ul>
     */
    private static MemorySegment readIOSSurfacePointer() throws BackendCreationException {
        String raw = System.getProperty("metallum.ios.view.pointer");
        if (raw == null || raw.isBlank()) {
            raw = System.getProperty("pojav.view.pointer");
        }
        if (raw == null || raw.isBlank()) {
            // Amethyst-iOS does not publish the UIView pointer as a system
            // property. Resolve it directly via the ObjC runtime instead:
            // metallum_ios_find_surface_view calls +[SurfaceViewController surface]
            // (with a key-window view-hierarchy fallback) to locate the host
            // launcher's GameSurfaceView. This is the supported path on
            // Amethyst/PojavLauncher_iOS.
            MemorySegment nativeView = MetalNativeBridge.metallum_ios_find_surface_view();
            if (!MetalNativeBridge.isNullHandle(nativeView)) {
                return nativeView;
            }
            throw new BackendCreationException(
                    "Could not locate the iOS surface view. Neither the "
                            + "'metallum.ios.view.pointer'/'pojav.view.pointer' system property "
                            + "nor the +[SurfaceViewController surface] class method returned a UIView. "
                            + "If you are using a launcher other than Amethyst/PojavLauncher, set "
                            + "'-Dmetallum.ios.view.pointer=<hex>' to the UIView address.",
                    BackendCreationException.Reason.OTHER
            );
        }
        raw = raw.trim();
        String hex = raw.startsWith("0x") || raw.startsWith("0X") ? raw.substring(2) : raw;
        long address;
        try {
            address = Long.parseUnsignedLong(hex, 16);
        } catch (NumberFormatException e) {
            throw new BackendCreationException(
                    "Invalid UIView pointer '" + raw + "': expected a hex address",
                    BackendCreationException.Reason.OTHER
            );
        }
        MemorySegment view = MemorySegment.ofAddress(address);
        if (MetalNativeBridge.isNullHandle(view)) {
            throw new BackendCreationException(
                    "Host-provided UIView pointer is null",
                    BackendCreationException.Reason.OTHER
            );
        }
        return view;
    }

    /**
     * Reads the backing scale factor on iOS. Defaults to {@code 2.0} (typical
     * Retina scale) if the host does not publish one.
     */
    private static double readIOSScreenScale() {
        String raw = System.getProperty("metallum.ios.screen.scale");
        if (raw == null || raw.isBlank()) {
            return 2.0;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return 2.0;
        }
    }
}
