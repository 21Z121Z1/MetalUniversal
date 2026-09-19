package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLCommandQueue;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.device.*;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import com.mojang.renderpearl.util.ShaderCompileException;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Environment(EnvType.CLIENT)
final class MetalDevice implements GpuDeviceBackend {
    private static final Pattern BLOCK_COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern LINE_COMMENTS = Pattern.compile("(?m)//[^\\n]*");
    private final MemorySegment metalDeviceHandle;
    private final MemorySegment metalLayer;
    private final MemorySegment cocoaView;
    private final GpuDebugOptions debugOptions;
    private final MetalCommandEncoder commandEncoder;
    private final DeviceInfo deviceInfo;
    public final MTLCommandQueue commandQueue;
    private final Map<RenderPipeline, MetalCompiledRenderPipeline> compiledPipelines = new IdentityHashMap<>();
    private final Map<ShaderCompilationKey, SpvModule> shaderCache = new HashMap<>();
    private final Map<MslFunctionKey, MemorySegment> functionCache = new HashMap<>();
    private static final int MAX_POOLED_BUFFER_BUCKETS = 32;
    private static final int MAX_POOLED_BUFFERS_PER_SIZE = 8;
    private final Map<Long, Deque<MemorySegment>> bufferPool = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<Long, Deque<MemorySegment>> eldest) {
            if (size() <= MAX_POOLED_BUFFER_BUCKETS) {
                return false;
            }
            for (MemorySegment handle : eldest.getValue()) {
                MetalNativeBridge.metallum_release_object(handle);
            }
            return true;
        }
    };
    private static final int MAX_TEXEL_VIEWS = 128;
    private final Map<TexelTexelViewKey, MemorySegment> texelViewCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<TexelTexelViewKey, MemorySegment> eldest) {
            if (size() <= MAX_TEXEL_VIEWS) {
                return false;
            }
            MetalNativeBridge.metallum_release_object(eldest.getValue());
            return true;
        }
    };

    MetalDevice(
            final GpuDebugOptions debugOptions,
            final MemorySegment metalDeviceHandle,
            final MemorySegment metalLayer,
            final String deviceName,
            final MemorySegment cocoaView
    ) {
        this.debugOptions = debugOptions;
        this.metalDeviceHandle = metalDeviceHandle;
        this.metalLayer = metalLayer;
        this.cocoaView = cocoaView;
        MetalNativeBridge.metallum_set_debug_labels_enabled(this.useLabels());
        this.commandQueue = MTLCommandQueue.create(metalDeviceHandle);
        MetalNativeBridge.metallum_init_pipelines(metalDeviceHandle);
        this.commandEncoder = new MetalCommandEncoder(this);
        this.deviceInfo = buildDeviceInfo(deviceName);
    }

    @Override
    public @NonNull GpuSurfaceBackend createSurface(final long windowHandle, final @NonNull BooleanSupplier isMinimized) {
        return new MetalSurface(this, this.metalLayer);
    }

    @Override
    public @NonNull MetalCommandEncoder createCommandEncoder() {
        return this.commandEncoder;
    }

    @Override
    public @NonNull GpuSampler createSampler(
            final @NonNull AddressMode addressModeU,
            final @NonNull AddressMode addressModeV,
            final @NonNull FilterMode minFilter,
            final @NonNull FilterMode magFilter,
            final int maxAnisotropy,
            final OptionalDouble maxLod
    ) {
        return new MetalGpuSampler(this, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    /** 便捷重载：26.3 的后端接口只要求 String 标签版本，Supplier 版本由这里补齐。 */
    @NonNull GpuTexture createTexture(
            final Supplier<String> label,
            final int usage,
            final GpuFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels
    ) {
        return this.createTexture(this.resolveDebugLabel(label), usage, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public @NonNull GpuTexture createTexture(
            final @NonNull String label,
            @GpuTexture.Usage final int usage,
            final @NonNull GpuFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels
    ) {
        return new MetalGpuTexture(this, usage, label, format, width, height, depthOrLayers, mipLevels);
    }

    /** 便捷重载：26.3 的接口只要求三参数版本，单参数版本由这里补齐。 */
    @NonNull GpuTextureView createTextureView(final GpuTexture texture) {
        return this.createTextureView(texture, 0, texture.getMipLevels());
    }

    @Override
    public @NonNull GpuTextureView createTextureView(final @NonNull GpuTexture texture, final int baseMipLevel, final int mipLevels) {
        return new MetalGpuTextureView(texture, baseMipLevel, mipLevels);
    }

    @Override
    public @NonNull GpuBuffer createBuffer(@Nullable final Supplier<String> label, @GpuBuffer.Usage final int usage, final long size) {
        if (size <= 0L) {
            throw new IllegalArgumentException("Metal buffer size must be > 0 (got " + size + ")");
        }
        return new MetalGpuBuffer(this, usage, size);
    }

    @Override
    public @NonNull GpuBuffer createBuffer(@Nullable final Supplier<String> label, @GpuBuffer.Usage final int usage, final ByteBuffer data) {
        if (data == null || data.remaining() <= 0) {
            throw new IllegalArgumentException("Cannot create buffer from empty ByteBuffer");
        }
        MetalGpuBuffer buffer = new MetalGpuBuffer(this, usage | GpuBuffer.USAGE_COPY_DST, data.remaining());
        this.commandEncoder.writeToBuffer(buffer.slice(), data.duplicate());
        return buffer;
    }

    @Override
    public @NonNull List<String> getLastDebugMessages() {
        return List.of();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return this.debugOptions.logLevel() > 0 || this.debugOptions.useLabels() || this.debugOptions.useValidationLayers();
    }

    boolean useLabels() {
        return this.debugOptions.useLabels();
    }

    @Override
    public BackendRenderPipeline.@NonNull Pending compilePipeline(final BackendRenderPipeline.@NonNull CreateInfo createInfo) {
        return MetalCrossShaderCompiler.compile(this, createInfo);
    }

    @Override
    public long getTimestampCalibrationOffset() {
        return 0L;
    }

    /**
     * 返回写入查询池的时间戳源。
     *
     * <p>26.3 的 {@code GpuDeviceBackend} 不再声明该方法，但 {@link MetalCommandEncoder} 与
     * {@link MetalRenderPass} 的 {@code writeTimestamp} 仍需要它，因此保留为包内方法。
     */
    long getTimestampNow() {
        return System.nanoTime();
    }

    /**
     * 清空管线与着色器缓存。
     *
     * <p>26.3 的 {@code GpuDeviceBackend} 接口不再暴露该操作，但设备关闭路径
     * 仍然需要它，因此保留为包内方法。
     */
    void clearPipelineCache() {
        this.waitForSubmittedGpuWork();
        this.compiledPipelines.values().forEach(MetalCompiledRenderPipeline::close);
        this.compiledPipelines.clear();
        this.shaderCache.values().forEach(SpvModule::close);
        this.shaderCache.clear();
        for (MemorySegment function : this.functionCache.values()) {
            if (!MetalNativeBridge.isNullHandle(function)) {
                MetalNativeBridge.metallum_release_object(function);
            }
        }
        this.functionCache.clear();
        for (MemorySegment view : this.texelViewCache.values()) {
            if (!MetalNativeBridge.isNullHandle(view)) {
                MetalNativeBridge.metallum_release_object(view);
            }
        }
        this.texelViewCache.clear();
    }

    @Override
    public void close() {
        this.waitForSubmittedGpuWork();
        this.commandEncoder.close();
        this.clearPipelineCache();
        this.drainBufferPool();
        try {
            MetalNativeBridge.metallum_NSView_clearLayer(this.cocoaView);
        } catch (Throwable ignored) {
        }
        this.commandQueue.close();
        MetalNativeBridge.metallum_release_object(this.metalDeviceHandle);
    }

    @Override
    public @NonNull GpuQueryPool createTimestampQueryPool(final int size) {
        return new MetalGpuQueryPool(size);
    }

    @Override
    public @NonNull DeviceInfo getDeviceInfo() {
        return this.deviceInfo;
    }

    MemorySegment metalDeviceHandle() {
        return this.metalDeviceHandle;
    }

    long maxBufferAllocationSize() {
        return this.deviceInfo.limits().maxMemoryAllocationSize();
    }

    void waitForSubmittedGpuWork() {
        this.commandEncoder.waitForSubmittedGpuWork();
    }

    void queueResourceRelease(final MemorySegment handle) {
        this.commandEncoder.queueForDestroy(() -> MetalNativeBridge.metallum_release_object(handle));
    }

    MemorySegment tryAcquirePooledBuffer(final long size, final long resourceOptions) {
        long key = composePoolKey(size, resourceOptions);
        Deque<MemorySegment> bucket = bufferPool.get(key);
        if (bucket != null && !bucket.isEmpty()) {
            return bucket.pop();
        }
        return MemorySegment.NULL;
    }

    void queueBufferRelease(final MemorySegment handle, final long size, final long resourceOptions) {
        this.commandEncoder.queueForDestroy(() -> {
            long key = composePoolKey(size, resourceOptions);
            Deque<MemorySegment> bucket = bufferPool.computeIfAbsent(key, k -> new ArrayDeque<>());
            if (bucket.size() < MAX_POOLED_BUFFERS_PER_SIZE) {
                bucket.push(handle);
            } else {
                MetalNativeBridge.metallum_release_object(handle);
            }
        });
    }

    /** Returns a cached Metal texture view over a texel buffer, creating one on first use. The returned
     *  segment remains owned by this cache and must not be released by callers. */
    MemorySegment getOrCreateTexelView(
            final MetalGpuBuffer texelBuffer,
            final long pixelFormat,
            final long offset,
            final long texelCount,
            final long height,
            final long bytesPerRow
    ) {
        TexelTexelViewKey key = new TexelTexelViewKey(texelBuffer.nativeHandle().address(), pixelFormat, offset, bytesPerRow, texelCount, height);
        MemorySegment cached = texelViewCache.get(key);
        if (cached != null && !MetalNativeBridge.isNullHandle(cached)) {
            return cached;
        }
        MemorySegment view = MetalNativeBridge.metallum_create_buffer_texture_view(
                texelBuffer.nativeHandle(), pixelFormat, offset, texelCount, height, bytesPerRow);
        if (!MetalNativeBridge.isNullHandle(view)) {
            texelViewCache.put(key, view);
            Stats.recordTexelViewAllocation();
        }
        return view;
    }

    static long composePoolKey(final long size, final long resourceOptions) {
        return (size << 12) | (resourceOptions & 0xFFFL);
    }

    private void drainBufferPool() {
        for (Deque<MemorySegment> bucket : bufferPool.values()) {
            for (MemorySegment handle : bucket) {
                MetalNativeBridge.metallum_release_object(handle);
            }
        }
        bufferPool.clear();
    }

    /**
     * 把 GLSL 着色器编译为 SPIR-V 中间模块。
     *
     * <p>26.3 移除了旧的 {@code IntermediaryShaderModule} / {@code GlslPreprocessor}，
     * 着色器前端统一收敛到 {@link GlslCompiler#compileToSpv}：它自己接收
     * {@link ShaderSource} 与 {@link ShaderDefines}，并负责 #define 注入，
     * 因此这里不再需要手工做预处理。
     */
    SpvModule getOrCompileShader(final Identifier id, final ShaderType type, final ShaderDefines defines, final ShaderSource shaderSource) {
        ShaderCompilationKey key = new ShaderCompilationKey(id, type, defines);
        String source = shaderSource.getShader(id, type);
        if (source == null) {
            throw new IllegalStateException("Shader source is missing for " + id + " (" + type + ")");
        }
        return this.shaderCache.computeIfAbsent(key, k -> {
            try (GlslCompiler glslCompiler = new GlslCompiler(false, false)) {
                return glslCompiler.compileToSpv(k.id().toDebugFileName(), source, k.type(), k.defines(), shaderSource);
            } catch (ShaderCompileException e) {
                throw new IllegalStateException("Failed to compile shader " + k.id(), e);
            }
        });
    }

    MemorySegment getOrCompileFunction(final String msl, final String entryPoint) {
        return this.functionCache.computeIfAbsent(
                new MslFunctionKey(msl, entryPoint),
                key -> MetalNativeBridge.metallum_create_shader_function(this.metalDeviceHandle, key.msl(), key.entryPoint())
        );
    }

    private record ShaderCompilationKey(Identifier id, ShaderType type, ShaderDefines defines) {
    }

    private record MslFunctionKey(String msl, String entryPoint) {
    }

    private DeviceInfo buildDeviceInfo(final String deviceName) {
        DeviceType type = DeviceType.INTEGRATED;
        Set<String> underlyingExtensions = Set.of("CAMetalLayer", "MTLDevice");
        String osVersion = System.getProperty("os.version", "").trim();
        String platformName = MetalNativeBridge.isIOS() ? "iOS" : "macOS";
        String driverDescription = platformName + " " + osVersion;
        long maxMemoryAllocationSize = MetalNativeBridge.MTLDevice_maxMemoryAllocationSize(metalDeviceHandle);
        return new DeviceInfo(
                deviceName,
                "Apple",
                driverDescription,
                true,
                "Metal",
                1.0F,
                // 26.3 的 DeviceLimits 多了 maxMultiDrawDirectInterleavedDrawCount / maxColorAttachments
                // / maxDrawIndirectDrawCount 三项，顺序为 (maxAnisotropy, minUniformOffsetAlignment,
                // maxTextureSize, maxMemoryAllocationSize, maxMultiDrawDirectInterleavedDrawCount,
                // maxColorAttachments, maxDrawIndirectDrawCount)。
                new DeviceLimits(16, 256, 16384, maxMemoryAllocationSize, 0, 8, 0),
                // 顺序为 (wireframeFillMode, shaderDrawParameters, multiDrawDirectInterleaved,
                // multiDrawDirectSeparate, multiDrawIndirect, drawIndirect, nonZeroFirstInstance,
                // persistentMapping)。
                new DeviceFeatures(false, false, false, false, false, false, true, false),
                underlyingExtensions,
                new HintsAndWorkarounds(false, false, false, false),
                type
        );
    }

    @Nullable
    private String resolveDebugLabel(@Nullable final Supplier<String> label) {
        return this.useLabels() && label != null ? label.get() : null;
    }

    private record TexelTexelViewKey(long bufferAddress, long pixelFormat, long offset, long bytesPerRow, long texelCount, long height) {
    }
}
