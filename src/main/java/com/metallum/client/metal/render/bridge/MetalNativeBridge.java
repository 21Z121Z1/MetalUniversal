package com.metallum.client.metal.render.bridge;

import com.metallum.client.metal.render.mtl.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Environment(EnvType.CLIENT)
public final class MetalNativeBridge {
    private static final String MACOS_RESOURCE_PATH = "/natives/macos/libmetallum.dylib";
    private static final String IOS_RESOURCE_PATH = "/natives/ios/libmetallum.dylib";
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG;
    private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT;
    private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE;
    private static final Linker LINKER = Linker.nativeLinker();
    // Reuse native matrix storage on the render thread. JDK 25 rejects heap
    // segments in native downcalls, but the matrices themselves are updated
    // every frame and do not need a new arena allocation each time.
    private static final ThreadLocal<MetalFxMatrixScratch> METALFX_MATRIX_SCRATCH =
            ThreadLocal.withInitial(MetalFxMatrixScratch::new);

    /**
     * iOS (e.g. via PojavLauncher) forbids dlopen of unsigned dylibs from the app's
     * tmp/writable directories due to code-signing restrictions. The native bridge
     * must therefore be loaded as a signed, embedded framework or be statically
     * linked into the launcher binary. We detect that environment and avoid the
     * temp-file extraction path used on macOS.
     */
    public static boolean isIOS() {
        String osName = System.getProperty("os.name", "");
        String osArch = System.getProperty("os.arch", "");
        if (osName.toLowerCase().contains("ios")) {
            return true;
        }
        // PojavLauncher / Amethyst on iOS
        if (System.getProperty("pojav.launcher") != null
                || System.getProperty("org.pojavlauncher") != null) {
            return true;
        }
        // The JVM on iOS (Azul Zulu via PojavLauncher/Amethyst) often reports
        // os.name as "Mac OS X" or "Darwin" because it doesn't distinguish the
        // underlying platform. The most reliable signal is the sandbox path:
        // on iOS, java.io.tmpdir and user.home are always under
        // /private/var/mobile/Containers/Data/Application/<UUID>/, which never
        // exists on macOS. This catches all PojavLauncher/Amethyst variants
        // regardless of how the JDK reports os.name.
        String tmpDir = System.getProperty("java.io.tmpdir", "");
        String userHome = System.getProperty("user.home", "");
        if (tmpDir.contains("/var/mobile/") || tmpDir.contains("/var/containers/")
                || userHome.contains("/var/mobile/") || userHome.contains("/var/containers/")) {
            return true;
        }
        // Fallback: Darwin + aarch64 without a "Mac" os.name
        return osName.toLowerCase().contains("darwin")
                && osArch.toLowerCase().contains("aarch64")
                && !osName.toLowerCase().contains("mac");
    }

    /**
     * 在 iOS 上确保完整版 libspvc.dylib（带 MSL 后端）被加载并设置到
     * {@link org.lwjgl.system.Configuration#SPVC_LIBRARY_NAME}。
     *
     * <p>背景：Amethyst-iOS 捆绑的 libMoltenVK.dylib 内部静态链接了 SPIRV-Cross，
     * 但只编译了 Vulkan 后端（MoltenVK 自己用 C++ API 做 SPIR-V→MSL 转换，不需要 C API
     * 的 MSL 后端）。LWJGL 的 Spvc 类在 iOS 上没有自己的 natives，回退到
     * dlsym(RTLD_DEFAULT, ...) 时找到的是 MoltenVK 的精简版符号，导致
     * spvc_context_create_compiler(SPVC_BACKEND_MSL) 返回 -4 "Invalid backend"。
     *
     * <p>修复：在 LWJGL 的 Spvc 类被首次加载之前，从 jar 中抽取完整版 libspvc.dylib
     * （带 MSL 后端），用 System.load 加载（经 Amethyst 的 hooked dlopen），然后设置
     * Configuration.SPVC_LIBRARY_NAME 指向该路径。LWJGL 加载时会用该绝对路径直接
     * dlopen，dlsym(handle, ...) 只查询该镜像的符号，不会被 MoltenVK 抢占。
     *
     * <p><b>关键：必须在 Spvc 类首次初始化前调用。</b> Spvc.SPVC 是 static final 字段，
     * 在类初始化时通过 Library.loadNative(...) 读取 Configuration.SPVC_LIBRARY_NAME
     * 并缓存结果。一旦 Spvc 类被加载，后续修改 Configuration.SPVC_LIBRARY_NAME 无效。
     * 因此本方法必须在任何可能触发 Spvc 类加载的代码（如 MetalCrossShaderCompiler、
     * VulkanBackend）之前调用。MetalBackend.createDevice 是 Metal 后端的最早入口点，
     * 在此处调用可保证早于 precompilePipeline 和 VulkanBackend 回退。
     *
     * <p>幂等：多次调用安全，只会真正加载一次。
     */
    private static volatile boolean spvcConfigured = false;

    public static void ensureSpvcLibraryConfigured() {
        if (spvcConfigured) return;
        synchronized (MetalNativeBridge.class) {
            if (spvcConfigured) return;
            if (!isIOS()) {
                spvcConfigured = true;
                return;
            }
            try {
                configureBundledSpvcLibrary();
            } catch (Throwable t) {
            } finally {
                spvcConfigured = true;
            }
        }
    }

    /**
     * 从 jar 中抽取完整版 libspvc.dylib 并设置 LWJGL Configuration.SPVC_LIBRARY_NAME。
     * 库文件位于 jar 的 /natives/ios/libspvc.dylib，由 build.gradle 的 buildIOSSpvc
     * 任务从 SPIRV-Cross 源码编译（启用 C API + MSL 后端）。
     */
    private static void configureBundledSpvcLibrary() throws IOException {
        String resourcePath = "/natives/ios/libspvc.dylib";
        try (InputStream stream = MetalNativeBridge.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                return;
            }
            // 抽取到可写目录（与 createIOSSymbolLookup 相同的策略）
            Path tempLib = null;
            IOException lastError = null;
            for (String dirProperty : new String[]{"pojav.launcher.home", "POJAV_HOME", "user.home", "java.io.tmpdir"}) {
                String dir = System.getProperty(dirProperty);
                if (dir == null || dir.isBlank()) continue;
                Path dirPath = Path.of(dir);
                if (!Files.isDirectory(dirPath)) continue;
                try {
                    tempLib = dirPath.resolve("libspvc_metallum.dylib");
                    Files.copy(stream, tempLib, StandardCopyOption.REPLACE_EXISTING);
                    break;
                } catch (IOException e) {
                    lastError = e;
                    tempLib = null;
                }
            }
            if (tempLib == null) {
                if (lastError != null) throw lastError;
                throw new IOException("No writable directory available for libspvc.dylib extraction");
            }
            tempLib.toFile().deleteOnExit();

            // System.load 经 Amethyst 的 hooked dlopen 加载（能绕过 iOS 代码签名）
            System.load(tempLib.toString());
            // 让 LWJGL 在 Spvc 类初始化时用该绝对路径直接 dlopen，避免
            // dlsym(RTLD_DEFAULT) 被 MoltenVK 抢占
            Configuration.SPVC_LIBRARY_NAME.set(tempLib.toString());
        }
    }

    static {
        try {
            SymbolLookup lookup = createSymbolLookup();


            createSystemDefaultDevice = downcallWithoutCritical(lookup, "metallum_create_system_default_device", FunctionDescriptor.of(ValueLayout.ADDRESS));
            copyDeviceName = downcall(lookup, "metallum_copy_device_name", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG));
            NSWindowBackingScaleFactor = downcall(lookup, "metallum_NSWindow_backingScaleFactor", FunctionDescriptor.of(DOUBLE, ValueLayout.ADDRESS));
            createMetalLayer = downcall(lookup, "metallum_create_metal_layer", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, DOUBLE));
            setMetalHud = downcall(lookup, "metallum_set_metal_hud", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT));
            metalHudStatus = downcall(lookup, "metallum_metal_hud_status", FunctionDescriptor.of(INT, ValueLayout.ADDRESS));
            NSViewSetMetalLayer = downcall(lookup, "metallum_NSView_setMetalLayer", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            NSViewClearLayer = downcall(lookup, "metallum_NSView_clearLayer", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            setDebugLabelsEnabled = downcall(lookup, "metallum_set_debug_labels_enabled", FunctionDescriptor.ofVoid(INT));
            systemThermalState = optionalDowncall(lookup, "metallum_system_thermal_state", FunctionDescriptor.of(INT));
            presentationLatestPresentIntervalNanos = optionalDowncall(
                    lookup,
                    "metallum_presentation_latest_present_interval_nanos",
                    FunctionDescriptor.of(LONG)
            );
            presentationLatestDrawableWaitNanos = optionalDowncall(
                    lookup,
                    "metallum_presentation_latest_drawable_wait_nanos",
                    FunctionDescriptor.of(LONG)
            );
            presentationFramesInFlight = optionalDowncall(
                    lookup,
                    "metallum_presentation_frames_in_flight",
                    FunctionDescriptor.of(LONG)
            );
            initPipelines = downcallWithoutCritical(lookup, "metallum_init_pipelines", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            metalfxSupportsSpatial = downcall(lookup, "metallum_metalfx_supports_spatial", FunctionDescriptor.of(INT, ValueLayout.ADDRESS));
            metalfxSupportsTemporal = downcall(lookup, "metallum_metalfx_supports_temporal", FunctionDescriptor.of(INT, ValueLayout.ADDRESS));
            metalfxSupportsFrameGeneration = downcall(lookup, "metallum_metalfx_supports_frame_generation", FunctionDescriptor.of(INT, ValueLayout.ADDRESS));
            metalfxSupportsMotionV2 = optionalDowncall(lookup, "metallum_metalfx_supports_motion_v2", FunctionDescriptor.of(INT, ValueLayout.ADDRESS));
            metalfxClearMotionInputs = optionalDowncall(lookup, "metallum_metalfx_clear_motion_inputs", FunctionDescriptor.of(
                    INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, INT, ValueLayout.ADDRESS
            ));
            metalfxSupportsCutoutReactive = optionalDowncall(
                    lookup,
                    "metallum_metalfx_supports_cutout_reactive",
                    FunctionDescriptor.of(INT, ValueLayout.ADDRESS)
            );
            metalfxApplyCutoutReactive = optionalDowncall(
                    lookup,
                    "metallum_metalfx_apply_cutout_reactive",
                    FunctionDescriptor.of(
                            INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            INT,
                            INT,
                            INT,
                            ValueLayout.ADDRESS
                    )
            );
            metalfxSetReactiveTuning = optionalDowncall(
                    lookup,
                    "metallum_metalfx_set_reactive_tuning",
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_FLOAT,
                            ValueLayout.JAVA_FLOAT,
                            ValueLayout.JAVA_FLOAT,
                            ValueLayout.JAVA_FLOAT,
                            ValueLayout.JAVA_FLOAT,
                            ValueLayout.JAVA_FLOAT,
                            ValueLayout.JAVA_FLOAT
                    )
            );
            metalfxSupportsHandOverlay = optionalDowncall(
                    lookup,
                    "metallum_metalfx_supports_hand_overlay",
                    FunctionDescriptor.of(INT, ValueLayout.ADDRESS)
            );
            metalfxEncodeHandOverlay = optionalDowncall(
                    lookup,
                    "metallum_metalfx_encode_hand_overlay",
                    FunctionDescriptor.of(
                            INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            INT,
                            INT,
                            FLOAT,
                            ValueLayout.ADDRESS
                    )
            );
            metalfxEncodeV2 = optionalDowncall(lookup, "metallum_metalfx_encode_v2", FunctionDescriptor.of(
                    INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    FLOAT, FLOAT, FLOAT, INT, INT, INT, INT, INT, INT
            ));
            metalfxEncode = downcallWithoutCritical(lookup, "metallum_metalfx_encode", FunctionDescriptor.of(
                    INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    FLOAT, FLOAT, INT, INT, INT, INT, INT
            ));
            metalfxTransparencyMask = downcallWithoutCritical(lookup, "metallum_metalfx_mark_transparency", FunctionDescriptor.of(
                    INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    INT, INT
            ));
            metalfxCopy = downcallWithoutCritical(lookup, "metallum_encode_texture_copy", FunctionDescriptor.of(
                    INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, ValueLayout.ADDRESS
            ));
            metalfxShutdown = downcall(lookup, "metallum_metalfx_shutdown", FunctionDescriptor.ofVoid());
            metalfxReleaseScalers = downcall(lookup, "metallum_metalfx_release_scalers", FunctionDescriptor.ofVoid());
            metalfxStopFrameGeneration = downcall(lookup, "metallum_metalfx_stop_frame_generation", FunctionDescriptor.ofVoid());
            metalfxFrameGenerationEncode = downcallWithoutCritical(
                    lookup,
                    "metallum_metalfx_frame_generation_encode",
                    FunctionDescriptor.of(
                            INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            INT, INT,
                            FLOAT, FLOAT, FLOAT, FLOAT, FLOAT, FLOAT, FLOAT,
                            INT, ValueLayout.ADDRESS
                    )
            );

            MTLDeviceMaxMemoryAllocationSize = downcall(lookup, "metallum_MTLDevice_maxMemoryAllocationSize", FunctionDescriptor.of(LONG, ValueLayout.ADDRESS));
            MTLDeviceMakeCommandQueue = downcall(lookup, "metallum_MTLDevice_makeCommandQueue", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MTLCommandQueueMakeCommandBuffer = downcall(lookup, "metallum_MTLCommandQueue_makeCommandBuffer", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MTLCommandBufferCommit = downcall(lookup, "metallum_MTLCommandBuffer_commit", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            createSemaphore = downcall(lookup, "metallum_create_semaphore", FunctionDescriptor.of(ValueLayout.ADDRESS));
            MTLCommandBufferCommitWithSignal = downcall(lookup, "metallum_MTLCommandBuffer_commitWithSignal", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            semaphoreWait = downcallWithoutCritical(lookup, "metallum_semaphore_wait", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, LONG));
            MTLCommandBufferIsCompleted = downcall(lookup, "metallum_MTLCommandBuffer_isCompleted", FunctionDescriptor.of(INT, ValueLayout.ADDRESS));
            MTLCommandBufferCompletedSuccessfully = downcall(lookup, "metallum_MTLCommandBuffer_completedSuccessfully", FunctionDescriptor.of(INT, ValueLayout.ADDRESS));
            MTLCommandBufferGpuStartTime = downcall(lookup, "metallum_MTLCommandBuffer_gpuStartTime", FunctionDescriptor.of(DOUBLE, ValueLayout.ADDRESS));
            MTLCommandBufferGpuEndTime = downcall(lookup, "metallum_MTLCommandBuffer_gpuEndTime", FunctionDescriptor.of(DOUBLE, ValueLayout.ADDRESS));
            MTLCommandBufferWaitUntilCompleted = downcallWithoutCritical(lookup, "metallum_MTLCommandBuffer_waitUntilCompleted", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, LONG));
            MTLCommandBufferPushDebugGroup = downcall(lookup, "metallum_MTLCommandBuffer_pushDebugGroup", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MTLCommandBufferPopDebugGroup = downcall(lookup, "metallum_MTLCommandBuffer_popDebugGroup", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            MTLCommandBufferMakeBlitCommandEncoder = downcall(lookup, "metallum_MTLCommandBuffer_makeBlitCommandEncoder", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MTLCommandEncoderEndEncoding = downcall(lookup, "metallum_MTLCommandEncoder_endEncoding", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            MTLBlitCommandEncoderCopyFromBufferToBuffer = downcall(
                    lookup,
                    "metallum_MTLBlitCommandEncoder_copyFromBufferToBuffer",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS, LONG, LONG)
            );
            MTLBlitCommandEncoderCopyFromBufferToTexture = downcall(
                    lookup,
                    "metallum_MTLBlitCommandEncoder_copyFromBufferToTexture",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG)
            );
            MTLBlitCommandEncoderCopyFromBufferToTextureV2 = downcall(
                    lookup,
                    "metallum_MTLBlitCommandEncoder_copyFromBufferToTexture_v2",
                    FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS,
                            LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG
                    )
            );
            MTLBlitCommandEncoderCopyFromTextureToTexture = downcall(
                    lookup,
                    "metallum_MTLBlitCommandEncoder_copyFromTextureToTexture",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG)
            );
            MTLBlitCommandEncoderCopyFromTextureToBuffer = downcall(
                    lookup,
                    "metallum_MTLBlitCommandEncoder_copyFromTextureToBuffer",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG)
            );
            MTLBlitCommandEncoderCopyFromTextureToBufferV2 = downcall(
                    lookup,
                    "metallum_MTLBlitCommandEncoder_copyFromTextureToBuffer_v2",
                    FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG,
                            LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG
                    )
            );
            MTLDeviceMakeDepthStencilState = downcall(lookup, "metallum_MTLDevice_makeDepthStencilState", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, INT));
            MTLCommandBufferMakeRenderCommandEncoder = downcall(
                    lookup,
                    "metallum_MTLCommandBuffer_makeRenderCommandEncoder",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            DOUBLE,
                            DOUBLE,
                            INT,
                            FLOAT,
                            FLOAT,
                            FLOAT,
                            FLOAT,
                            INT,
                            DOUBLE
                            )
                    );
            MTLCommandBufferMakeRenderCommandEncoderV2 = optionalDowncall(
                    lookup,
                    "metallum_MTLCommandBuffer_makeRenderCommandEncoder_v2",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            INT,
                            ValueLayout.ADDRESS,
                            DOUBLE,
                            DOUBLE,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            INT,
                            DOUBLE,
                            ValueLayout.ADDRESS
                    )
            );
            // RenderPassDescriptorV3 (P2): per-attachment load/store actions.
            // Optional: an older shipping dylib without the symbol falls back
            // to the V2 mapping at the call site.
            MTLCommandBufferMakeRenderCommandEncoderV3 = optionalDowncall(
                    lookup,
                    "metallum_MTLCommandBuffer_makeRenderCommandEncoder_v3",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            INT,
                            INT,
                            DOUBLE,
                            DOUBLE,
                            DOUBLE,
                            ValueLayout.ADDRESS
                    )
            );
            MTLRenderCommandEncoderSetRenderPipelineState = downcall(lookup, "metallum_MTLRenderCommandEncoder_setRenderPipelineState", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MTLRenderCommandEncoderSetDepthStencilState = downcall(lookup, "metallum_MTLRenderCommandEncoder_setDepthStencilState", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MTLRenderCommandEncoderSetDepthBias = downcall(lookup, "metallum_MTLRenderCommandEncoder_setDepthBias", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, FLOAT, FLOAT, FLOAT));
            MTLRenderCommandEncoderSetFrontFacingWinding = downcall(lookup, "metallum_MTLRenderCommandEncoder_setFrontFacingWinding", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT));
            MTLRenderCommandEncoderSetCullMode = downcall(lookup, "metallum_MTLRenderCommandEncoder_setCullMode", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG));
            MTLRenderCommandEncoderSetTriangleFillMode = downcall(lookup, "metallum_MTLRenderCommandEncoder_setTriangleFillMode", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT));
            MTLRenderCommandEncoderSetBuffer = downcall(lookup, "metallum_MTLRenderCommandEncoder_setBuffer", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, INT));
            MTLRenderCommandEncoderSetBufferOffset = downcall(lookup, "metallum_MTLRenderCommandEncoder_setBufferOffset", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, INT));
            MTLRenderCommandEncoderSetTexture = downcall(lookup, "metallum_MTLRenderCommandEncoder_setTexture", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, INT));
            MTLRenderCommandEncoderSetTextureAndSampler = downcall(lookup, "metallum_MTLRenderCommandEncoder_setTextureAndSampler", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, INT));
            MTLRenderCommandEncoderSetScissorRect = downcall(lookup, "metallum_MTLRenderCommandEncoder_setScissorRect", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, LONG, LONG));
            MTLRenderCommandEncoderClearDraw = downcall(
                    lookup,
                    "metallum_MTLRenderCommandEncoder_clearDraw",
                    FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            DOUBLE,
                            DOUBLE,
                            INT,
                            FLOAT,
                            FLOAT,
                            FLOAT,
                            FLOAT,
                            INT,
                            DOUBLE
                    )
            );
            MTLRenderCommandEncoderDrawPrimitives = downcall(lookup, "metallum_MTLRenderCommandEncoder_drawPrimitives", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG));
            MTLRenderCommandEncoderDrawIndexedPrimitives = downcall(
                    lookup,
                    "metallum_MTLRenderCommandEncoder_drawIndexedPrimitives",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, LONG, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG)
            );
            MTLRenderCommandEncoderMultiDrawIndexed = downcall(
                    lookup,
                    "metallum_MTLRenderCommandEncoder_multiDrawIndexed",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG)
            );
            MTLRenderCommandEncoderDrawIndexedPrimitivesIndirect = downcall(
                    lookup,
                    "metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesIndirect",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG)
            );
            MTLDeviceCreateTerrainIndexedIcb = optionalDowncallWithoutCritical(
                    lookup,
                    "metallum_MTLDevice_createTerrainIndexedIcb",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            LONG,
                            LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            INT
                    )
            );
            MTLRenderCommandEncoderExecuteTerrainIcb = optionalDowncallWithoutCritical(
                    lookup,
                    "metallum_MTLRenderCommandEncoder_executeTerrainIcb",
                    FunctionDescriptor.of(
                            INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            INT
                    )
            );
            MTLRenderCommandEncoderDrawPrimitivesIndirect = downcall(
                    lookup,
                    "metallum_MTLRenderCommandEncoder_drawPrimitivesIndirect",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS, LONG, LONG, LONG)
            );
            MTLRenderCommandEncoderDrawIndexedPrimitivesTriangleFan = downcallWithoutCritical(
                    lookup,
                    "metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG)
            );
            MTLCommandBufferClearColorDepthTexturesRegion = downcall(
                    lookup,
                    "metallum_MTLCommandBuffer_clearColorDepthTexturesRegion",
                    FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            FLOAT,
                            FLOAT,
                            FLOAT,
                            FLOAT,
                            ValueLayout.ADDRESS,
                            DOUBLE,
                            INT,
                            INT,
                            INT,
                            INT,
                            ValueLayout.ADDRESS
                    )
            );
            MTLCommandBufferEncodePresentTextureToDrawable = downcallWithoutCritical(
                    lookup,
                    "metallum_MTLCommandBuffer_encodePresentTextureToDrawable",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            MTLCommandBufferEncodePresentTextureToDrawableV2 = optionalDowncallWithoutCritical(
                    lookup,
                    "metallum_MTLCommandBuffer_encodePresentTextureToDrawable_v2",
                    FunctionDescriptor.of(
                            LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS
                    )
            );
            presentationCancel = optionalDowncall(
                    lookup,
                    "metallum_presentation_cancel",
                    FunctionDescriptor.ofVoid(LONG)
            );
            createBuffer = downcall(lookup, "metallum_create_buffer", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG));
            createTexture2d = downcall(
                    lookup,
                    "metallum_create_texture_2d",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG, ValueLayout.ADDRESS)
            );
            createTexture = downcall(
                    lookup,
                    "metallum_create_texture",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG,
                            ValueLayout.ADDRESS
                    )
            );
            createTextureView = downcall(lookup, "metallum_create_texture_view", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG));
            createTextureViewAlphaOne = downcall(
                    lookup,
                    "metallum_create_texture_view_alpha_one",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG)
            );
            createBufferTextureView = downcall(
                    lookup,
                    "metallum_create_buffer_texture_view",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG)
            );
            createSampler = downcall(
                    lookup,
                    "metallum_create_sampler",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, INT, DOUBLE)
            );
            MTLVertexDescriptorCreate = downcallWithoutCritical(
                    lookup,
                    "metallum_MTLVertexDescriptor_create",
                    FunctionDescriptor.of(ValueLayout.ADDRESS)
            );
            MTLVertexDescriptorSetAttribute = downcallWithoutCritical(
                    lookup,
                    "metallum_MTLVertexDescriptor_setAttribute",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, LONG, LONG)
            );
            MTLVertexDescriptorSetLayout = downcallWithoutCritical(
                    lookup,
                    "metallum_MTLVertexDescriptor_setLayout",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, LONG, LONG)
            );
            MTLRenderPipelineDescriptorCreate = downcallWithoutCritical(
                    lookup,
                    "metallum_MTLRenderPipelineDescriptor_create",
                    FunctionDescriptor.of(ValueLayout.ADDRESS)
            );
            createShaderFunction = downcallWithoutCritical(
                    lookup,
                    "metallum_create_shader_function",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            MTLRenderPipelineDescriptorSetCompiledFunctions = downcallWithoutCritical(
                    lookup,
                    "metallum_MTLRenderPipelineDescriptor_setCompiledFunctions",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            MTLRenderPipelineDescriptorSetVertexDescriptor = downcallWithoutCritical(
                    lookup,
                    "metallum_MTLRenderPipelineDescriptor_setVertexDescriptor",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            MTLRenderPipelineDescriptorSetAttachmentFormats = downcallWithoutCritical(
                    lookup,
                    "metallum_MTLRenderPipelineDescriptor_setAttachmentFormats",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, LONG)
            );
            MTLRenderPipelineDescriptorSetColorAttachmentFormat = optionalDowncallWithoutCritical(
                    lookup,
                    "metallum_MTLRenderPipelineDescriptor_setColorAttachmentFormat",
                    FunctionDescriptor.of(INT, ValueLayout.ADDRESS, INT, LONG)
            );
            MTLRenderPipelineDescriptorSetDepthStencilFormats = optionalDowncallWithoutCritical(
                    lookup,
                    "metallum_MTLRenderPipelineDescriptor_setDepthStencilFormats",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG)
            );
            MTLRenderPipelineDescriptorSetColorAttachmentBlendState = optionalDowncallWithoutCritical(
                    lookup,
                    "metallum_MTLRenderPipelineDescriptor_setColorAttachmentBlendState",
                    FunctionDescriptor.of(
                            INT,
                            ValueLayout.ADDRESS,
                            INT,
                            INT,
                            LONG,
                            LONG,
                            LONG,
                            LONG,
                            LONG,
                            LONG,
                            LONG
                    )
            );
            MTLRenderPipelineDescriptorSetBlendState = downcallWithoutCritical(
                    lookup,
                    "metallum_MTLRenderPipelineDescriptor_setBlendState",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT, LONG, LONG, LONG, LONG, LONG, LONG, LONG)
            );
            MTLDeviceMakeRenderPipelineState = downcallWithoutCritical(
                    lookup,
                    "metallum_MTLDevice_makeRenderPipelineState",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            configureLayer = downcall(lookup, "metallum_configure_layer", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, DOUBLE, DOUBLE, INT));
            releaseObject = downcall(lookup, "metallum_release_object", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            setTransferFence = downcall(lookup, "metallum_set_transfer_fence", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            getBufferContents = downcall(lookup, "metallum_get_buffer_contents", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            createFence = downcall(lookup, "metallum_create_fence", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MTLRenderCommandEncoderUpdateFence = downcall(lookup, "MTLRenderCommandEncoder_updateFence", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG));
            MTLRenderCommandEncoderWaitForFence = downcallWithoutCritical(lookup, "MTLRenderCommandEncoder_waitForFence", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG));
            MTLRenderCommandEncoderSetDepthStoreAction = downcall(lookup, "metallum_MTLRenderCommandEncoder_setDepthStoreAction", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT));
            // Optional: older shipping dylibs without color store resolution
            // keep every V3 color store concrete (no deferral, no suppression).
            MTLRenderCommandEncoderSetColorStoreAction = optionalDowncall(
                    lookup,
                    "metallum_MTLRenderCommandEncoder_setColorStoreAction",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT, INT)
            );
            setDeferredDepthStore = downcall(lookup, "metallum_set_deferred_depth_store", FunctionDescriptor.ofVoid(INT));
            metal4Supported = downcall(lookup, "metallum_metal4_supported", FunctionDescriptor.of(INT, ValueLayout.ADDRESS));
            metal4MainQueuePilotValidate = downcall(lookup, "metallum_metal4_main_queue_pilot_validate", FunctionDescriptor.of(INT, ValueLayout.ADDRESS));
            metal4MainRendererEnable = downcall(lookup, "metallum_metal4_main_renderer_enable", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            metal4MainRendererStats = downcall(lookup, "metallum_metal4_main_renderer_stats", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            metal4MetalFxStats = downcall(lookup, "metallum_metal4_metalfx_stats", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            setMetal4CompilerEnabled = downcall(lookup, "metallum_set_metal4_compiler_enabled", FunctionDescriptor.ofVoid(INT));
            setTerrainIcbEnabled = optionalDowncall(lookup, "metallum_set_terrain_icb_enabled", FunctionDescriptor.ofVoid(INT));
            terrainIcbStats = optionalDowncall(
                    lookup,
                    "metallum_terrain_icb_stats",
                    FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            residencySetEnable = downcall(lookup, "metallum_residency_set_enable", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            setMetal4PresentEnabled = downcall(lookup, "metallum_set_metal4_present_enabled", FunctionDescriptor.ofVoid(INT));
            setMetal4BarrierEnabled = downcall(lookup, "metallum_set_metal4_barrier_enabled", FunctionDescriptor.ofVoid(INT));
            setGpuEncoderTimingEnabled = downcall(lookup, "metallum_set_gpu_encoder_timing_enabled", FunctionDescriptor.ofVoid(INT));
            gpuEncoderTimingReset = downcall(lookup, "metallum_gpu_encoder_timing_reset", FunctionDescriptor.ofVoid());
            gpuEncoderTimingCount = downcall(lookup, "metallum_gpu_encoder_timing_count", FunctionDescriptor.of(INT));
            gpuEncoderTimingMilliseconds = downcall(lookup, "metallum_gpu_encoder_timing_milliseconds", FunctionDescriptor.of(DOUBLE, INT));
            gpuEncoderTimingKind = downcall(lookup, "metallum_gpu_encoder_timing_kind", FunctionDescriptor.of(INT, INT));
            gpuEncoderTimingCopyLabel = downcall(lookup, "metallum_gpu_encoder_timing_copy_label", FunctionDescriptor.of(INT, INT, ValueLayout.ADDRESS, LONG));
            // The archive open path performs disk IO inside the native call;
            // avoid the critical-linker fast path like other IO-adjacent calls.
            psoArchiveOpen = downcallWithoutCritical(lookup, "metallum_pso_archive_open", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            psoArchiveFlush = downcallWithoutCritical(lookup, "metallum_pso_archive_flush", FunctionDescriptor.of(INT, ValueLayout.ADDRESS));
            MTLBlitCommandEncoderUpdateFence = downcall(lookup, "MTLBlitCommandEncoder_updateFence", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MTLBlitCommandEncoderWaitForFence = downcallWithoutCritical(lookup, "MTLBlitCommandEncoder_waitForFence", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            // Generic compute / mipmap / compare-sampler ABI (Iris backend B0).
            // Optional so a stale dylib degrades to a clear "unsupported"
            // failure in the Java layer instead of a load-time crash.
            MTLCommandBufferMakeComputeCommandEncoder = optionalDowncall(
                    lookup,
                    "metallum_MTLCommandBuffer_makeComputeCommandEncoder",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            MTLComputeCommandEncoderSetComputePipelineState = optionalDowncall(
                    lookup,
                    "metallum_MTLComputeCommandEncoder_setComputePipelineState",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            MTLComputeCommandEncoderSetBuffer = optionalDowncall(
                    lookup,
                    "metallum_MTLComputeCommandEncoder_setBuffer",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, INT)
            );
            MTLComputeCommandEncoderSetTexture = optionalDowncall(
                    lookup,
                    "metallum_MTLComputeCommandEncoder_setTexture",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT)
            );
            MTLComputeCommandEncoderSetSamplerState = optionalDowncall(
                    lookup,
                    "metallum_MTLComputeCommandEncoder_setSamplerState",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT)
            );
            MTLComputeCommandEncoderDispatchThreadgroups = optionalDowncall(
                    lookup,
                    "metallum_MTLComputeCommandEncoder_dispatchThreadgroups",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT, INT, INT, INT, INT, INT)
            );
            MTLComputeCommandEncoderDispatchThreadgroupsIndirect = optionalDowncall(
                    lookup,
                    "metallum_MTLComputeCommandEncoder_dispatchThreadgroupsIndirect",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, INT, INT, INT)
            );
            MTLComputeCommandEncoderUpdateFence = optionalDowncall(
                    lookup,
                    "metallum_MTLComputeCommandEncoder_updateFence",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            MTLComputeCommandEncoderWaitForFence = optionalDowncall(
                    lookup,
                    "metallum_MTLComputeCommandEncoder_waitForFence",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            MTLDeviceMakeComputePipelineState = optionalDowncallWithoutCritical(
                    lookup,
                    "metallum_MTLDevice_makeComputePipelineState",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            MTLComputePipelineStateMaxTotalThreadsPerThreadgroup = optionalDowncall(
                    lookup,
                    "metallum_MTLComputePipelineState_maxTotalThreadsPerThreadgroup",
                    FunctionDescriptor.of(INT, ValueLayout.ADDRESS)
            );
            MTLBlitCommandEncoderGenerateMipmaps = optionalDowncall(
                    lookup,
                    "metallum_MTLBlitCommandEncoder_generateMipmaps",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            createSamplerV2 = optionalDowncall(
                    lookup,
                    "metallum_create_sampler_v2",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, INT, DOUBLE, INT)
            );
            createSamplerV3 = optionalDowncall(
                    lookup,
                    "metallum_create_sampler_v3",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            LONG, LONG, LONG, LONG, LONG, INT, DOUBLE, INT, INT
                    )
            );
            // metallum_ios_find_surface_view and metallum_ios_get_view_metal_layer
            // only exist in the iOS build of the dylib (guarded by #if os(iOS)
            // in Swift). Register them only on iOS so the macOS build does not
            // fail with a missing symbol.
            if (isIOS() && lookup.find("metallum_ios_find_surface_view").isPresent()) {
                iosFindSurfaceView = downcall(lookup, "metallum_ios_find_surface_view", FunctionDescriptor.of(ValueLayout.ADDRESS));
            } else {
                iosFindSurfaceView = null;
            }
            if (isIOS() && lookup.find("metallum_ios_get_view_metal_layer").isPresent()) {
                // Returns the host UIView's existing CAMetalLayer (view.layer),
                // configured with the given device. See MetallumNative.swift for
                // why we use view.layer directly instead of creating a sublayer.
                iosGetViewMetalLayer = downcall(lookup, "metallum_ios_get_view_metal_layer", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, DOUBLE));
            } else {
                iosGetViewMetalLayer = null;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load Metal native bridge", e);
        }
    }

    /**
     * Resolves the {@link SymbolLookup} for the Metallum native bridge.
     *
     * <p>On macOS the dylib is bundled inside the mod jar and extracted to a
     * temporary file at runtime. On iOS, dynamic loading from a writable tmp
     * directory is rejected by the kernel because the dylib is not part of the
     * app bundle's code signature. We therefore:
     * <ol>
     *   <li>try to load the dylib from the bundled Frameworks directory via
     *       {@code System.loadLibrary} (PojavLauncher exposes embedded, signed
     *       dylibs this way); if that succeeds, the symbols are looked up via
     *       {@link SymbolLookup#loaderLookup()};</li>
     *   <li>fall back to {@link SymbolLookup#loaderLookup()} alone, which finds
     *       symbols that are statically linked into the launcher executable;</li>
     *   <li>as a last resort, attempt the macOS-style temp-file extraction
     *       path so that an embedded signed dylib shipped in the jar still
     *       works on developer devices with relaxed signing.</li>
     * </ol>
     */
    private static SymbolLookup createSymbolLookup() throws IOException {
        if (isIOS()) {
            return createIOSSymbolLookup();
        }
        return extractAndLoad(MACOS_RESOURCE_PATH);
    }

    /**
     * iOS native loading, modelled on how Amethyst-iOS (PojavLauncher fork)
     * loads ALL of its own natives:
     *
     * <ol>
     *   <li>{@code System.loadLibrary} searches {@code java.library.path},
     *       which Amethyst sets to {@code <bundle>/Frameworks/}. This is the
     *       supported deployment path — the dylib is pre-signed at IPA build
     *       time and lives inside the signed app bundle.</li>
     *   <li>{@code SymbolLookup.loaderLookup()} then exposes the symbols from
     *       any library loaded via the JVM's standard loader.</li>
     *   <li>If the dylib is not in Frameworks (e.g. shipped only inside the
     *       Metallum jar), extract it to a writable directory and load it via
     *       {@code System.load}. Amethyst installs a fishhook'd
     *       {@code hooked_dlopen} (see Amethyst {@code Natives/main_hook.m})
     *       that recognises paths under {@code $HOME} or {@code $TMPDIR} and,
     *       together with the in-memory dyld {@code mmap}/{@code fcntl} bypass
     *       ({@code Natives/dyld_bypass_validation.m}), allows unsigned dylibs
     *       from those directories to load when JIT is enabled (TrollStore /
     *       jailbreak). {@code System.load} routes through the JVM's
     *       {@code JVM_LoadLibrary} → {@code dlopen}, which is the exact path
     *       Amethyst's hooks are built around — using it instead of FFM's
     *       {@code libraryLookup} ensures the hooked {@code dlopen} is invoked.
     *       There is NO {@code ldid} binary bundled in Amethyst, so ad-hoc
     *       signing the extracted file would be a no-op; the dyld bypass is
     *       the only mechanism that makes tmp extraction work.</li>
     * </ol>
     */
    private static SymbolLookup createIOSSymbolLookup() throws IOException {
        // 1. Try the app bundle's Frameworks/ directory via java.library.path.
        try {
            System.loadLibrary("metallum");
        } catch (UnsatisfiedLinkError first) {
            try {
                System.loadLibrary("metallum_native");
            } catch (UnsatisfiedLinkError second) {
                // Not in Frameworks; fall through.
            }
        }
        SymbolLookup loader = SymbolLookup.loaderLookup();
        if (loader.find("metallum_create_system_default_device").isPresent()) {
            return loader;
        }

        // 2. Extract to a writable directory and System.load it. Amethyst's
        //    hooked_dlopen recognises $HOME and $TMPDIR paths, so try both.
        //    $HOME / $POJAV_HOME is the PojavLauncher data directory and is the
        //    primary location Amethyst's own hook checks.
        UnsatisfiedLinkError lastError = null;
        for (String dirProperty : new String[] { "pojav.launcher.home", "POJAV_HOME", "user.home", "java.io.tmpdir" }) {
            String dir = System.getProperty(dirProperty);
            if (dir == null || dir.isEmpty()) continue;
            Path dirPath = Path.of(dir);
            if (!Files.isDirectory(dirPath)) continue;
            try {
                Path lib = dirPath.resolve("libmetallum.dylib");
                try (InputStream stream = MetalNativeBridge.class.getResourceAsStream(IOS_RESOURCE_PATH)) {
                    if (stream == null) {
                        throw new IllegalStateException("Missing native library resource: " + IOS_RESOURCE_PATH);
                    }
                    Files.copy(stream, lib, StandardCopyOption.REPLACE_EXISTING);
                }
                lib.toFile().deleteOnExit();
                System.load(lib.toString());
                loader = SymbolLookup.loaderLookup();
                if (loader.find("metallum_create_system_default_device").isPresent()) {
                    return loader;
                }
            } catch (IOException | UnsatisfiedLinkError e) {
                lastError = e instanceof UnsatisfiedLinkError ? (UnsatisfiedLinkError) e : null;
                // Try the next directory.
            }
        }

        throw new IllegalStateException(
            "Could not load the Metallum native bridge on iOS.\n" +
            "Tried: System.loadLibrary (Frameworks/), System.load from\n" +
            "$POJAV_HOME / $HOME / $TMPDIR — all failed.\n" +
            (lastError != null ? "Last loader error: " + lastError.getMessage() + "\n" : "") +
            "\nThe iOS dylib must either:\n" +
            "  (a) be embedded in the Amethyst app bundle at\n" +
            "      <Amethyst.app>/Frameworks/libmetallum.dylib and signed at\n" +
            "      IPA build time (the supported path — Amethyst loads all its\n" +
            "      natives this way via java.library.path); OR\n" +
            "  (b) the device must have JIT enabled (TrollStore / jailbreak)\n" +
            "      so Amethyst's dyld library-validation bypass can load the\n" +
            "      unsigned dylib extracted from the jar.\n" +
            "See README.md -> iOS Installation for details.",
            lastError);
    }

    private static SymbolLookup extractAndLoad(String resourcePath) throws IOException {
        Path tempLib = Files.createTempFile("metallum-native-", ".dylib");
        tempLib.toFile().deleteOnExit();
        try (InputStream stream = MetalNativeBridge.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Missing native library resource: " + resourcePath);
            }
            Files.copy(stream, tempLib, StandardCopyOption.REPLACE_EXISTING);
        }
        return SymbolLookup.libraryLookup(tempLib, Arena.global());
    }


    private static final MethodHandle createSystemDefaultDevice;
    private static final MethodHandle copyDeviceName;
    private static final MethodHandle NSWindowBackingScaleFactor;
    private static final MethodHandle createMetalLayer;
    private static final MethodHandle NSViewSetMetalLayer;
    private static final MethodHandle NSViewClearLayer;
    private static final MethodHandle setDebugLabelsEnabled;
    @Nullable
    private static final MethodHandle systemThermalState;
    @Nullable
    private static final MethodHandle presentationLatestPresentIntervalNanos;
    @Nullable
    private static final MethodHandle presentationLatestDrawableWaitNanos;
    @Nullable
    private static final MethodHandle presentationFramesInFlight;
    private static final MethodHandle MTLDeviceMaxMemoryAllocationSize;
    private static final MethodHandle MTLDeviceMakeCommandQueue;
    private static final MethodHandle MTLCommandQueueMakeCommandBuffer;
    private static final MethodHandle MTLCommandBufferCommit;
    private static final MethodHandle createSemaphore;
    private static final MethodHandle MTLCommandBufferCommitWithSignal;
    private static final MethodHandle semaphoreWait;
    private static final MethodHandle MTLCommandBufferIsCompleted;
    private static final MethodHandle MTLCommandBufferCompletedSuccessfully;
    private static final MethodHandle MTLCommandBufferGpuStartTime;
    private static final MethodHandle MTLCommandBufferGpuEndTime;
    private static final MethodHandle MTLCommandBufferWaitUntilCompleted;
    private static final MethodHandle MTLCommandBufferPushDebugGroup;
    private static final MethodHandle MTLCommandBufferPopDebugGroup;
    private static final MethodHandle MTLCommandBufferMakeBlitCommandEncoder;
    private static final MethodHandle MTLCommandEncoderEndEncoding;
    private static final MethodHandle MTLBlitCommandEncoderCopyFromBufferToBuffer;
    private static final MethodHandle MTLBlitCommandEncoderCopyFromBufferToTexture;
    private static final MethodHandle MTLBlitCommandEncoderCopyFromBufferToTextureV2;
    private static final MethodHandle MTLBlitCommandEncoderCopyFromTextureToTexture;
    private static final MethodHandle MTLBlitCommandEncoderCopyFromTextureToBuffer;
    private static final MethodHandle MTLBlitCommandEncoderCopyFromTextureToBufferV2;
    private static final MethodHandle MTLDeviceMakeDepthStencilState;
    private static final MethodHandle MTLCommandBufferMakeRenderCommandEncoder;
    private static final MethodHandle MTLCommandBufferMakeRenderCommandEncoderV2;
    private static final MethodHandle MTLCommandBufferMakeRenderCommandEncoderV3;

    /** True when the loaded dylib exposes the RenderPassDescriptorV3 symbol. */
    public static boolean renderCommandEncoderV3Available() {
        return MTLCommandBufferMakeRenderCommandEncoderV3 != null;
    }

    /** True when the loaded dylib can resolve deferred color store decisions. */
    public static boolean colorStoreResolutionAvailable() {
        return MTLRenderCommandEncoderSetColorStoreAction != null;
    }
    private static final MethodHandle MTLRenderCommandEncoderSetRenderPipelineState;
    private static final MethodHandle MTLRenderCommandEncoderSetDepthStencilState;
    private static final MethodHandle MTLRenderCommandEncoderSetDepthBias;
    private static final MethodHandle MTLRenderCommandEncoderSetFrontFacingWinding;
    private static final MethodHandle MTLRenderCommandEncoderSetCullMode;
    private static final MethodHandle MTLRenderCommandEncoderSetTriangleFillMode;
    private static final MethodHandle MTLRenderCommandEncoderSetBuffer;
    private static final MethodHandle MTLRenderCommandEncoderSetBufferOffset;
    private static final MethodHandle MTLRenderCommandEncoderSetTexture;
    private static final MethodHandle MTLRenderCommandEncoderSetTextureAndSampler;
    private static final MethodHandle MTLRenderCommandEncoderSetScissorRect;
    private static final MethodHandle MTLRenderCommandEncoderClearDraw;
    private static final MethodHandle MTLRenderCommandEncoderDrawPrimitives;
    private static final MethodHandle MTLRenderCommandEncoderDrawIndexedPrimitives;
    private static final MethodHandle MTLRenderCommandEncoderMultiDrawIndexed;
    private static final MethodHandle MTLRenderCommandEncoderDrawIndexedPrimitivesTriangleFan;
    private static final MethodHandle MTLRenderCommandEncoderDrawIndexedPrimitivesIndirect;
    @Nullable
    private static final MethodHandle MTLDeviceCreateTerrainIndexedIcb;
    @Nullable
    private static final MethodHandle MTLRenderCommandEncoderExecuteTerrainIcb;
    private static final MethodHandle MTLRenderCommandEncoderDrawPrimitivesIndirect;
    private static final MethodHandle MTLCommandBufferClearColorDepthTexturesRegion;
    private static final MethodHandle MTLCommandBufferEncodePresentTextureToDrawable;
    @Nullable
    private static final MethodHandle MTLCommandBufferEncodePresentTextureToDrawableV2;
    @Nullable
    private static final MethodHandle presentationCancel;
    private static final MethodHandle createBuffer;
    private static final MethodHandle createTexture2d;
    private static final MethodHandle createTexture;
    private static final MethodHandle createTextureView;
    private static final MethodHandle createTextureViewAlphaOne;
    private static final MethodHandle createBufferTextureView;
    private static final MethodHandle createSampler;
    private static final MethodHandle MTLVertexDescriptorCreate;
    private static final MethodHandle MTLVertexDescriptorSetAttribute;
    private static final MethodHandle MTLVertexDescriptorSetLayout;
    private static final MethodHandle MTLRenderPipelineDescriptorCreate;
    private static final MethodHandle createShaderFunction;
    private static final MethodHandle MTLRenderPipelineDescriptorSetCompiledFunctions;
    private static final MethodHandle MTLRenderPipelineDescriptorSetVertexDescriptor;
    private static final MethodHandle MTLRenderPipelineDescriptorSetAttachmentFormats;
    private static final MethodHandle MTLRenderPipelineDescriptorSetColorAttachmentFormat;
    private static final MethodHandle MTLRenderPipelineDescriptorSetDepthStencilFormats;
    private static final MethodHandle MTLRenderPipelineDescriptorSetColorAttachmentBlendState;
    private static final MethodHandle MTLRenderPipelineDescriptorSetBlendState;
    private static final MethodHandle MTLDeviceMakeRenderPipelineState;
    private static final MethodHandle setTransferFence;
    private static final MethodHandle configureLayer;
    private static final MethodHandle releaseObject;
    private static final MethodHandle getBufferContents;
    private static final MethodHandle createFence;
    private static final MethodHandle MTLRenderCommandEncoderUpdateFence;
    private static final MethodHandle MTLRenderCommandEncoderWaitForFence;
    private static final MethodHandle MTLRenderCommandEncoderSetDepthStoreAction;
    private static final MethodHandle MTLRenderCommandEncoderSetColorStoreAction;
    private static final MethodHandle setDeferredDepthStore;
    private static final MethodHandle metal4Supported;
    private static final MethodHandle metal4MainQueuePilotValidate;
    private static final MethodHandle metal4MainRendererEnable;
    private static final MethodHandle metal4MainRendererStats;
    private static final MethodHandle metal4MetalFxStats;
    private static final MethodHandle setMetal4CompilerEnabled;
    @Nullable
    private static final MethodHandle setTerrainIcbEnabled;
    @Nullable
    private static final MethodHandle terrainIcbStats;
    private static final MethodHandle setMetalHud;
    private static final MethodHandle metalHudStatus;
    private static final MethodHandle residencySetEnable;
    private static final MethodHandle setMetal4PresentEnabled;
    private static final MethodHandle setMetal4BarrierEnabled;
    private static final MethodHandle setGpuEncoderTimingEnabled;
    private static final MethodHandle gpuEncoderTimingReset;
    private static final MethodHandle gpuEncoderTimingCount;
    private static final MethodHandle gpuEncoderTimingMilliseconds;
    private static final MethodHandle gpuEncoderTimingKind;
    private static final MethodHandle gpuEncoderTimingCopyLabel;
    private static final MethodHandle psoArchiveOpen;
    private static final MethodHandle psoArchiveFlush;
    private static final MethodHandle MTLBlitCommandEncoderUpdateFence;
    private static final MethodHandle MTLBlitCommandEncoderWaitForFence;
    private static final @Nullable MethodHandle MTLCommandBufferMakeComputeCommandEncoder;
    private static final @Nullable MethodHandle MTLComputeCommandEncoderSetComputePipelineState;
    private static final @Nullable MethodHandle MTLComputeCommandEncoderSetBuffer;
    private static final @Nullable MethodHandle MTLComputeCommandEncoderSetTexture;
    private static final @Nullable MethodHandle MTLComputeCommandEncoderSetSamplerState;
    private static final @Nullable MethodHandle MTLComputeCommandEncoderDispatchThreadgroups;
    private static final @Nullable MethodHandle MTLComputeCommandEncoderDispatchThreadgroupsIndirect;
    private static final @Nullable MethodHandle MTLComputeCommandEncoderUpdateFence;
    private static final @Nullable MethodHandle MTLComputeCommandEncoderWaitForFence;
    private static final @Nullable MethodHandle MTLDeviceMakeComputePipelineState;
    private static final @Nullable MethodHandle MTLComputePipelineStateMaxTotalThreadsPerThreadgroup;
    private static final @Nullable MethodHandle MTLBlitCommandEncoderGenerateMipmaps;
    private static final @Nullable MethodHandle createSamplerV2;
    private static final @Nullable MethodHandle createSamplerV3;
    private static final MethodHandle initPipelines;
    private static final MethodHandle metalfxSupportsSpatial;
    private static final MethodHandle metalfxSupportsTemporal;
    private static final MethodHandle metalfxSupportsFrameGeneration;
    @Nullable
    private static final MethodHandle metalfxSupportsMotionV2;
    @Nullable
    private static final MethodHandle metalfxClearMotionInputs;
    @Nullable
    private static final MethodHandle metalfxSupportsCutoutReactive;
    @Nullable
    private static final MethodHandle metalfxApplyCutoutReactive;
    @Nullable
    private static final MethodHandle metalfxSetReactiveTuning;
    @Nullable
    private static final MethodHandle metalfxSupportsHandOverlay;
    @Nullable
    private static final MethodHandle metalfxEncodeHandOverlay;
    @Nullable
    private static final MethodHandle metalfxEncodeV2;
    private static final MethodHandle metalfxEncode;
    private static final MethodHandle metalfxTransparencyMask;
    private static final MethodHandle metalfxCopy;
    private static final MethodHandle metalfxShutdown;
    private static final MethodHandle metalfxReleaseScalers;
    private static final MethodHandle metalfxStopFrameGeneration;
    private static final MethodHandle metalfxFrameGenerationEncode;
    private static final MethodHandle iosFindSurfaceView; // null on macOS
    private static final MethodHandle iosGetViewMetalLayer; // null on macOS


    private static MethodHandle downcall(final SymbolLookup lookup, final String symbol, final FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(lookup.findOrThrow(symbol), descriptor, Linker.Option.critical(false));
    }

    private static MethodHandle optionalDowncall(final SymbolLookup lookup, final String symbol, final FunctionDescriptor descriptor) {
        return lookup.find(symbol)
                .map(address -> LINKER.downcallHandle(address, descriptor, Linker.Option.critical(false)))
                .orElse(null);
    }

    private static MethodHandle downcallWithoutCritical(final SymbolLookup lookup, final String symbol, final FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(lookup.findOrThrow(symbol), descriptor);
    }

    private static MethodHandle optionalDowncallWithoutCritical(
            final SymbolLookup lookup,
            final String symbol,
            final FunctionDescriptor descriptor
    ) {
        return lookup.find(symbol)
                .map(address -> LINKER.downcallHandle(address, descriptor))
                .orElse(null);
    }

    public static MemorySegment metallum_create_system_default_device() {
        try {
            return (MemorySegment) createSystemDefaultDevice.invokeExact();
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_system_default_device", throwable);
        }
    }

    public static String metallum_copy_device_name(final MemorySegment device) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(256L);
            int result = (int) copyDeviceName.invokeExact(segment(device), buffer, 256L);
            return result == 0 ? buffer.getString(0L) : "";
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_copy_device_name", throwable);
        }
    }

    /** Returns the Foundation thermal state (0 nominal through 3 critical), or -1 if unavailable. */
    public static int metallum_system_thermal_state() {
        if (systemThermalState == null) {
            return -1;
        }
        try {
            return (int) systemThermalState.invokeExact();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** Latest interval between two ordinary CAMetalLayer presented callbacks, or -1. */
    public static long metallum_presentation_latest_present_interval_nanos() {
        if (presentationLatestPresentIntervalNanos == null) {
            return -1L;
        }
        try {
            return (long) presentationLatestPresentIntervalNanos.invokeExact();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    /** Latest layer.nextDrawable() wait duration, or -1 when the native symbol is unavailable. */
    public static long metallum_presentation_latest_drawable_wait_nanos() {
        if (presentationLatestDrawableWaitNanos == null) {
            return -1L;
        }
        try {
            return (long) presentationLatestDrawableWaitNanos.invokeExact();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    /** Number of ordinary presents scheduled but not yet resolved, or -1. */
    public static long metallum_presentation_frames_in_flight() {
        if (presentationFramesInFlight == null) {
            return -1L;
        }
        try {
            return (long) presentationFramesInFlight.invokeExact();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    public static double metallum_NSWindow_backingScaleFactor(final MemorySegment window) {
        try {
            return (double) NSWindowBackingScaleFactor.invokeExact(segment(window));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_NSWindow_backingScaleFactor", throwable);
        }
    }

    public static MemorySegment metallum_create_metal_layer(final MemorySegment device, final double contentsScale) {
        try {
            return (MemorySegment) createMetalLayer.invokeExact(segment(device), contentsScale);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_metal_layer", throwable);
        }
    }

    public static void metallum_set_metal_hud(final MemorySegment layer, final boolean enabled) {
        try {
            setMetalHud.invokeExact(segment(layer), enabled ? 1 : 0);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_set_metal_hud", throwable);
        }
    }

    public static int metallum_metal_hud_status(final MemorySegment layer) {
        try {
            return (int) metalHudStatus.invokeExact(segment(layer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metal_hud_status", throwable);
        }
    }

    public static void metallum_NSView_setMetalLayer(final MemorySegment view, final MemorySegment layer) {
        try {
            NSViewSetMetalLayer.invokeExact(segment(view), segment(layer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_NSView_setMetalLayer", throwable);
        }
    }

    public static void metallum_NSView_clearLayer(final MemorySegment view) {
        try {
            NSViewClearLayer.invokeExact(segment(view));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_NSView_clearLayer", throwable);
        }
    }

    /**
     * On iOS, locates the host launcher's game surface {@code UIView} via the
     * Objective-C runtime (calls {@code +[SurfaceViewController surface]} on
     * Amethyst/PojavLauncher, with a key-window view-hierarchy fallback).
     * Returns {@code null} on macOS or if the surface view cannot be found.
     */
    public static MemorySegment metallum_ios_find_surface_view() {
        if (iosFindSurfaceView == null) {
            return MemorySegment.NULL;
        }
        try {
            return (MemorySegment) iosFindSurfaceView.invokeExact();
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_ios_find_surface_view", throwable);
        }
    }

    /**
     * On iOS, returns the host launcher's existing {@code CAMetalLayer} for the
     * given {@code UIView} (i.e. {@code view.layer}), configured with the given
     * Metal device. On Amethyst / PojavLauncher_iOS, {@code GameSurfaceView}
     * overrides {@code +layerClass} to return {@code CAMetalLayer.class}, so
     * {@code view.layer} IS already a {@code CAMetalLayer}. Using it directly
     * matches what Amethyst's own Vulkan path does in {@code pojavCreateContext}
     * (see {@code Natives/egl_bridge.m}).
     *
     * <p>Returns {@code null} on macOS or if the native symbol is unavailable.
     */
    public static MemorySegment metallum_ios_get_view_metal_layer(final MemorySegment view, final MemorySegment device, final double contentsScale) {
        if (iosGetViewMetalLayer == null) {
            return MemorySegment.NULL;
        }
        try {
            return (MemorySegment) iosGetViewMetalLayer.invokeExact(segment(view), segment(device), contentsScale);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_ios_get_view_metal_layer", throwable);
        }
    }

    public static void metallum_set_debug_labels_enabled(final boolean enabled) {
        try {
            setDebugLabelsEnabled.invokeExact(enabled ? 1 : 0);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_set_debug_labels_enabled", throwable);
        }
    }

    public static void metallum_init_pipelines(final MemorySegment device) {
        try {
            initPipelines.invokeExact(segment(device));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_init_pipelines", throwable);
        }
    }

    public static boolean metallum_metalfx_supports_spatial(final MemorySegment device) {
        try {
            return (int) metalfxSupportsSpatial.invokeExact(segment(device)) != 0;
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metalfx_supports_spatial", throwable);
        }
    }

    public static boolean metallum_metalfx_supports_temporal(final MemorySegment device) {
        try {
            return (int) metalfxSupportsTemporal.invokeExact(segment(device)) != 0;
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metalfx_supports_temporal", throwable);
        }
    }

    public static boolean metallum_metalfx_supports_frame_generation(final MemorySegment device) {
        try {
            return (int) metalfxSupportsFrameGeneration.invokeExact(segment(device)) != 0;
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metalfx_supports_frame_generation", throwable);
        }
    }

    /**
     * Returns whether the native bridge can produce and merge the explicit
     * camera/object/validity motion resources used by the temporal path. This
     * is optional so an older bundled dylib can fail closed instead of being
     * called with the v2 ABI.
     */
    public static boolean metallum_metalfx_supports_motion_v2(final MemorySegment device) {
        if (metalfxSupportsMotionV2 == null) {
            return false;
        }
        try {
            return (int) metalfxSupportsMotionV2.invokeExact(segment(device)) != 0;
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metalfx_supports_motion_v2", throwable);
        }
    }

    public static boolean metallum_metalfx_clear_motion_inputs(
            final MemorySegment commandBuffer,
            final MemorySegment objectMotion,
            final MemorySegment objectValidity,
            final int inputWidth,
            final int inputHeight,
            final MemorySegment fence
    ) {
        if (metalfxClearMotionInputs == null) {
            return false;
        }
        try {
            return (int) metalfxClearMotionInputs.invokeExact(
                    segment(commandBuffer), segment(objectMotion), segment(objectValidity),
                    inputWidth, inputHeight, segment(fence)
            ) != 0;
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metalfx_clear_motion_inputs", throwable);
        }
    }

    public static boolean metallum_metalfx_supports_cutout_reactive(final MemorySegment device) {
        if (metalfxSupportsCutoutReactive == null || metalfxApplyCutoutReactive == null) {
            return false;
        }
        try {
            return (int) metalfxSupportsCutoutReactive.invokeExact(segment(device)) != 0;
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metalfx_supports_cutout_reactive", throwable);
        }
    }

    public static void metallum_metalfx_set_reactive_tuning(
            final float cutoutEdgeWeight,
            final float cutoutInteriorWeight,
            final float depthEdgeCap,
            final float transparencyValue,
            final float skyFarPlaneMotion,
            final float disocclusionReactiveCap,
            final float mergeDepthDilation
    ) {
        if (metalfxSetReactiveTuning == null) {
            return;
        }
        try {
            metalfxSetReactiveTuning.invokeExact(
                    cutoutEdgeWeight,
                    cutoutInteriorWeight,
                    depthEdgeCap,
                    transparencyValue,
                    skyFarPlaneMotion,
                    disocclusionReactiveCap,
                    mergeDepthDilation
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metalfx_set_reactive_tuning", throwable);
        }
    }

    public static boolean metallum_metalfx_apply_cutout_reactive(
            final MemorySegment commandBuffer,
            final MemorySegment cutoutCoverage,
            final MemorySegment reactive,
            final int inputWidth,
            final int inputHeight,
            final int radius,
            final MemorySegment fence
    ) {
        if (metalfxApplyCutoutReactive == null) {
            return false;
        }
        try {
            return (int) metalfxApplyCutoutReactive.invokeExact(
                    segment(commandBuffer),
                    segment(cutoutCoverage),
                    segment(reactive),
                    inputWidth,
                    inputHeight,
                    radius,
                    segment(fence)
            ) != 0;
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metalfx_apply_cutout_reactive", throwable);
        }
    }

    public static boolean metallum_metalfx_supports_hand_overlay(final MemorySegment device) {
        if (metalfxSupportsHandOverlay == null || metalfxEncodeHandOverlay == null) {
            return false;
        }
        try {
            return (int) metalfxSupportsHandOverlay.invokeExact(segment(device)) != 0;
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metalfx_supports_hand_overlay", throwable);
        }
    }

    public static boolean metallum_metalfx_encode_hand_overlay(
            final MemorySegment commandBuffer,
            final MemorySegment handDepth,
            final MemorySegment objectMotion,
            final MemorySegment objectValidity,
            final MemorySegment reactive,
            final int inputWidth,
            final int inputHeight,
            final float reactiveBoost,
            final MemorySegment fence
    ) {
        if (metalfxEncodeHandOverlay == null) {
            return false;
        }
        try {
            return (int) metalfxEncodeHandOverlay.invokeExact(
                    segment(commandBuffer),
                    segment(handDepth),
                    segment(objectMotion),
                    segment(objectValidity),
                    segment(reactive),
                    inputWidth,
                    inputHeight,
                    reactiveBoost,
                    segment(fence)
            ) != 0;
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metalfx_encode_hand_overlay", throwable);
        }
    }

    public static boolean metallum_metalfx_mark_transparency(
            final MemorySegment commandBuffer,
            final MemorySegment device,
            @Nullable final MemorySegment translucent,
            @Nullable final MemorySegment itemEntity,
            @Nullable final MemorySegment particles,
            @Nullable final MemorySegment weather,
            @Nullable final MemorySegment clouds,
            final MemorySegment reactive,
            final int inputWidth,
            final int inputHeight
    ) {
        try {
            return (int) metalfxTransparencyMask.invokeExact(
                    segment(commandBuffer), segment(device), segment(translucent), segment(itemEntity),
                    segment(particles), segment(weather), segment(clouds), segment(reactive), inputWidth, inputHeight
            ) != 0;
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metalfx_mark_transparency", throwable);
        }
    }

    public static boolean metallum_metalfx_encode(
            final MemorySegment commandBuffer,
            final MemorySegment device,
            final MemorySegment color,
            final MemorySegment depth,
            final MemorySegment motion,
            final MemorySegment reactive,
            final MemorySegment output,
            @Nullable final float[] currentViewProjection,
            @Nullable final float[] inverseCurrentViewProjection,
            @Nullable final float[] previousViewProjection,
            final float jitterX,
            final float jitterY,
            final int inputWidth,
            final int inputHeight,
            final boolean reset,
            final boolean depthReversed,
            final boolean preserveReactiveMask,
            final MemorySegment fence
    ) {
        try {
            // Native downcalls require native segments; heap-backed float arrays
            // are copied into thread-local storage before calling Swift.
            MetalFxMatrixScratch scratch = METALFX_MATRIX_SCRATCH.get();
            MemorySegment current = scratch.copy(currentViewProjection, scratch.current);
            MemorySegment inverse = scratch.copy(inverseCurrentViewProjection, scratch.inverse);
            MemorySegment previous = scratch.copy(previousViewProjection, scratch.previous);
            return (int) metalfxEncode.invokeExact(
                    segment(commandBuffer), segment(device), segment(color), segment(depth), segment(motion),
                    segment(reactive), segment(output), current, inverse, previous, segment(fence),
                    jitterX, jitterY, inputWidth, inputHeight, reset ? 1 : 0, depthReversed ? 1 : 0,
                    preserveReactiveMask ? 1 : 0
            ) != 0;
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metalfx_encode", throwable);
        }
    }

    /**
     * Versioned temporal encode ABI. The final motion texture is written by
     * native merge after camera reconstruction; object motion is selected only
     * when its validity attachment is non-zero. The old symbol above remains
     * available for older dylibs and for the spatial/camera fallback path.
     */
    public static boolean metallum_metalfx_encode_v2(
            final MemorySegment commandBuffer,
            final MemorySegment device,
            final MemorySegment color,
            final MemorySegment depth,
            @Nullable final MemorySegment handDepth,
            final MemorySegment cameraMotion,
            final MemorySegment objectMotion,
            final MemorySegment objectValidity,
            final MemorySegment disocclusion,
            final MemorySegment motion,
            final MemorySegment reactive,
            final MemorySegment output,
            @Nullable final float[] currentViewProjection,
            @Nullable final float[] inverseCurrentViewProjection,
            @Nullable final float[] previousViewProjection,
            final float jitterX,
            final float jitterY,
            final float handReactiveBoost,
            final int inputWidth,
            final int inputHeight,
            final boolean reset,
            final boolean depthReversed,
            final boolean preserveReactiveMask,
            final boolean emitMotionDiagnostics,
            final MemorySegment fence
    ) {
        if (metalfxEncodeV2 == null) {
            return false;
        }
        try {
            MetalFxMatrixScratch scratch = METALFX_MATRIX_SCRATCH.get();
            MemorySegment current = scratch.copy(currentViewProjection, scratch.current);
            MemorySegment inverse = scratch.copy(inverseCurrentViewProjection, scratch.inverse);
            MemorySegment previous = scratch.copy(previousViewProjection, scratch.previous);
            return (int) metalfxEncodeV2.invokeExact(
                    segment(commandBuffer), segment(device), segment(color), segment(depth),
                    segment(handDepth), segment(cameraMotion), segment(objectMotion), segment(objectValidity),
                    segment(disocclusion), segment(motion), segment(reactive), segment(output),
                    current, inverse, previous, segment(fence), jitterX, jitterY, handReactiveBoost,
                    inputWidth, inputHeight,
                    reset ? 1 : 0, depthReversed ? 1 : 0, preserveReactiveMask ? 1 : 0,
                    emitMotionDiagnostics ? 1 : 0
            ) != 0;
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metalfx_encode_v2", throwable);
        }
    }

    public static boolean metallum_metalfx_frame_generation_encode(
            final MemorySegment commandBuffer,
            final MemorySegment device,
            final MemorySegment layer,
            final MemorySegment sceneColor,
            final MemorySegment nativeSceneColor,
            final MemorySegment uiColor,
            final MemorySegment depth,
            final MemorySegment motion,
            final int inputWidth,
            final int inputHeight,
            final float jitterX,
            final float jitterY,
            final float fieldOfView,
            final float nearPlane,
            final float farPlane,
            final float aspectRatio,
            final float sourceDeltaSeconds,
            final boolean reset,
            final MemorySegment fence
    ) {
        try {
            return (int) metalfxFrameGenerationEncode.invokeExact(
                    segment(commandBuffer), segment(device), segment(layer),
                    segment(sceneColor), segment(nativeSceneColor), segment(uiColor), segment(depth), segment(motion),
                    inputWidth, inputHeight,
                    jitterX, jitterY, fieldOfView, nearPlane, farPlane, aspectRatio,
                    sourceDeltaSeconds,
                    reset ? 1 : 0, segment(fence)
            ) != 0;
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metalfx_frame_generation_encode", throwable);
        }
    }

    private static final class MetalFxMatrixScratch {
        private final Arena arena = Arena.ofConfined();
        private final MemorySegment current = arena.allocate(16L * Float.BYTES, FLOAT.byteAlignment());
        private final MemorySegment inverse = arena.allocate(16L * Float.BYTES, FLOAT.byteAlignment());
        private final MemorySegment previous = arena.allocate(16L * Float.BYTES, FLOAT.byteAlignment());

        private MemorySegment copy(@Nullable final float[] source, final MemorySegment destination) {
            if (source == null) {
                return MemorySegment.NULL;
            }
            for (int index = 0; index < source.length; index++) {
                destination.set(FLOAT, (long) index * Float.BYTES, source[index]);
            }
            return destination;
        }
    }

    public static boolean metallum_encode_texture_copy(
            final MemorySegment commandBuffer,
            final MemorySegment source,
            final MemorySegment destination,
            final boolean linear,
            final MemorySegment fence
    ) {
        try {
            return (int) metalfxCopy.invokeExact(
                    segment(commandBuffer), segment(source), segment(destination), linear ? 1 : 0, segment(fence)
            ) != 0;
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_encode_texture_copy", throwable);
        }
    }

    public static void metallum_metalfx_shutdown() {
        try {
            metalfxShutdown.invokeExact();
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metalfx_shutdown", throwable);
        }
    }

    /**
     * Drops the dimension-keyed MetalFX scalers and their depth history without
     * tearing down the compute pipelines or the frame-generation presenter.
     */
    public static void metallum_metalfx_release_scalers() {
        try {
            metalfxReleaseScalers.invokeExact();
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metalfx_release_scalers", throwable);
        }
    }

    public static void metallum_metalfx_stop_frame_generation() {
        try {
            metalfxStopFrameGeneration.invokeExact();
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metalfx_stop_frame_generation", throwable);
        }
    }


    public static long MTLDevice_maxMemoryAllocationSize(final MemorySegment device) {
        try {
            return (long) MTLDeviceMaxMemoryAllocationSize.invokeExact(segment(device));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLDevice_maxMemoryAllocationSize", throwable);
        }
    }

    public static MemorySegment MTLDevice_makeCommandQueue(final MemorySegment device) {
        try {
            return (MemorySegment) MTLDeviceMakeCommandQueue.invokeExact(segment(device));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLDevice_makeCommandQueue", throwable);
        }
    }

    public static MemorySegment MTLCommandQueue_makeCommandBuffer(final MemorySegment commandQueue, final String label) {
        try (Arena arena = Arena.ofConfined()) {
            return (MemorySegment) MTLCommandQueueMakeCommandBuffer.invokeExact(segment(commandQueue), toCString(arena, label));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandQueue_makeCommandBuffer", throwable);
        }
    }

    public static void MTLCommandBuffer_commit(final MemorySegment commandBuffer) {
        try {
            MTLCommandBufferCommit.invokeExact(segment(commandBuffer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_commit", throwable);
        }
    }

    public static MemorySegment metallum_create_semaphore() {
        try {
            return (MemorySegment) createSemaphore.invokeExact();
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_semaphore", throwable);
        }
    }

    public static void MTLCommandBuffer_commitWithSignal(final MemorySegment commandBuffer, final MemorySegment semaphore) {
        try {
            MTLCommandBufferCommitWithSignal.invokeExact(segment(commandBuffer), segment(semaphore));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_commitWithSignal", throwable);
        }
    }

    public static int metallum_semaphore_wait(final MemorySegment semaphore, final long timeoutMs) {
        try {
            return (int) semaphoreWait.invokeExact(segment(semaphore), timeoutMs);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_semaphore_wait", throwable);
        }
    }

    public static int MTLCommandBuffer_isCompleted(final MemorySegment commandBuffer) {
        try {
            return (int) MTLCommandBufferIsCompleted.invokeExact(segment(commandBuffer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_isCompleted", throwable);
        }
    }

    public static int MTLCommandBuffer_completedSuccessfully(final MemorySegment commandBuffer) {
        try {
            return (int) MTLCommandBufferCompletedSuccessfully.invokeExact(segment(commandBuffer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_completedSuccessfully", throwable);
        }
    }

    public static double MTLCommandBuffer_gpuStartTime(final MemorySegment commandBuffer) {
        try {
            return (double) MTLCommandBufferGpuStartTime.invokeExact(segment(commandBuffer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_gpuStartTime", throwable);
        }
    }

    public static double MTLCommandBuffer_gpuEndTime(final MemorySegment commandBuffer) {
        try {
            return (double) MTLCommandBufferGpuEndTime.invokeExact(segment(commandBuffer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_gpuEndTime", throwable);
        }
    }

    public static int MTLCommandBuffer_waitUntilCompleted(final MemorySegment commandBuffer, final long timeoutMs) {
        try {
            return (int) MTLCommandBufferWaitUntilCompleted.invokeExact(segment(commandBuffer), timeoutMs);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_waitUntilCompleted", throwable);
        }
    }

    public static void MTLCommandBuffer_pushDebugGroup(final MemorySegment commandBuffer, final String label) {
        try (Arena arena = Arena.ofConfined()) {
            MTLCommandBufferPushDebugGroup.invokeExact(segment(commandBuffer), toCString(arena, label));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_pushDebugGroup", throwable);
        }
    }

    public static void MTLCommandBuffer_popDebugGroup(final MemorySegment commandBuffer) {
        try {
            MTLCommandBufferPopDebugGroup.invokeExact(segment(commandBuffer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_popDebugGroup", throwable);
        }
    }

    public static MemorySegment MTLCommandBuffer_makeBlitCommandEncoder(
            final MemorySegment commandBuffer,
            final String label
    ) {
        try (Arena arena = Arena.ofConfined()) {
            return (MemorySegment) MTLCommandBufferMakeBlitCommandEncoder.invokeExact(
                    segment(commandBuffer), toCString(arena, label)
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_makeBlitCommandEncoder", throwable);
        }
    }

    public static void MTLCommandEncoder_endEncoding(final MemorySegment encoder) {
        try {
            MTLCommandEncoderEndEncoding.invokeExact(segment(encoder));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandEncoder_endEncoding", throwable);
        }
    }

    public static void MTLBlitCommandEncoder_copyFromBufferToBuffer(
            final MemorySegment blitEncoder,
            final MemorySegment sourceBuffer,
            final long sourceOffset,
            final MemorySegment destinationBuffer,
            final long destinationOffset,
            final long length
    ) {
        try {
            MTLBlitCommandEncoderCopyFromBufferToBuffer.invokeExact(
                    segment(blitEncoder),
                    segment(sourceBuffer),
                    sourceOffset,
                    segment(destinationBuffer),
                    destinationOffset,
                    length
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLBlitCommandEncoder_copyFromBufferToBuffer", throwable);
        }
    }

    public static void MTLBlitCommandEncoder_copyFromBufferToTexture(
            final MemorySegment blitEncoder,
            final MemorySegment sourceBuffer,
            final long sourceOffset,
            final MemorySegment texture,
            final long mipLevel,
            final long slice,
            final long x,
            final long y,
            final long width,
            final long height,
            final long bytesPerRow,
            final long bytesPerImage
    ) {
        try {
            MTLBlitCommandEncoderCopyFromBufferToTexture.invokeExact(
                    segment(blitEncoder),
                    segment(sourceBuffer),
                    sourceOffset,
                    segment(texture),
                    mipLevel,
                    slice,
                    x,
                    y,
                    width,
                    height,
                    bytesPerRow,
                    bytesPerImage
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLBlitCommandEncoder_copyFromBufferToTexture", throwable);
        }
    }

    public static void MTLBlitCommandEncoder_copyFromBufferToTextureV2(
            final MemorySegment blitEncoder,
            final MemorySegment sourceBuffer,
            final long sourceOffset,
            final MemorySegment texture,
            final long mipLevel,
            final long slice,
            final long x,
            final long y,
            final long z,
            final long width,
            final long height,
            final long depth,
            final long bytesPerRow,
            final long bytesPerImage
    ) {
        try {
            MTLBlitCommandEncoderCopyFromBufferToTextureV2.invokeExact(
                    segment(blitEncoder), segment(sourceBuffer), sourceOffset, segment(texture),
                    mipLevel, slice, x, y, z, width, height, depth, bytesPerRow, bytesPerImage
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLBlitCommandEncoder_copyFromBufferToTexture_v2", throwable);
        }
    }

    public static void MTLBlitCommandEncoder_copyFromTextureToTexture(
            final MemorySegment blitEncoder,
            final MemorySegment sourceTexture,
            final MemorySegment destinationTexture,
            final long mipLevel,
            final long sourceX,
            final long sourceY,
            final long destX,
            final long destY,
            final long width,
            final long height
    ) {
        try {
            MTLBlitCommandEncoderCopyFromTextureToTexture.invokeExact(
                    segment(blitEncoder),
                    segment(sourceTexture),
                    segment(destinationTexture),
                    mipLevel,
                    sourceX,
                    sourceY,
                    destX,
                    destY,
                    width,
                    height
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLBlitCommandEncoder_copyFromTextureToTexture", throwable);
        }
    }

    public static void MTLBlitCommandEncoder_copyFromTextureToBuffer(
            final MemorySegment blitEncoder,
            final MemorySegment sourceTexture,
            final MemorySegment destinationBuffer,
            final long destinationOffset,
            final long mipLevel,
            final long slice,
            final long x,
            final long y,
            final long width,
            final long height,
            final long bytesPerRow,
            final long bytesPerImage
    ) {
        try {
            MTLBlitCommandEncoderCopyFromTextureToBuffer.invokeExact(
                    segment(blitEncoder),
                    segment(sourceTexture),
                    segment(destinationBuffer),
                    destinationOffset,
                    mipLevel,
                    slice,
                    x,
                    y,
                    width,
                    height,
                    bytesPerRow,
                    bytesPerImage
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLBlitCommandEncoder_copyFromTextureToBuffer", throwable);
        }
    }

    public static void MTLBlitCommandEncoder_copyFromTextureToBufferV2(
            final MemorySegment blitEncoder,
            final MemorySegment sourceTexture,
            final MemorySegment destinationBuffer,
            final long destinationOffset,
            final long mipLevel,
            final long slice,
            final long x,
            final long y,
            final long z,
            final long width,
            final long height,
            final long depth,
            final long bytesPerRow,
            final long bytesPerImage
    ) {
        try {
            MTLBlitCommandEncoderCopyFromTextureToBufferV2.invokeExact(
                    segment(blitEncoder), segment(sourceTexture), segment(destinationBuffer), destinationOffset,
                    mipLevel, slice, x, y, z, width, height, depth, bytesPerRow, bytesPerImage
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLBlitCommandEncoder_copyFromTextureToBuffer_v2", throwable);
        }
    }

    public static MemorySegment metallum_create_buffer(final MemorySegment device, final long length, final long options) {
        try {
            return (MemorySegment) createBuffer.invokeExact(segment(device), length, options);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_buffer", throwable);
        }
    }

    public static MemorySegment metallum_create_texture_2d(
            final MemorySegment device,
            final MTLPixelFormat pixelFormat,
            final long width,
            final long height,
            final long depthOrLayers,
            final long mipLevels,
            final long cubeCompatible,
            final long usage,
            final MTLStorageMode storageMode,
            final String label
    ) {
        try (Arena arena = Arena.ofConfined()) {
            return (MemorySegment) createTexture2d.invokeExact(
                    segment(device),
                    pixelFormat.value,
                    width,
                    height,
                    depthOrLayers,
                    mipLevels,
                    cubeCompatible,
                    usage,
                    storageMode.value,
                    toCString(arena, label)
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_texture_2d", throwable);
        }
    }

    public static MemorySegment metallum_create_texture(
            final MemorySegment device,
            final MTLPixelFormat pixelFormat,
            final long width,
            final long height,
            final long depthOrLayers,
            final long mipLevels,
            final long dimension,
            final long cubeCompatible,
            final long usage,
            final MTLStorageMode storageMode,
            final String label
    ) {
        try (Arena arena = Arena.ofConfined()) {
            return (MemorySegment) createTexture.invokeExact(
                    segment(device), pixelFormat.value, width, height, depthOrLayers, mipLevels,
                    dimension, cubeCompatible, usage, storageMode.value, toCString(arena, label)
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_texture", throwable);
        }
    }

    public static MemorySegment metallum_create_texture_view(final MemorySegment texture, final long baseMipLevel, final long mipLevelCount) {
        try {
            return (MemorySegment) createTextureView.invokeExact(segment(texture), baseMipLevel, mipLevelCount);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_texture_view", throwable);
        }
    }

    public static MemorySegment metallum_create_texture_view_alpha_one(
            final MemorySegment texture,
            final long baseMipLevel,
            final long mipLevelCount
    ) {
        try {
            return (MemorySegment) createTextureViewAlphaOne.invokeExact(
                    segment(texture), baseMipLevel, mipLevelCount
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_texture_view_alpha_one", throwable);
        }
    }

    public static MemorySegment metallum_create_buffer_texture_view(
            final MemorySegment buffer,
            final long pixelFormat,
            final long offset,
            final long width,
            final long height,
            final long bytesPerRow
    ) {
        try {
            return (MemorySegment) createBufferTextureView.invokeExact(segment(buffer), pixelFormat, offset, width, height, bytesPerRow);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_buffer_texture_view", throwable);
        }
    }

    public static MemorySegment metallum_create_sampler(
            final MemorySegment device,
            final MTLSamplerAddressMode addressModeU,
            final MTLSamplerAddressMode addressModeV,
            final MTLSamplerMinMagFilter minFilter,
            final MTLSamplerMinMagFilter magFilter,
            final MTLSamplerMipFilter mipFilter,
            final int maxAnisotropy,
            final double lodMaxClamp
    ) {
        try {
            return (MemorySegment) createSampler.invokeExact(
                    segment(device),
                    addressModeU.value,
                    addressModeV.value,
                    minFilter.value,
                    magFilter.value,
                    mipFilter.value,
                    maxAnisotropy,
                    lodMaxClamp
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_sampler", throwable);
        }
    }

    public static MemorySegment MTLDevice_makeDepthStencilState(final MemorySegment device, final MTLCompareFunction depthCompareOp, final int writeDepth) {
        try {
            return (MemorySegment) MTLDeviceMakeDepthStencilState.invokeExact(segment(device), depthCompareOp.value, writeDepth);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLDevice_makeDepthStencilState", throwable);
        }
    }

    public static MemorySegment MTLCommandBuffer_makeRenderCommandEncoder(
            final MemorySegment commandBuffer,
            final MemorySegment colorTexture,
            final MemorySegment depthTexture,
            final double viewportWidth,
            final double viewportHeight,
            final int clearColorEnabled,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final int clearDepthEnabled,
            final double clearDepth
    ) {
        try {
            return (MemorySegment) MTLCommandBufferMakeRenderCommandEncoder.invokeExact(
                    segment(commandBuffer),
                    segment(colorTexture),
                    segment(depthTexture),
                    viewportWidth,
                    viewportHeight,
                    clearColorEnabled,
                    clearColorRed,
                    clearColorGreen,
                    clearColorBlue,
                    clearColorAlpha,
                    clearDepthEnabled,
                    clearDepth
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_makeRenderCommandEncoder", throwable);
        }
    }

    public static MemorySegment MTLCommandBuffer_makeRenderCommandEncoderV2(
            final MemorySegment commandBuffer,
            final MemorySegment[] colorTextures,
            final MemorySegment depthTexture,
            final double viewportWidth,
            final double viewportHeight,
            final int[] clearColorEnabled,
            final float[] clearColors,
            final int clearDepthEnabled,
            final double clearDepth,
            final String label
    ) {
        if (colorTextures == null || clearColorEnabled == null || clearColors == null
                || clearColorEnabled.length != colorTextures.length
                || clearColors.length != colorTextures.length * 4) {
            throw new IllegalArgumentException("MRT texture, clear flag and clear color arrays must have matching lengths");
        }

        if (MTLCommandBufferMakeRenderCommandEncoderV2 == null) {
            if (colorTextures.length > 1) {
                throw new IllegalStateException("Loaded native bridge does not support indexed MRT render encoders");
            }
            MemorySegment colorTexture = colorTextures.length == 0 ? MemorySegment.NULL : colorTextures[0];
            int clearColor = colorTextures.length == 0 ? 0 : clearColorEnabled[0];
            float red = colorTextures.length == 0 ? 0.0F : clearColors[0];
            float green = colorTextures.length == 0 ? 0.0F : clearColors[1];
            float blue = colorTextures.length == 0 ? 0.0F : clearColors[2];
            float alpha = colorTextures.length == 0 ? 0.0F : clearColors[3];
            return MTLCommandBuffer_makeRenderCommandEncoder(
                    commandBuffer,
                    colorTexture,
                    depthTexture,
                    viewportWidth,
                    viewportHeight,
                    clearColor,
                    red,
                    green,
                    blue,
                    alpha,
                    clearDepthEnabled,
                    clearDepth
            );
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment textureArray = colorTextures.length == 0
                    ? MemorySegment.NULL
                    : arena.allocate(ValueLayout.ADDRESS, colorTextures.length);
            MemorySegment clearFlagArray = colorTextures.length == 0
                    ? MemorySegment.NULL
                    : arena.allocate(INT, clearColorEnabled.length);
            MemorySegment clearColorArray = colorTextures.length == 0
                    ? MemorySegment.NULL
                    : arena.allocate(FLOAT, clearColors.length);

            for (int index = 0; index < colorTextures.length; index++) {
                textureArray.setAtIndex(ValueLayout.ADDRESS, index, segment(colorTextures[index]));
                clearFlagArray.setAtIndex(INT, index, clearColorEnabled[index]);
            }
            for (int index = 0; index < clearColors.length; index++) {
                clearColorArray.setAtIndex(FLOAT, index, clearColors[index]);
            }

            try {
                return (MemorySegment) MTLCommandBufferMakeRenderCommandEncoderV2.invokeExact(
                        segment(commandBuffer),
                        textureArray,
                        colorTextures.length,
                        segment(depthTexture),
                        viewportWidth,
                        viewportHeight,
                        clearColorArray,
                        clearFlagArray,
                        clearDepthEnabled,
                        clearDepth,
                        toCString(arena, label)
                );
            } catch (Throwable throwable) {
                throw bridgeFailure("metallum_MTLCommandBuffer_makeRenderCommandEncoder_v2", throwable);
            }
        }
    }

    /**
     * RenderPassDescriptorV3: per-attachment load/store actions.
     * Action encodings match the Swift side: 0=dontCare, 1=load, 2=clear for
     * loads; 0=dontCare, 1=store, 2=deferred(.unknown) for stores. When the
     * loaded dylib has no V3 symbol this falls back to the V2 boolean-clear
     * mapping, which is exactly the conservative projection of the V3 actions
     * the caller builds, so pixels cannot change across the fallback.
     */
    public static MemorySegment MTLCommandBuffer_makeRenderCommandEncoderV3(
            final MemorySegment commandBuffer,
            final MemorySegment[] colorTextures,
            final MemorySegment depthTexture,
            final int[] colorLoadActions,
            final int[] colorStoreActions,
            final float[] clearColors,
            final int depthLoadAction,
            final int depthStoreAction,
            final double clearDepth,
            final double viewportWidth,
            final double viewportHeight,
            final String label
    ) {
        if (colorTextures == null || colorLoadActions == null || colorStoreActions == null || clearColors == null
                || colorLoadActions.length != colorTextures.length
                || colorStoreActions.length != colorTextures.length
                || clearColors.length != colorTextures.length * 4) {
            throw new IllegalArgumentException("MRT texture and action arrays must have matching lengths");
        }

        if (MTLCommandBufferMakeRenderCommandEncoderV3 == null) {
            int[] clearColorEnabled = new int[colorTextures.length];
            for (int index = 0; index < colorTextures.length; index++) {
                clearColorEnabled[index] = colorLoadActions[index] == 2 ? 1 : 0;
            }
            return MTLCommandBuffer_makeRenderCommandEncoderV2(
                    commandBuffer,
                    colorTextures,
                    depthTexture,
                    viewportWidth,
                    viewportHeight,
                    clearColorEnabled,
                    clearColors,
                    depthLoadAction == 2 ? 1 : 0,
                    clearDepth,
                    label
            );
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment textureArray = colorTextures.length == 0
                    ? MemorySegment.NULL
                    : arena.allocate(ValueLayout.ADDRESS, colorTextures.length);
            MemorySegment loadArray = colorTextures.length == 0
                    ? MemorySegment.NULL
                    : arena.allocate(INT, colorLoadActions.length);
            MemorySegment storeArray = colorTextures.length == 0
                    ? MemorySegment.NULL
                    : arena.allocate(INT, colorStoreActions.length);
            MemorySegment clearColorArray = colorTextures.length == 0
                    ? MemorySegment.NULL
                    : arena.allocate(FLOAT, clearColors.length);

            for (int index = 0; index < colorTextures.length; index++) {
                textureArray.setAtIndex(ValueLayout.ADDRESS, index, segment(colorTextures[index]));
                loadArray.setAtIndex(INT, index, colorLoadActions[index]);
                storeArray.setAtIndex(INT, index, colorStoreActions[index]);
            }
            for (int index = 0; index < clearColors.length; index++) {
                clearColorArray.setAtIndex(FLOAT, index, clearColors[index]);
            }

            try {
                return (MemorySegment) MTLCommandBufferMakeRenderCommandEncoderV3.invokeExact(
                        segment(commandBuffer),
                        textureArray,
                        colorTextures.length,
                        segment(depthTexture),
                        loadArray,
                        storeArray,
                        clearColorArray,
                        depthLoadAction,
                        depthStoreAction,
                        clearDepth,
                        viewportWidth,
                        viewportHeight,
                        toCString(arena, label)
                );
            } catch (Throwable throwable) {
                throw bridgeFailure("metallum_MTLCommandBuffer_makeRenderCommandEncoder_v3", throwable);
            }
        }
    }

    public static void MTLRenderCommandEncoder_clearDraw(
            final MemorySegment encoder,
            final MemorySegment colorTexture,
            final MemorySegment depthTexture,
            final double viewportWidth,
            final double viewportHeight,
            final int clearColorEnabled,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final int clearDepthEnabled,
            final double clearDepth
    ) {
        try {
            MTLRenderCommandEncoderClearDraw.invokeExact(
                    segment(encoder),
                    segment(colorTexture),
                    segment(depthTexture),
                    viewportWidth,
                    viewportHeight,
                    clearColorEnabled,
                    clearColorRed,
                    clearColorGreen,
                    clearColorBlue,
                    clearColorAlpha,
                    clearDepthEnabled,
                    clearDepth
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_clearDraw", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setRenderPipelineState(final MemorySegment encoder, final MemorySegment pipeline) {
        try {
            MTLRenderCommandEncoderSetRenderPipelineState.invokeExact(segment(encoder), segment(pipeline));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setRenderPipelineState", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setDepthStencilState(final MemorySegment encoder, final MemorySegment depthStencilState) {
        try {
            MTLRenderCommandEncoderSetDepthStencilState.invokeExact(segment(encoder), segment(depthStencilState));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setDepthStencilState", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setDepthBias(final MemorySegment encoder, final float depthBias, final float slopeScale, final float clamp) {
        try {
            MTLRenderCommandEncoderSetDepthBias.invokeExact(segment(encoder), depthBias, slopeScale, clamp);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setDepthBias", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setFrontFacingWinding(final MemorySegment encoder, final int clockwise) {
        try {
            MTLRenderCommandEncoderSetFrontFacingWinding.invokeExact(segment(encoder), clockwise);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setFrontFacingWinding", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setCullMode(final MemorySegment encoder, final long cullMode) {
        try {
            MTLRenderCommandEncoderSetCullMode.invokeExact(segment(encoder), cullMode);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setCullMode", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setTriangleFillMode(final MemorySegment encoder, final int lines) {
        try {
            MTLRenderCommandEncoderSetTriangleFillMode.invokeExact(segment(encoder), lines);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setTriangleFillMode", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setBuffer(final MemorySegment encoder, final MemorySegment buffer, final long offset, final long index, final int stageMask) {
        try {
            MTLRenderCommandEncoderSetBuffer.invokeExact(segment(encoder), segment(buffer), offset, index, stageMask);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setBuffer", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setBufferOffset(final MemorySegment encoder, final long offset, final long index, final int stageMask) {
        try {
            MTLRenderCommandEncoderSetBufferOffset.invokeExact(segment(encoder), offset, index, stageMask);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setBufferOffset", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setTexture(final MemorySegment encoder, final MemorySegment texture, final long index, final int stageMask) {
        try {
            MTLRenderCommandEncoderSetTexture.invokeExact(segment(encoder), segment(texture), index, stageMask);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setTexture", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setTextureAndSampler(final MemorySegment encoder, final MemorySegment texture, final MemorySegment sampler, final long index, final int stageMask) {
        try {
            MTLRenderCommandEncoderSetTextureAndSampler.invokeExact(segment(encoder), segment(texture), segment(sampler), index, stageMask);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setTextureAndSampler", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setScissorRect(final MemorySegment encoder, final long x, final long y, final long width, final long height) {
        try {
            MTLRenderCommandEncoderSetScissorRect.invokeExact(segment(encoder), x, y, width, height);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setScissorRect", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_drawPrimitives(
            final MemorySegment encoder,
            final long primitiveType,
            final long firstVertex,
            final long vertexCount,
            final long instanceCount,
            final long baseInstance
    ) {
        try {
            MTLRenderCommandEncoderDrawPrimitives.invokeExact(segment(encoder), primitiveType, firstVertex, vertexCount, instanceCount, baseInstance);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_drawPrimitives", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_drawIndexedPrimitives(
            final MemorySegment encoder,
            final long primitiveType,
            final long indexCount,
            final long indexType,
            final MemorySegment indexBuffer,
            final long indexBufferOffset,
            final long instanceCount,
            final long baseVertex,
            final long baseInstance
    ) {
        try {
            MTLRenderCommandEncoderDrawIndexedPrimitives.invokeExact(
                    segment(encoder),
                    primitiveType,
                    indexCount,
                    indexType,
                    segment(indexBuffer),
                    indexBufferOffset,
                    instanceCount,
                    baseVertex,
                    baseInstance
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_drawIndexedPrimitives", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_multiDrawIndexed(
            final MemorySegment encoder,
            final long primitiveType,
            final long indexType,
            final MemorySegment indexBuffer,
            final MemorySegment firstIndexOffsets,
            final MemorySegment indexCounts,
            final MemorySegment vertexOffsets,
            final long drawCount,
            final long instanceCount,
            final long baseInstance
    ) {
        try {
            MTLRenderCommandEncoderMultiDrawIndexed.invokeExact(
                    segment(encoder),
                    primitiveType,
                    indexType,
                    segment(indexBuffer),
                    segment(firstIndexOffsets),
                    segment(indexCounts),
                    segment(vertexOffsets),
                    drawCount,
                    instanceCount,
                    baseInstance
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_multiDrawIndexed", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_drawIndexedPrimitivesIndirect(
            final MemorySegment encoder,
            final long primitiveType,
            final long indexType,
            final MemorySegment indexBuffer,
            final MemorySegment indirectBuffer,
            final long indirectBufferOffset,
            final long drawCount,
            final long stride
    ) {
        try {
            MTLRenderCommandEncoderDrawIndexedPrimitivesIndirect.invokeExact(
                    segment(encoder),
                    primitiveType,
                    indexType,
                    segment(indexBuffer),
                    segment(indirectBuffer),
                    indirectBufferOffset,
                    drawCount,
                    stride
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesIndirect", throwable);
        }
    }

    /** Creates one producer-owned, already encoded terrain ICB. */
    public static MemorySegment MTLDevice_createTerrainIndexedIcb(
            final MemorySegment device,
            final long primitiveType,
            final long indexType,
            final MemorySegment indexBuffer,
            final MemorySegment pipeline,
            final MemorySegment packedCommands,
            final int drawCount
    ) {
        if (MTLDeviceCreateTerrainIndexedIcb == null || drawCount <= 0) {
            return MemorySegment.NULL;
        }
        try {
            return (MemorySegment) MTLDeviceCreateTerrainIndexedIcb.invokeExact(
                    segment(device),
                    primitiveType,
                    indexType,
                    segment(indexBuffer),
                    segment(pipeline),
                    segment(packedCommands),
                    drawCount
            );
        } catch (Throwable ignored) {
            return MemorySegment.NULL;
        }
    }

    /** Executes a producer-owned ICB without decoding or replaying its draws. */
    public static int MTLRenderCommandEncoder_executeTerrainIcb(
            final MemorySegment encoder,
            final MemorySegment indirectCommandBuffer,
            final int drawCount
    ) {
        if (MTLRenderCommandEncoderExecuteTerrainIcb == null || drawCount <= 0) {
            return 0;
        }
        try {
            return (int) MTLRenderCommandEncoderExecuteTerrainIcb.invokeExact(
                    segment(encoder),
                    segment(indirectCommandBuffer),
                    drawCount
            );
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Native counters used by the focused Metal 4 reuse proof. */
    public static int terrainIcbStats(final MemorySegment encoded, final MemorySegment executed) {
        if (terrainIcbStats == null) {
            return 0;
        }
        try {
            return (int) terrainIcbStats.invokeExact(segment(encoded), segment(executed));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static void MTLRenderCommandEncoder_drawPrimitivesIndirect(
            final MemorySegment encoder,
            final long primitiveType,
            final MemorySegment indirectBuffer,
            final long indirectBufferOffset,
            final long drawCount,
            final long stride
    ) {
        try {
            MTLRenderCommandEncoderDrawPrimitivesIndirect.invokeExact(
                    segment(encoder),
                    primitiveType,
                    segment(indirectBuffer),
                    indirectBufferOffset,
                    drawCount,
                    stride
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_drawPrimitivesIndirect", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan(
            final MemorySegment encoder,
            final MemorySegment indexBuffer,
            final MemorySegment fanIndexBuffer,
            final long fanIndexBufferOffset,
            final long indexType,
            final long indexBufferOffset,
            final long indexCount,
            final long baseVertex,
            final long instanceCount,
            final long baseInstance
    ) {
        try {
            MTLRenderCommandEncoderDrawIndexedPrimitivesTriangleFan.invokeExact(
                    segment(encoder),
                    segment(indexBuffer),
                    segment(fanIndexBuffer),
                    fanIndexBufferOffset,
                    indexType,
                    indexBufferOffset,
                    indexCount,
                    baseVertex,
                    instanceCount,
                    baseInstance
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan", throwable);
        }
    }

    public static void MTLCommandBuffer_clearColorDepthTexturesRegion(
            final MemorySegment commandBuffer,
            final MemorySegment colorTexture,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final MemorySegment depthTexture,
            final double clearDepth,
            final int x,
            final int y,
            final int width,
            final int height,
            final MemorySegment globalFence
    ) {
        try {
            MTLCommandBufferClearColorDepthTexturesRegion.invokeExact(
                    segment(commandBuffer),
                    segment(colorTexture),
                    clearColorRed,
                    clearColorGreen,
                    clearColorBlue,
                    clearColorAlpha,
                    segment(depthTexture),
                    clearDepth,
                    x,
                    y,
                    width,
                    height,
                    segment(globalFence)
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_clearColorDepthTexturesRegion", throwable);
        }
    }

    public static MemorySegment metallum_MTLVertexDescriptor_create() {
        try {
            return (MemorySegment) MTLVertexDescriptorCreate.invokeExact();
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLVertexDescriptor_create", throwable);
        }
    }

    public static void metallum_MTLVertexDescriptor_setAttribute(
            final MemorySegment desc,
            final long index,
            final long format,
            final long offset,
            final long bufferIndex
    ) {
        try {
            MTLVertexDescriptorSetAttribute.invokeExact(segment(desc), index, format, offset, bufferIndex);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLVertexDescriptor_setAttribute", throwable);
        }
    }

    public static void metallum_MTLVertexDescriptor_setLayout(
            final MemorySegment desc,
            final long bufferIndex,
            final long stride,
            final long stepFunction,
            final long stepRate
    ) {
        try {
            MTLVertexDescriptorSetLayout.invokeExact(segment(desc), bufferIndex, stride, stepFunction, stepRate);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLVertexDescriptor_setLayout", throwable);
        }
    }

    public static MemorySegment metallum_MTLRenderPipelineDescriptor_create() {
        try {
            return (MemorySegment) MTLRenderPipelineDescriptorCreate.invokeExact();
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderPipelineDescriptor_create", throwable);
        }
    }

    public static MemorySegment metallum_create_shader_function(
            final MemorySegment device,
            final String source,
            final String entryPoint
    ) {
        try (Arena arena = Arena.ofConfined()) {
            return (MemorySegment) createShaderFunction.invokeExact(
                    segment(device),
                    toCString(arena, source),
                    toCString(arena, entryPoint)
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_shader_function", throwable);
        }
    }

    public static void metallum_MTLRenderPipelineDescriptor_setCompiledFunctions(
            final MemorySegment desc,
            final MemorySegment vertexFunction,
            final MemorySegment fragmentFunction
    ) {
        try {
            MTLRenderPipelineDescriptorSetCompiledFunctions.invokeExact(
                    segment(desc),
                    segment(vertexFunction),
                    segment(fragmentFunction)
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderPipelineDescriptor_setCompiledFunctions", throwable);
        }
    }

    public static void metallum_MTLRenderPipelineDescriptor_setVertexDescriptor(
            final MemorySegment desc,
            final MemorySegment vertexDesc
    ) {
        try {
            MTLRenderPipelineDescriptorSetVertexDescriptor.invokeExact(segment(desc), segment(vertexDesc));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderPipelineDescriptor_setVertexDescriptor", throwable);
        }
    }

    public static void metallum_MTLRenderPipelineDescriptor_setAttachmentFormats(
            final MemorySegment desc,
            final MTLPixelFormat colorFormat,
            final MTLPixelFormat depthFormat,
            final MTLPixelFormat stencilFormat
    ) {
        try {
            MTLRenderPipelineDescriptorSetAttachmentFormats.invokeExact(segment(desc), colorFormat.value, depthFormat.value, stencilFormat.value);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderPipelineDescriptor_setAttachmentFormats", throwable);
        }
    }

    public static void metallum_MTLRenderPipelineDescriptor_setColorAttachmentFormat(
            final MemorySegment desc,
            final int index,
            final MTLPixelFormat format
    ) {
        if (MTLRenderPipelineDescriptorSetColorAttachmentFormat == null) {
            if (index != 0) {
                throw new IllegalStateException("Loaded native bridge does not support indexed color attachment formats");
            }
            setAttachmentFormatLegacy(desc, format);
            return;
        }
        try {
            int result = (int) MTLRenderPipelineDescriptorSetColorAttachmentFormat.invokeExact(
                    segment(desc), index, format.value
            );
            if (result == 0) {
                throw new IllegalArgumentException("Native bridge rejected color attachment index " + index);
            }
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderPipelineDescriptor_setColorAttachmentFormat", throwable);
        }
    }

    public static void metallum_MTLRenderPipelineDescriptor_setDepthStencilFormats(
            final MemorySegment desc,
            final MTLPixelFormat depthFormat,
            final MTLPixelFormat stencilFormat
    ) {
        if (MTLRenderPipelineDescriptorSetDepthStencilFormats == null) {
            throw new IllegalStateException("Loaded native bridge does not support independent depth/stencil formats");
        }
        try {
            MTLRenderPipelineDescriptorSetDepthStencilFormats.invokeExact(
                    segment(desc), depthFormat.value, stencilFormat.value
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderPipelineDescriptor_setDepthStencilFormats", throwable);
        }
    }

    public static void metallum_MTLRenderPipelineDescriptor_setColorAttachmentBlendState(
            final MemorySegment desc,
            final int index,
            final boolean enabled,
            final long srcRgb,
            final long dstRgb,
            final long opRgb,
            final long srcAlpha,
            final long dstAlpha,
            final long opAlpha,
            final long writeMask
    ) {
        if (MTLRenderPipelineDescriptorSetColorAttachmentBlendState == null) {
            if (index != 0) {
                throw new IllegalStateException("Loaded native bridge does not support indexed color attachment blend state");
            }
            metallum_MTLRenderPipelineDescriptor_setBlendState(desc, enabled ? 1 : 0, srcRgb, dstRgb, opRgb, srcAlpha, dstAlpha, opAlpha, writeMask);
            return;
        }
        try {
            int result = (int) MTLRenderPipelineDescriptorSetColorAttachmentBlendState.invokeExact(
                    segment(desc), index, enabled ? 1 : 0,
                    srcRgb, dstRgb, opRgb, srcAlpha, dstAlpha, opAlpha, writeMask
            );
            if (result == 0) {
                throw new IllegalArgumentException("Native bridge rejected color attachment index " + index);
            }
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderPipelineDescriptor_setColorAttachmentBlendState", throwable);
        }
    }

    private static void setAttachmentFormatLegacy(final MemorySegment desc, final MTLPixelFormat format) {
        metallum_MTLRenderPipelineDescriptor_setAttachmentFormats(
                desc,
                format,
                MTLPixelFormat.Invalid,
                MTLPixelFormat.Invalid
        );
    }

    public static void metallum_MTLRenderPipelineDescriptor_setBlendState(
            final MemorySegment desc,
            final int enabled,
            final long srcRgb,
            final long dstRgb,
            final long opRgb,
            final long srcAlpha,
            final long dstAlpha,
            final long opAlpha,
            final long writeMask
    ) {
        try {
            MTLRenderPipelineDescriptorSetBlendState.invokeExact(
                    segment(desc),
                    enabled,
                    srcRgb,
                    dstRgb,
                    opRgb,
                    srcAlpha,
                    dstAlpha,
                    opAlpha,
                    writeMask
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderPipelineDescriptor_setBlendState", throwable);
        }
    }

    public static MemorySegment metallum_MTLDevice_makeRenderPipelineState(
            final MemorySegment device,
            final MemorySegment descriptor
    ) {
        try {
            return (MemorySegment) MTLDeviceMakeRenderPipelineState.invokeExact(segment(device), segment(descriptor));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLDevice_makeRenderPipelineState", throwable);
        }
    }

    public static void metallum_configure_layer(final MemorySegment layer, final double width, final double height, final int immediatePresentMode) {
        try {
            configureLayer.invokeExact(segment(layer), width, height, immediatePresentMode);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_configure_layer", throwable);
        }
    }

    public static long MTLCommandBuffer_encodePresentTextureToDrawable(final MemorySegment commandBuffer, final MemorySegment layer, final MemorySegment sourceTexture, final MemorySegment globalFence) {
        try {
            if (MTLCommandBufferEncodePresentTextureToDrawableV2 != null) {
                return (long) MTLCommandBufferEncodePresentTextureToDrawableV2.invokeExact(
                        segment(commandBuffer), segment(layer), segment(sourceTexture), segment(globalFence)
                );
            }
            MTLCommandBufferEncodePresentTextureToDrawable.invokeExact(
                    segment(commandBuffer), segment(layer), segment(sourceTexture), segment(globalFence)
            );
            return 0L;
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_encodePresentTextureToDrawable", throwable);
        }
    }

    public static void metallum_presentation_cancel(final long identifier) {
        if (identifier <= 0L || presentationCancel == null) {
            return;
        }
        try {
            presentationCancel.invokeExact(identifier);
        } catch (Throwable ignored) {
            // Cancellation is best-effort for an optional ABI; native
            // presented/completion callbacks remain authoritative after commit.
        }
    }

    public static void metallum_release_object(final MemorySegment object) {
        try {
            releaseObject.invokeExact(segment(object));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_release_object", throwable);
        }
    }

    /**
     * Publishes the split-fence transfer fence to the native side (Swift
     * retains it), or clears it with {@link MemorySegment#NULL} before the
     * Java owner releases the fence. Non-null enables the split-fence path
     * for natively encoded blits (frame-generation input copies).
     */
    public static void metallum_set_transfer_fence(final MemorySegment fence) {
        try {
            setTransferFence.invokeExact(segment(fence));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_set_transfer_fence", throwable);
        }
    }

    public static MemorySegment metallum_create_fence(final MemorySegment device) {
        try {
            return (MemorySegment) createFence.invokeExact(segment(device));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_fence", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_updateFence(final MemorySegment encoder, final MemorySegment fence, final long stages) {
        try {
            MTLRenderCommandEncoderUpdateFence.invokeExact(segment(encoder), segment(fence), stages);
        } catch (Throwable throwable) {
            throw bridgeFailure("MTLRenderCommandEncoder_updateFence", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setDepthStoreAction(final MemorySegment encoder, final int store) {
        try {
            MTLRenderCommandEncoderSetDepthStoreAction.invokeExact(segment(encoder), store);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setDepthStoreAction", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setColorStoreAction(
            final MemorySegment encoder,
            final int index,
            final int store
    ) {
        if (MTLRenderCommandEncoderSetColorStoreAction == null) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setColorStoreAction",
                    new IllegalStateException("loaded native bridge has no color store resolution symbol"));
        }
        try {
            MTLRenderCommandEncoderSetColorStoreAction.invokeExact(segment(encoder), index, store);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setColorStoreAction", throwable);
        }
    }

    public static void metallum_set_deferred_depth_store(final int enabled) {
        try {
            setDeferredDepthStore.invokeExact(enabled);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_set_deferred_depth_store", throwable);
        }
    }

    /**
     * Non-zero when this device and this dylib's SDK both support Metal 4.
     * Answers the run-time half of the Metal 4 capability gate; the requested
     * half is the {@code metallum.opt.metal4} system property. Both must hold
     * before any {@code MTL4*} path is used.
     */
    public static int metallum_metal4_supported(final MemorySegment device) {
        try {
            return (int) metal4Supported.invokeExact(segment(device));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metal4_supported", throwable);
        }
    }

    public static int metallum_metal4_main_queue_pilot_validate(final MemorySegment device) {
        try {
            return (int) metal4MainQueuePilotValidate.invokeExact(segment(device));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metal4_main_queue_pilot_validate", throwable);
        }
    }

    public static int metallum_metal4_main_renderer_enable(
            final MemorySegment device,
            final MemorySegment layer
    ) {
        try {
            return (int) metal4MainRendererEnable.invokeExact(segment(device), segment(layer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metal4_main_renderer_enable", throwable);
        }
    }

    public static long[] metallum_metal4_main_renderer_stats() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment begun = arena.allocate(LONG);
            MemorySegment submitted = arena.allocate(LONG);
            MemorySegment reused = arena.allocate(LONG);
            int engaged = (int) metal4MainRendererStats.invokeExact(begun, submitted, reused);
            return new long[] {
                    engaged,
                    begun.get(LONG, 0L),
                    submitted.get(LONG, 0L),
                    reused.get(LONG, 0L)
            };
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metal4_main_renderer_stats", throwable);
        }
    }

    public static long[] metallum_metal4_metalfx_stats() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment auxiliaryCompute = arena.allocate(LONG);
            MemorySegment spatial = arena.allocate(LONG);
            MemorySegment temporal = arena.allocate(LONG);
            MemorySegment frameGenerationInput = arena.allocate(LONG);
            int engaged = (int) metal4MetalFxStats.invokeExact(
                    auxiliaryCompute, spatial, temporal, frameGenerationInput
            );
            return new long[] {
                    engaged,
                    auxiliaryCompute.get(LONG, 0L),
                    spatial.get(LONG, 0L),
                    temporal.get(LONG, 0L),
                    frameGenerationInput.get(LONG, 0L)
            };
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metal4_metalfx_stats", throwable);
        }
    }

    /**
     * Appends the Metal 4 barrier map's consumer barriers to the existing Metal 3
     * encoders (spec M6-B). Strengthens ordering only, so rendering must be
     * unchanged; it exists to validate the barrier positions before M7e removes the
     * fences they will replace.
     */
    public static void metallum_set_metal4_barrier_enabled(final int enabled) {
        try {
            setMetal4BarrierEnabled.invokeExact(enabled);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_set_metal4_barrier_enabled", throwable);
        }
    }

    public static void metallum_set_gpu_encoder_timing_enabled(final int enabled) {
        try {
            setGpuEncoderTimingEnabled.invokeExact(enabled);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_set_gpu_encoder_timing_enabled", throwable);
        }
    }

    public static void metallum_gpu_encoder_timing_reset() {
        try {
            gpuEncoderTimingReset.invokeExact();
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_gpu_encoder_timing_reset", throwable);
        }
    }

    public static int metallum_gpu_encoder_timing_count() {
        try {
            return (int) gpuEncoderTimingCount.invokeExact();
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_gpu_encoder_timing_count", throwable);
        }
    }

    public static double metallum_gpu_encoder_timing_milliseconds(final int index) {
        try {
            return (double) gpuEncoderTimingMilliseconds.invokeExact(index);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_gpu_encoder_timing_milliseconds", throwable);
        }
    }

    public static int metallum_gpu_encoder_timing_kind(final int index) {
        try {
            return (int) gpuEncoderTimingKind.invokeExact(index);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_gpu_encoder_timing_kind", throwable);
        }
    }

    public static String metallum_gpu_encoder_timing_label(final int index) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(512L);
            int result = (int) gpuEncoderTimingCopyLabel.invokeExact(index, buffer, 512L);
            return result == 0 ? buffer.getString(0L) : "";
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_gpu_encoder_timing_copy_label", throwable);
        }
    }

    /**
     * Routes the frame-generation present thread onto a Metal 4 queue. Read once
     * when the presenter is built, so this must be set before frame generation
     * starts.
     */
    public static void metallum_set_metal4_present_enabled(final int enabled) {
        try {
            setMetal4PresentEnabled.invokeExact(enabled);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_set_metal4_present_enabled", throwable);
        }
    }

    /**
     * Creates a residency set and attaches it to {@code queue}, after which
     * natively created buffers and textures are tracked in it. Non-zero on
     * success; 0 means the OS is too old or the set could not be created, and
     * residency stays automatic.
     */
    public static int metallum_residency_set_enable(final MemorySegment device, final MemorySegment queue) {
        try {
            return (int) residencySetEnable.invokeExact(segment(device), segment(queue));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_residency_set_enable", throwable);
        }
    }

    /**
     * Enables MTL4Compiler-backed render pipeline creation on the native side.
     * Must be called before the first pipeline is built, and only with 1 when
     * {@link #metallum_metal4_supported} already said yes.
     */
    public static void metallum_set_metal4_compiler_enabled(final int enabled) {
        try {
            setMetal4CompilerEnabled.invokeExact(enabled);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_set_metal4_compiler_enabled", throwable);
        }
    }

    public static void metallum_set_terrain_icb_enabled(final int enabled) {
        if (setTerrainIcbEnabled == null) {
            return;
        }
        try {
            setTerrainIcbEnabled.invokeExact(enabled);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_set_terrain_icb_enabled", throwable);
        }
    }

    public static int metallum_pso_archive_open(final MemorySegment device, final String path) {
        try (Arena arena = Arena.ofConfined()) {
            return (int) psoArchiveOpen.invokeExact(segment(device), toCString(arena, path));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_pso_archive_open", throwable);
        }
    }

    public static int metallum_pso_archive_flush(final String path) {
        try (Arena arena = Arena.ofConfined()) {
            return (int) psoArchiveFlush.invokeExact(toCString(arena, path));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_pso_archive_flush", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_waitForFence(final MemorySegment encoder, final MemorySegment fence, final long stages) {
        try {
            MTLRenderCommandEncoderWaitForFence.invokeExact(segment(encoder), segment(fence), stages);
        } catch (Throwable throwable) {
            throw bridgeFailure("MTLRenderCommandEncoder_waitForFence", throwable);
        }
    }

    public static void MTLBlitCommandEncoder_updateFence(final MemorySegment encoder, final MemorySegment fence) {
        try {
            MTLBlitCommandEncoderUpdateFence.invokeExact(segment(encoder), segment(fence));
        } catch (Throwable throwable) {
            throw bridgeFailure("MTLBlitCommandEncoder_updateFence", throwable);
        }
    }

    public static void MTLBlitCommandEncoder_waitForFence(final MemorySegment encoder, final MemorySegment fence) {
        try {
            MTLBlitCommandEncoderWaitForFence.invokeExact(segment(encoder), segment(fence));
        } catch (Throwable throwable) {
            throw bridgeFailure("MTLBlitCommandEncoder_waitForFence", throwable);
        }
    }

    public static MemorySegment metallum_get_buffer_contents(final MemorySegment buffer) {
        try {
            return (MemorySegment) getBufferContents.invokeExact(segment(buffer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_get_buffer_contents", throwable);
        }
    }

    // --- Generic compute / mipmap / compare-sampler ABI (Iris backend B0) ---

    /** True when the loaded dylib exports the generic compute encoder ABI. */
    public static boolean supportsComputeAbi() {
        return MTLCommandBufferMakeComputeCommandEncoder != null
                && MTLComputeCommandEncoderSetComputePipelineState != null
                && MTLComputeCommandEncoderSetBuffer != null
                && MTLComputeCommandEncoderSetTexture != null
                && MTLComputeCommandEncoderDispatchThreadgroups != null
                && MTLComputeCommandEncoderUpdateFence != null
                && MTLComputeCommandEncoderWaitForFence != null
                && MTLDeviceMakeComputePipelineState != null;
    }

    /** True when the loaded dylib exports blit mipmap generation. */
    public static boolean supportsGenerateMipmaps() {
        return MTLBlitCommandEncoderGenerateMipmaps != null;
    }

    /** True when the loaded dylib exports the compare-function sampler ABI. */
    public static boolean supportsSamplerCompare() {
        return createSamplerV2 != null;
    }

    private static MethodHandle requireComputeHandle(final @Nullable MethodHandle handle, final String symbol) {
        if (handle == null) {
            throw new IllegalStateException(
                    "Loaded native bridge does not export " + symbol
                            + "; rebuild libmetallum.dylib (gradle buildMacNative) before using compute"
            );
        }
        return handle;
    }

    public static MemorySegment MTLCommandBuffer_makeComputeCommandEncoder(final MemorySegment commandBuffer) {
        try {
            return (MemorySegment) requireComputeHandle(
                    MTLCommandBufferMakeComputeCommandEncoder,
                    "metallum_MTLCommandBuffer_makeComputeCommandEncoder"
            ).invokeExact(segment(commandBuffer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_makeComputeCommandEncoder", throwable);
        }
    }

    public static void MTLComputeCommandEncoder_setComputePipelineState(final MemorySegment encoder, final MemorySegment pipelineState) {
        try {
            requireComputeHandle(
                    MTLComputeCommandEncoderSetComputePipelineState,
                    "metallum_MTLComputeCommandEncoder_setComputePipelineState"
            ).invokeExact(segment(encoder), segment(pipelineState));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLComputeCommandEncoder_setComputePipelineState", throwable);
        }
    }

    public static void MTLComputeCommandEncoder_setBuffer(final MemorySegment encoder, final MemorySegment buffer, final long offset, final int index) {
        try {
            requireComputeHandle(
                    MTLComputeCommandEncoderSetBuffer,
                    "metallum_MTLComputeCommandEncoder_setBuffer"
            ).invokeExact(segment(encoder), segment(buffer), offset, index);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLComputeCommandEncoder_setBuffer", throwable);
        }
    }

    public static void MTLComputeCommandEncoder_setTexture(final MemorySegment encoder, final MemorySegment texture, final int index) {
        try {
            requireComputeHandle(
                    MTLComputeCommandEncoderSetTexture,
                    "metallum_MTLComputeCommandEncoder_setTexture"
            ).invokeExact(segment(encoder), segment(texture), index);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLComputeCommandEncoder_setTexture", throwable);
        }
    }

    public static void MTLComputeCommandEncoder_setSamplerState(final MemorySegment encoder, final MemorySegment sampler, final int index) {
        try {
            requireComputeHandle(
                    MTLComputeCommandEncoderSetSamplerState,
                    "metallum_MTLComputeCommandEncoder_setSamplerState"
            ).invokeExact(segment(encoder), segment(sampler), index);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLComputeCommandEncoder_setSamplerState", throwable);
        }
    }

    public static void MTLComputeCommandEncoder_dispatchThreadgroups(
            final MemorySegment encoder,
            final int groupsX,
            final int groupsY,
            final int groupsZ,
            final int threadsPerGroupX,
            final int threadsPerGroupY,
            final int threadsPerGroupZ
    ) {
        try {
            requireComputeHandle(
                    MTLComputeCommandEncoderDispatchThreadgroups,
                    "metallum_MTLComputeCommandEncoder_dispatchThreadgroups"
            ).invokeExact(segment(encoder), groupsX, groupsY, groupsZ, threadsPerGroupX, threadsPerGroupY, threadsPerGroupZ);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLComputeCommandEncoder_dispatchThreadgroups", throwable);
        }
    }

    public static void MTLComputeCommandEncoder_dispatchThreadgroupsIndirect(
            final MemorySegment encoder,
            final MemorySegment indirectBuffer,
            final long indirectOffset,
            final int threadsPerGroupX,
            final int threadsPerGroupY,
            final int threadsPerGroupZ
    ) {
        try {
            requireComputeHandle(
                    MTLComputeCommandEncoderDispatchThreadgroupsIndirect,
                    "metallum_MTLComputeCommandEncoder_dispatchThreadgroupsIndirect"
            ).invokeExact(segment(encoder), segment(indirectBuffer), indirectOffset, threadsPerGroupX, threadsPerGroupY, threadsPerGroupZ);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLComputeCommandEncoder_dispatchThreadgroupsIndirect", throwable);
        }
    }

    public static void MTLComputeCommandEncoder_updateFence(final MemorySegment encoder, final MemorySegment fence) {
        try {
            requireComputeHandle(
                    MTLComputeCommandEncoderUpdateFence,
                    "metallum_MTLComputeCommandEncoder_updateFence"
            ).invokeExact(segment(encoder), segment(fence));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLComputeCommandEncoder_updateFence", throwable);
        }
    }

    public static void MTLComputeCommandEncoder_waitForFence(final MemorySegment encoder, final MemorySegment fence) {
        try {
            requireComputeHandle(
                    MTLComputeCommandEncoderWaitForFence,
                    "metallum_MTLComputeCommandEncoder_waitForFence"
            ).invokeExact(segment(encoder), segment(fence));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLComputeCommandEncoder_waitForFence", throwable);
        }
    }

    public static MemorySegment MTLDevice_makeComputePipelineState(final MemorySegment device, final MemorySegment function) {
        try {
            return (MemorySegment) requireComputeHandle(
                    MTLDeviceMakeComputePipelineState,
                    "metallum_MTLDevice_makeComputePipelineState"
            ).invokeExact(segment(device), segment(function));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLDevice_makeComputePipelineState", throwable);
        }
    }

    public static int MTLComputePipelineState_maxTotalThreadsPerThreadgroup(final MemorySegment pipelineState) {
        try {
            return (int) requireComputeHandle(
                    MTLComputePipelineStateMaxTotalThreadsPerThreadgroup,
                    "metallum_MTLComputePipelineState_maxTotalThreadsPerThreadgroup"
            ).invokeExact(segment(pipelineState));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLComputePipelineState_maxTotalThreadsPerThreadgroup", throwable);
        }
    }

    public static void MTLBlitCommandEncoder_generateMipmaps(final MemorySegment encoder, final MemorySegment texture) {
        try {
            requireComputeHandle(
                    MTLBlitCommandEncoderGenerateMipmaps,
                    "metallum_MTLBlitCommandEncoder_generateMipmaps"
            ).invokeExact(segment(encoder), segment(texture));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLBlitCommandEncoder_generateMipmaps", throwable);
        }
    }

    /**
     * Sampler creation with an optional depth-compare function. Pass
     * {@code compareFunction = -1} for an ordinary sampler; otherwise the
     * {@link com.metallum.client.metal.render.mtl.MTLCompareFunction} value.
     * Falls back to the v1 ABI when the dylib predates the extension and no
     * compare function was requested.
     */
    public static MemorySegment metallum_create_sampler_v2(
            final MemorySegment device,
            final MTLSamplerAddressMode addressModeU,
            final MTLSamplerAddressMode addressModeV,
            final MTLSamplerMinMagFilter minFilter,
            final MTLSamplerMinMagFilter magFilter,
            final MTLSamplerMipFilter mipFilter,
            final int maxAnisotropy,
            final double lodMaxClamp,
            final int compareFunction
    ) {
        if (createSamplerV2 == null) {
            if (compareFunction >= 0) {
                throw new IllegalStateException(
                        "Loaded native bridge does not export metallum_create_sampler_v2; "
                                + "rebuild libmetallum.dylib before creating compare samplers"
                );
            }
            return metallum_create_sampler(
                    device, addressModeU, addressModeV, minFilter, magFilter, mipFilter, maxAnisotropy, lodMaxClamp
            );
        }
        try {
            return (MemorySegment) createSamplerV2.invokeExact(
                    segment(device),
                    addressModeU.value,
                    addressModeV.value,
                    minFilter.value,
                    magFilter.value,
                    mipFilter.value,
                    maxAnisotropy,
                    lodMaxClamp,
                    compareFunction
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_sampler_v2", throwable);
        }
    }

    public static MemorySegment metallum_create_sampler_v3(
            final MemorySegment device,
            final MTLSamplerAddressMode addressModeU,
            final MTLSamplerAddressMode addressModeV,
            final MTLSamplerMinMagFilter minFilter,
            final MTLSamplerMinMagFilter magFilter,
            final MTLSamplerMipFilter mipFilter,
            final int maxAnisotropy,
            final double lodMaxClamp,
            final int compareFunction,
            final boolean normalizedCoordinates
    ) {
        if (createSamplerV3 == null) {
            if (!normalizedCoordinates) {
                throw new IllegalStateException(
                        "Loaded native bridge does not export metallum_create_sampler_v3; "
                                + "rebuild libmetallum.dylib before creating unnormalized samplers"
                );
            }
            return metallum_create_sampler_v2(
                    device, addressModeU, addressModeV, minFilter, magFilter, mipFilter,
                    maxAnisotropy, lodMaxClamp, compareFunction
            );
        }
        try {
            return (MemorySegment) createSamplerV3.invokeExact(
                    segment(device), addressModeU.value, addressModeV.value,
                    minFilter.value, magFilter.value, mipFilter.value,
                    maxAnisotropy, lodMaxClamp, compareFunction, normalizedCoordinates ? 1 : 0
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_sampler_v3", throwable);
        }
    }

    public static ByteBuffer nativeByteBufferView(final MemorySegment pointer, final long byteSize) {
        if (pointer == null || pointer.address() == 0L) {
            throw new IllegalArgumentException("Cannot create a ByteBuffer view for a null native pointer");
        }
        if (byteSize < 0L) {
            throw new IllegalArgumentException("Byte size must be non-negative");
        }
        return MemorySegment.ofAddress(pointer.address()).reinterpret(byteSize).asByteBuffer();
    }

    private static MemorySegment segment(final MemorySegment pointer) {
        return pointer == null || pointer.address() == 0L ? MemorySegment.NULL : pointer;
    }

    private static MemorySegment toCString(final Arena arena, final String value) {
        return value == null ? MemorySegment.NULL : arena.allocateFrom(value);
    }

    public static boolean isNullHandle(@Nullable final MemorySegment pointer) {
        return pointer == null || pointer.address() == 0L;
    }

    private static RuntimeException bridgeFailure(final String symbol, final Throwable throwable) {
        return new IllegalStateException("Native bridge call failed: " + symbol, throwable);
    }
}
