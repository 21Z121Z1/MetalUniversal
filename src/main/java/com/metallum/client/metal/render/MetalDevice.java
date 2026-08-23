package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLCommandQueue;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    private final MetalGpuBuffer genericVertexAttributeBuffer;
    private final DeviceInfo deviceInfo;
    public final MTLCommandQueue commandQueue;
    // ConcurrentHashMap gives identity semantics here only because
    // RenderPipeline never overrides equals/hashCode; RENDER_PIPELINE_IDENTITY_EQUALS
    // verifies that at class load and disables async precompile otherwise.
    private final Map<RenderPipeline, MetalCompiledRenderPipeline> compiledPipelines = new ConcurrentHashMap<>();
    private final Map<ShaderCompilationKey, IntermediaryShaderModule> shaderCache = new ConcurrentHashMap<>();
    private final Map<MslFunctionKey, MemorySegment> functionCache = new ConcurrentHashMap<>();
    private final Map<StableTerrainSamplerKey, MetalGpuSampler> stableTerrainSamplers = new HashMap<>();
    private static final int MAX_POOLED_BUFFER_BUCKETS = 32;
    private static final int MAX_POOLED_BUFFERS_PER_SIZE = 16;
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
    private ShaderSource activeShaderSource;
    private int pendingExtraTextureUsage;
    private static final boolean PSO_ARCHIVE =
            Boolean.parseBoolean(System.getProperty("metallum.opt.psoArchive", "true"));
    @Nullable
    private String psoArchivePath;
    private static final boolean ASYNC_PRECOMPILE =
            Boolean.parseBoolean(System.getProperty("metallum.opt.asyncPrecompile", "false"));
    private static final boolean STABLE_TERRAIN_SAMPLER =
            !"false".equalsIgnoreCase(System.getProperty(
                    "metallum.metalfx.stableTerrainSampler",
                    "true"
            ));
    private boolean stableTerrainSamplerLogged;
    /**
     * Master kill switch for every Metal 4 path (migration spec M1, appendix C).
     * Metal 4 code is a parallel branch: the Metal 3 path stays byte-for-byte
     * intact and is what runs whenever this is false, whenever the device or SDK
     * lacks Metal 4, or whenever a sub-switch is off.
     */
    private static final boolean METAL4_REQUESTED =
            Boolean.parseBoolean(System.getProperty("metallum.opt.metal4", "false"));
    /**
     * Routes render pipeline creation through MTL4Compiler (spec M2). Depends on
     * the master switch; on its own it does nothing.
     */
    private static final boolean METAL4_COMPILER =
            Boolean.parseBoolean(System.getProperty("metallum.opt.metal4Compiler", "false"));
    /**
     * Runs the frame-generation present thread on a Metal 4 queue (spec M4).
     * Depends on the compiler switch, because the MTL4 frame interpolator is built
     * from an MTL4Compiler.
     */
    private static final boolean METAL4_PRESENT =
            Boolean.parseBoolean(System.getProperty("metallum.opt.metal4Present", "false"));
    private static final boolean METAL4_MAIN_QUEUE_PILOT =
            Boolean.parseBoolean(System.getProperty("metallum.opt.metal4MainQueuePilot", "false"));
    private static final boolean METAL4_MAIN_RENDERER =
            Boolean.parseBoolean(System.getProperty("metallum.opt.metal4MainRenderer", "false"));
    /** METAL4_REQUESTED AND the device/SDK actually supporting Metal 4. */
    private final boolean metal4Available;
    private final boolean metal4MainRenderer;
    /**
     * Explicit residency tracking (spec M3). MTLResidencySet is macOS 15 / iOS 18
     * and needs no Metal 4, so this switch is independent of the master one: the
     * table gets built and measured on the existing Metal 3 queue, and M7 only
     * has to connect it.
     */
    /**
     * Appends the Metal 4 barrier map's consumer barriers to the existing Metal 3
     * encoders (spec M6-B). Independent of the master switch: the API is gated on
     * macOS 26, not on Metal 4 family support. Strengthens ordering only, so golden
     * frames must stay byte-identical with it on.
     */
    private static final boolean METAL4_BARRIER =
            Boolean.parseBoolean(System.getProperty("metallum.opt.metal4Barrier", "false"));
    private static final boolean RESIDENCY_SET =
            Boolean.parseBoolean(System.getProperty("metallum.opt.residencySet", "false"));
    private static final boolean RENDER_PIPELINE_IDENTITY_EQUALS = renderPipelineUsesIdentityEquals();
    /**
     * Serializes the whole GLSL→SPIR-V→MSL→PSO chain across threads: the
     * thread-safety of GlslCompiler, SPIRV-Cross contexts and the Swift-side
     * depth-stencil/archive caches is unverified, so exactly one thread may
     * be inside the chain at a time. Lock order is always
     * COMPILE_CHAIN_LOCK → map bins (never taken inside a computeIfAbsent
     * mapping function), matching {@link #clearPipelineCache()}. Package
     * visible for MetalCompiledRenderPipeline's lazy variant builds.
     */
    static final Object COMPILE_CHAIN_LOCK = new Object();
    /**
     * Bumped under COMPILE_CHAIN_LOCK by {@link #clearPipelineCache()};
     * background precompile tasks captured under an older generation carry a
     * stale ShaderSource and must abandon instead of repopulating the map.
     */
    private volatile int pipelineCacheGeneration;
    @Nullable
    private final ExecutorService prewarmExecutor;

    /** Vanilla marker result for a precompile that was queued, not run. */
    private record PendingCompiledPipeline() implements CompiledRenderPipeline {
        @Override
        public boolean isValid() {
            return true;
        }
    }

    private static final CompiledRenderPipeline PENDING_PRECOMPILE = new PendingCompiledPipeline();

    private static boolean renderPipelineUsesIdentityEquals() {
        try {
            return RenderPipeline.class.getMethod("equals", Object.class).getDeclaringClass() == Object.class;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    static boolean hasPresentationLayer(@Nullable final MemorySegment metalLayer) {
        return metalLayer != null && metalLayer.address() != 0L;
    }

    MetalDevice(
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
        MetalNativeBridge.metallum_set_debug_labels_enabled(this.useLabels());
        this.commandQueue = MTLCommandQueue.create(metalDeviceHandle);
        this.metal4Available = METAL4_REQUESTED
                && MetalNativeBridge.metallum_metal4_supported(metalDeviceHandle) != 0;
        boolean metal4MainRenderer = this.metal4Available && METAL4_MAIN_RENDERER;
        this.metal4MainRenderer = metal4MainRenderer;
        // Before metallum_init_pipelines and before any texture or buffer exists:
        // resources created earlier would never enter the set.
        if ((RESIDENCY_SET || metal4MainRenderer)
                && !this.commandQueue.enableResidencySet(metalDeviceHandle)) {
            if (metal4MainRenderer) {
                throw new IllegalStateException("Metal 4 main renderer requires explicit residency");
            }
            Metallum.LOGGER.warn("[metallum] residency set unavailable; residency stays automatic");
        }
        if (metal4MainRenderer
                && MetalNativeBridge.metallum_metal4_main_renderer_enable(
                        metalDeviceHandle,
                        metalLayer
                ) == 0) {
            throw new IllegalStateException("Metal 4 main renderer initialization failed");
        }
        // metallum_init_pipelines eagerly builds the swapchain/present PSO and
        // associated presentation samplers. An offscreen MetalDevice has no
        // CAMetalLayer by contract, so do not initialize presentation-only state
        // for it. Render/compute pipelines continue to compile through their
        // normal lazy shipping paths; production devices with a real layer keep
        // the existing eager presentation prewarm unchanged.
        if (presentationBacked) {
            MetalNativeBridge.metallum_init_pipelines(metalDeviceHandle);
        }
        // Must agree with MetalCommandEncoder.DEFERRED_DEPTH_STORE before the
        // first render encoder: the native side only sets storeAction=.unknown
        // (which Java must then resolve before endEncoding) when enabled.
        MetalNativeBridge.metallum_set_deferred_depth_store(
                MetalCommandEncoder.DEFERRED_DEPTH_STORE ? 1 : 0
        );
        // Metal 4 capability gate. Queried once here so every Metal 4 sub-switch
        // can just AND against it; the native side folds the compile-time
        // #available check into the same answer.
        // MetalFX's Metal 4 scaler/interpolator factories require an
        // MTL4Compiler. Enabling the main renderer therefore implies the
        // compiler even when its independent pilot switch is absent.
        boolean metal4Compiler = this.metal4Available && (METAL4_COMPILER || metal4MainRenderer);
        MetalNativeBridge.metallum_set_metal4_compiler_enabled(metal4Compiler ? 1 : 0);
        // Depends on the compiler switch: the MTL4 frame interpolator factory
        // takes an MTL4Compiler, so the present pilot cannot run without it.
        boolean metal4Present = metal4Compiler && (METAL4_PRESENT || metal4MainRenderer);
        MetalNativeBridge.metallum_set_metal4_present_enabled(metal4Present ? 1 : 0);
        boolean metal4MainQueuePilot = this.metal4Available && METAL4_MAIN_QUEUE_PILOT;
        if (metal4MainQueuePilot
                && MetalNativeBridge.metallum_metal4_main_queue_pilot_validate(metalDeviceHandle) == 0) {
            throw new IllegalStateException("Metal 4 main-queue pilot validation failed");
        }
        MetalNativeBridge.metallum_set_metal4_barrier_enabled(METAL4_BARRIER ? 1 : 0);
        MetalNativeBridge.metallum_set_gpu_encoder_timing_enabled(
                Boolean.getBoolean("metallum.validation.gpuPassTiming") ? 1 : 0
        );
        Metallum.LOGGER.info(
                "[Metallum] Metal 4: requested={} available={} compiler={} present={} mainQueuePilot={} mainRenderer={} barrier={}",
                METAL4_REQUESTED,
                this.metal4Available,
                metal4Compiler,
                metal4Present,
                metal4MainQueuePilot,
                metal4MainRenderer,
                METAL4_BARRIER
        );
        if (PSO_ARCHIVE) {
            try {
                java.nio.file.Path cacheDir = net.fabricmc.loader.api.FabricLoader.getInstance()
                        .getGameDir().resolve("metallum-cache");
                java.nio.file.Files.createDirectories(cacheDir);
                String archivePath = cacheDir.resolve("pso.binaryarchive").toString();
                if (MetalNativeBridge.metallum_pso_archive_open(metalDeviceHandle, archivePath) != 0) {
                    this.psoArchivePath = archivePath;
                } else {
                    Metallum.LOGGER.warn("[metallum] PSO binary archive unavailable; pipelines compile uncached");
                }
            } catch (Exception e) {
                Metallum.LOGGER.warn("[metallum] PSO binary archive setup failed; pipelines compile uncached", e);
            }
        }
        if (ASYNC_PRECOMPILE && !RENDER_PIPELINE_IDENTITY_EQUALS) {
            Metallum.LOGGER.warn(
                    "[metallum] RenderPipeline overrides equals/hashCode; async precompile disabled"
            );
        }
        this.prewarmExecutor = ASYNC_PRECOMPILE && RENDER_PIPELINE_IDENTITY_EQUALS
                ? Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "metallum-pso-prewarm");
                    thread.setDaemon(true);
                    return thread;
                })
                : null;
        this.commandEncoder = new MetalCommandEncoder(this);
        this.deviceInfo = buildDeviceInfo(deviceName);
        this.genericVertexAttributeBuffer = (MetalGpuBuffer) this.createBuffer(
                () -> "OpenGL generic vertex attribute defaults",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                genericVertexAttributeDefaults()
        );
        current = this;
        // MetalFX owns presentation/HUD/frame-generation state. It is not part
        // of a layerless offscreen device's resource graph and its startup path
        // requires a real CAMetalLayer.
        if (presentationBacked) {
            MetalFxManager.initialize(this);
        }
    }

    /** Current source chain used by lazy PSO compilation; package seam for generated Iris stages. */
    ShaderSource activeShaderSource() {
        return this.activeShaderSource;
    }

    /**
     * The live Metal device, or {@code null} before creation / after close.
     *
     * <p>{@code RenderSystem.getDevice()} hands back a {@code GpuDevice}, which
     * this class does not implement (it is a {@code GpuDeviceBackend}), so there
     * is otherwise no way for code outside the render-pass call chain to reach
     * the device. Needed by callers that must allocate GPU resources at a point
     * where no encoder is running — see
     * {@link IrisMetalPipelineOverrides#updateFrame()}.
     */
    static @Nullable MetalDevice current() {
        return current;
    }

    @Override
    public @NonNull GpuSurfaceBackend createSurface(final long windowHandle) {
        return new MetalSurface(this, this.metalLayer);
    }

    MemorySegment metalLayerHandle() {
        return this.metalLayer;
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
            final @NonNull OptionalDouble maxLod
    ) {
        return new MetalGpuSampler(this, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    MetalGpuSampler stableTerrainSampler(final MetalGpuSampler source) {
        if (!STABLE_TERRAIN_SAMPLER
                || source.getMinFilter() == FilterMode.LINEAR
                && source.getMagFilter() == FilterMode.LINEAR) {
            return source;
        }
        StableTerrainSamplerKey key = new StableTerrainSamplerKey(
                source.getAddressModeU(),
                source.getAddressModeV(),
                source.getMaxAnisotropy(),
                source.getMaxLod()
        );
        MetalGpuSampler derived = stableTerrainSamplers.computeIfAbsent(
                key,
                ignored -> new MetalGpuSampler(
                        this,
                        key.addressModeU(),
                        key.addressModeV(),
                        FilterMode.LINEAR,
                        FilterMode.LINEAR,
                        key.maxAnisotropy(),
                        key.maxLod()
                )
        );
        if (!stableTerrainSamplerLogged) {
            stableTerrainSamplerLogged = true;
            Metallum.LOGGER.info(
                    "MetalFX stable terrain sampler engaged: min/mag=LINEAR, mip/address/aniso/LOD preserved"
            );
        }
        return derived;
    }

    @Override
    public @NonNull GpuTexture createTexture(
            @Nullable final Supplier<String> label,
            @GpuTexture.Usage final int usage,
            final @NonNull GpuFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels
    ) {
        return this.createTexture(this.resolveDebugLabel(label), usage, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public @NonNull GpuTexture createTexture(
            @Nullable final String label,
            @GpuTexture.Usage final int usage,
            final @NonNull GpuFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels
    ) {
        return new MetalGpuTexture(
                this, usage | this.pendingExtraTextureUsage, label == null ? "" : label,
                format, width, height, depthOrLayers, mipLevels
        );
    }

    /**
     * Runs {@code runnable} with every texture this device creates carrying
     * {@code extraUsage} in addition to its declared usage. Used to route
     * backend-only usage bits (e.g. {@link MetalGpuTexture#USAGE_SHADER_WRITE}
     * for MetalFX output targets) through vanilla creation paths such as
     * {@code TextureTarget} that cannot forward custom flags. Render thread
     * only.
     */
    void withExtraTextureUsage(final int extraUsage, final Runnable runnable) {
        int previous = this.pendingExtraTextureUsage;
        this.pendingExtraTextureUsage = previous | extraUsage;
        try {
            runnable.run();
        } finally {
            this.pendingExtraTextureUsage = previous;
        }
    }

    @Override
    public @NonNull GpuTextureView createTextureView(final @NonNull GpuTexture texture) {
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
        return new MetalGpuBuffer(this, label, usage, size);
    }

    @Override
    public @NonNull GpuBuffer createBuffer(@Nullable final Supplier<String> label, @GpuBuffer.Usage final int usage, final ByteBuffer data) {
        if (data == null || data.remaining() <= 0) {
            throw new IllegalArgumentException("Cannot create buffer from empty ByteBuffer");
        }
        int effectiveUsage = usage | GpuBuffer.USAGE_COPY_DST;
        if ((usage & GpuBuffer.USAGE_INDEX) != 0) {
            /*
             * Metal has no indexed triangle-fan primitive. Our generic fan
             * emulation expands the source indices while encoding the draw,
             * before an upload blit in that command buffer can execute. Keep
             * initialized index data CPU-visible and publish it synchronously.
             */
            effectiveUsage |= GpuBuffer.USAGE_MAP_WRITE;
            MetalGpuBuffer buffer = (MetalGpuBuffer) this.createBuffer(label, effectiveUsage, data.remaining());
            buffer.sliceStorage(0L, data.remaining()).put(data.duplicate());
            return buffer;
        }
        MetalGpuBuffer buffer = (MetalGpuBuffer) this.createBuffer(label, effectiveUsage, data.remaining());
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
    public @NonNull CompiledRenderPipeline precompilePipeline(final @NonNull RenderPipeline pipeline, @Nullable final ShaderSource shaderSource) {
        ShaderSource effectiveSource = shaderSource == null ? this.activeShaderSource : shaderSource;
        if (shaderSource != null) {
            this.activeShaderSource = shaderSource;
        }
        MetalCompiledRenderPipeline existing = this.compiledPipelines.get(pipeline);
        if (existing != null) {
            return existing;
        }
        if (this.prewarmExecutor != null) {
            int generation = this.pipelineCacheGeneration;
            this.prewarmExecutor.execute(() -> {
                try {
                    this.compileInBackground(pipeline, effectiveSource, generation);
                } catch (Throwable t) {
                    // First real use on the render thread recompiles and
                    // surfaces the error with vanilla's own handling.
                    Metallum.LOGGER.warn("[metallum] background precompile failed for {}", pipeline.getLocation(), t);
                }
            });
            return PENDING_PRECOMPILE;
        }
        synchronized (COMPILE_CHAIN_LOCK) {
            return this.compiledPipelines.computeIfAbsent(pipeline, p -> compileWithIrisOverride(p, effectiveSource));
        }
    }

    /**
     * The single funnel every compile path goes through, so the Iris terrain
     * override is consulted on the render thread and on the prewarm thread
     * alike. Missing the background path would let prewarm win the cache race
     * with a native PSO and silently disable the override.
     */
    private MetalCompiledRenderPipeline compileWithIrisOverride(
            final RenderPipeline pipeline, final ShaderSource source
    ) {
        MetalCompiledRenderPipeline override = IrisMetalPipelineOverrides.tryCompile(this, pipeline, source);
        return override != null ? override : MetalCrossShaderCompiler.compile(this, pipeline, source);
    }

    /** True when the background prewarm thread exists (async precompile on). */
    boolean asyncPrewarmEnabled() {
        return this.prewarmExecutor != null;
    }

    boolean metal4MainRendererEnabled() {
        return this.metal4MainRenderer;
    }

    /**
     * Queues work on the prewarm thread; silently dropped once the executor
     * is shut down (device close), when the render thread finishes the work
     * on demand instead.
     */
    void submitPrewarmTask(final Runnable task) {
        if (this.prewarmExecutor != null) {
            try {
                this.prewarmExecutor.execute(task);
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
            }
        }
    }

    private void compileInBackground(final RenderPipeline pipeline, final ShaderSource source, final int generation) {
        if (this.compiledPipelines.containsKey(pipeline)) {
            return;
        }
        synchronized (COMPILE_CHAIN_LOCK) {
            // The volatile generation write happens under this lock, so this
            // read also orders us after every render-thread write (shader
            // source swap, MetalFX LOD bias) that preceded the last clear.
            if (generation != this.pipelineCacheGeneration) {
                return;
            }
            this.compiledPipelines.computeIfAbsent(pipeline, p -> compileWithIrisOverride(p, source));
        }
    }

    @Override
    public void clearPipelineCache() {
        this.waitForSubmittedGpuWork();
        this.stableTerrainSamplers.values().forEach(MetalGpuSampler::closeImmediately);
        this.stableTerrainSamplers.clear();
        this.stableTerrainSamplerLogged = false;
        synchronized (COMPILE_CHAIN_LOCK) {
            this.pipelineCacheGeneration++;
            this.compiledPipelines.values().forEach(MetalCompiledRenderPipeline::close);
            this.compiledPipelines.clear();
            this.shaderCache.values().forEach(IntermediaryShaderModule::close);
            this.shaderCache.clear();
            for (MemorySegment function : this.functionCache.values()) {
                if (!MetalNativeBridge.isNullHandle(function)) {
                    MetalNativeBridge.metallum_release_object(function);
                }
            }
            this.functionCache.clear();
        }
        MetalMslDiskCache.logSessionStats();
        // Persist harvested pipelines so the next launch (or the rebuild
        // following this cache clear) hits the on-disk archive.
        if (this.psoArchivePath != null) {
            try {
                MetalNativeBridge.metallum_pso_archive_flush(this.psoArchivePath);
            } catch (Exception e) {
                Metallum.LOGGER.warn("[metallum] PSO binary archive flush failed", e);
            }
        }
    }

    private static volatile @Nullable MetalDevice current;

    @Override
    public void close() {
        if (current == this) {
            current = null;
        }
        this.waitForSubmittedGpuWork();
        this.genericVertexAttributeBuffer.close();
        this.commandEncoder.close();
        if (this.prewarmExecutor != null) {
            // Stop background compiles before tearing down the caches they
            // populate; a straggler past the 5s bail-out still serializes
            // against clearPipelineCache via COMPILE_CHAIN_LOCK.
            this.prewarmExecutor.shutdownNow();
            try {
                if (!this.prewarmExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    Metallum.LOGGER.warn("[metallum] PSO prewarm thread still busy at shutdown");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        this.clearPipelineCache();
        this.drainBufferPool();
        if (!MetalNativeBridge.isNullHandle(this.cocoaView)) {
            try {
                MetalNativeBridge.metallum_NSView_clearLayer(this.cocoaView);
            } catch (Throwable ignored) {
            }
        }
        this.commandQueue.close();
        MetalNativeBridge.metallum_release_object(this.metalDeviceHandle);
    }

    @Override
    public @NonNull GpuQueryPool createTimestampQueryPool(final int size) {
        return new MetalGpuQueryPool(size);
    }

    @Override
    public long getTimestampNow() {
        return System.nanoTime();
    }

    @Override
    public @NonNull DeviceInfo getDeviceInfo() {
        return this.deviceInfo;
    }

    MemorySegment metalDeviceHandle() {
        return this.metalDeviceHandle;
    }

    MetalCommandEncoder commandEncoder() {
        return this.commandEncoder;
    }

    MetalGpuBuffer genericVertexAttributeBuffer() {
        return this.genericVertexAttributeBuffer;
    }

    static ByteBuffer genericVertexAttributeDefaults() {
        ByteBuffer defaults = ByteBuffer.allocateDirect(
                MetalCrossShaderCompiler.GENERIC_VERTEX_DEFAULT_VALUES_SIZE
        );
        MetalCrossShaderCompiler.writeGenericVertexDefaultValues(defaults);
        return defaults;
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

    MetalCompiledRenderPipeline getOrCompilePipeline(final RenderPipeline pipeline) {
        // Lock-free on the hot path; a miss takes the chain lock, so a first
        // use may wait out whatever the prewarm thread is currently building.
        MetalCompiledRenderPipeline existing = this.compiledPipelines.get(pipeline);
        if (existing != null) {
            return existing;
        }
        synchronized (COMPILE_CHAIN_LOCK) {
            return this.compiledPipelines.computeIfAbsent(
                    pipeline, p -> compileWithIrisOverride(p, this.activeShaderSource));
        }
    }

    IntermediaryShaderModule getOrCompileShader(final Identifier id, final ShaderType type, final ShaderDefines defines, final ShaderSource shaderSource) {
        ShaderCompilationKey key = new ShaderCompilationKey(id, type, defines);
        return this.shaderCache.computeIfAbsent(key, k -> {
            String source = shaderSource.get(k.id(), k.type());
            if (source == null) {
                return IntermediaryShaderModule.INVALID;
            }
            String sourceWithDefines = prepareShaderSource(source, k.defines());
            try (GlslCompiler glslCompiler = new GlslCompiler()) {
                return glslCompiler.createIntermediary(k.id().toDebugFileName(), sourceWithDefines, k.type());
            } catch (ShaderCompileException e) {
                throw new IllegalStateException("Failed to compile shader " + k.id(), e);
            }
        });
    }

    static String prepareShaderSource(final String source, final ShaderDefines defines) {
        String stripped = BLOCK_COMMENTS.matcher(source).replaceAll("");
        stripped = LINE_COMMENTS.matcher(stripped).replaceAll("").stripLeading();
        return GlslPreprocessor.injectDefines(stripped, defines);
    }

    MemorySegment getOrCompileFunction(final String msl, final String entryPoint) {
        return this.functionCache.computeIfAbsent(
                new MslFunctionKey(msl, entryPoint),
                key -> MetalNativeBridge.metallum_create_shader_function(this.metalDeviceHandle, key.msl(), key.entryPoint())
        );
    }

    private record ShaderCompilationKey(Identifier id, ShaderType type, ShaderDefines defines) {
    }

    private record StableTerrainSamplerKey(
            AddressMode addressModeU,
            AddressMode addressModeV,
            int maxAnisotropy,
            OptionalDouble maxLod
    ) {
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
                // Metal exposes eight color attachment slots and Minecraft's
                // ColorTargetState contract has the same upper bound. Keep
                // the advertised limit aligned with both APIs so the generic
                // CommandEncoder rejects an impossible pass before native use.
                new DeviceLimits(1, 256, 16384, maxMemoryAllocationSize, 0, ColorTargetState.MAX_COLOR_TARGETS),
                new DeviceFeatures(false, false, true, true, true, false, true),
                underlyingExtensions,
                new HintsAndWorkarounds(false, false),
                type
        );
    }

    @Nullable
    private String resolveDebugLabel(@Nullable final Supplier<String> label) {
        return this.useLabels() && label != null ? label.get() : null;
    }
}
