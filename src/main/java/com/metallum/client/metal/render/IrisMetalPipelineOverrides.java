package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeFunction;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pathways.colorspace.ColorSpace;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.pbr.TextureTracker;
import net.irisshaders.iris.pbr.texture.PBRTextureHolder;
import net.irisshaders.iris.pbr.texture.PBRTextureManager;
import net.irisshaders.iris.pbr.texture.PBRType;
import net.irisshaders.iris.pbr.format.TextureFormat;
import net.irisshaders.iris.pbr.format.TextureFormatLoader;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives.RenderTargetSettings;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniformFixedInputUniformsHolder;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Field;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * B2-1 pipeline-override registry: the Metal-side equivalent of Iris's
 * {@code MixinShaderManager_Overrides} HEAD injection into
 * {@code GlDevice.getOrCompilePipeline}.
 *
 * <p>When a shader pack is active, {@link MetalDevice}'s pipeline-compile
 * funnel consults {@link #tryCompile} first. Sodium terrain pipelines are
 * recognized with Iris's own production discrimination
 * ({@code IrisPipelines.getPipeline} bytecode): namespace contains
 * {@code "sodium"}; translucent when the color target carries a blend
 * function; cutout when the shader defines mention {@code CUTOUT}; solid
 * otherwise. A recognized pipeline is answered with a PSO compiled through the
 * <b>stock</b> chain ({@code MetalCrossShaderCompiler}: vanilla GlslCompiler
 * &rarr; by-name rebind &rarr; SPIRV-Cross &rarr; Metal PSO) from a synthetic
 * {@link RenderPipeline} that carries the Iris-patched pack sources, the
 * XHFP chunk vertex format from {@link WorldRenderingSettings}, and an MRT
 * color-target list derived from the program's DRAWBUFFERS directive
 * (draw buffer 0 aliases the sodium pipeline's own target — the main
 * framebuffer — until the B2-3 composite chain lands).</p>
 *
 * <p>An admitted active pack is fail-closed: translation, compilation,
 * descriptor, and resource failures reject the generation before it can be
 * published. Test-only construction helpers may still request a non-strict
 * diagnostic instance, but production never substitutes the native pipeline
 * for an admitted pack.</p>
 */
@Environment(EnvType.CLIENT)
public final class IrisMetalPipelineOverrides {
    /** Formats for extended (non-alias) DRAWBUFFERS targets; B2-1 fixes RGBA8, pack format directives are B2-3 scope. */
    static final GpuFormat EXTENDED_TARGET_FORMAT = GpuFormat.RGBA8_UNORM;

    private static final AtomicInteger GENERATIONS = new AtomicInteger();
    private static volatile @Nullable Instance active;
    private static final ThreadLocal<TerrainKind> ACTIVE_TERRAIN_KIND = new ThreadLocal<>();
    private static final Field IRIS_BLEND_MODE = irisBlendModeField();

    /**
     * Whether the sodium terrain render pass carries the pack's extra
     * DRAWBUFFERS attachments.
     *
     * <p>{@link MetalCompiledRenderPipeline} selects its PSO by the attachment
     * signature of the pass being drawn into, so a program declaring
     * {@code /* DRAWBUFFERS:02 *}{@code /} can only be bound once the pass
     * really has those targets. Until the terrain pass is extended (handoff
     * step S6) multi-target kinds fail open and keep sodium's own shader.</p>
     *
     * <p>Compilation itself is independent of this — the offline gate sets it
     * to exercise the full translate→compile chain for every kind.</p>
     */
    private static volatile boolean extendedTerrainTargets;

    /**
     * Declares that the sodium terrain pass carries the pack's extra DRAWBUFFERS
     * attachments. Only read when an {@link Instance} is constructed — flipping
     * it afterwards does not affect the live generation, by design (see
     * {@link Instance#extendedKinds}). Deactivate and reactivate to change it.
     */
    static void setExtendedTerrainTargets(final boolean supported) {
        extendedTerrainTargets = supported;
    }

    /** Called after Sodium has selected the program for the current terrain pass. */
    public static void beginTerrainPass(final TerrainRenderPass pass) {
        Instance instance = active;
        TerrainKind kind = instance == null ? null : Instance.discriminate(pass.getPipeline());
        ACTIVE_TERRAIN_KIND.set(kind);
        if (instance != null && kind != null) {
            IrisMetalPassTrace.observeTerrain(kind.name(), instance.drawBuffersFor(kind));
        }
    }

    /** Clears the render-thread terrain discriminator at the matching end hook. */
    public static void endTerrainPass() {
        ACTIVE_TERRAIN_KIND.remove();
    }

    /**
     * Replaces Sodium's descriptor for every translated program, including
     * {@code DRAWBUFFERS:0}: colortex0 is generation-owned and only the final
     * pass resolves it to Minecraft's scene target. A null return means the
     * caller should keep its original descriptor path.
     */
    public static @Nullable RenderPass createTerrainRenderPass(
            final CommandEncoder encoder,
            final Supplier<String> label,
            final GpuTextureView mainColor,
            final Optional<Vector4fc> clearColor,
            final GpuTextureView sceneDepth,
            final java.util.OptionalDouble clearDepth
    ) {
        Instance instance = active;
        TerrainKind kind = ACTIVE_TERRAIN_KIND.get();
        if (instance == null) {
            return null;
        }
        if (kind == null) {
            instance.requireNoFallback("Sodium terrain descriptor selection has no active terrain kind");
            return null;
        }
        if (isShadowPassActive()) {
            return instance.createShadowTerrainRenderPass(encoder, label, kind);
        }
        int[] drawBuffers = instance.drawBuffersFor(kind);
        RenderPass renderPass = instance.createTerrainRenderPass(
                encoder, label, mainColor, clearColor.orElse(null), sceneDepth,
                clearDepth.isPresent() ? clearDepth.getAsDouble() : null, kind
        );
        IrisMetalPassTrace.observeTerrainPath(
                kind.name(), drawBuffers, drawBuffers.length, renderPass == null ? "native" : "extended"
        );
        return renderPass;
    }

    /**
     * Replaces Sodium's active terrain program with the generation-owned
     * synthetic program for every translated terrain kind. Both single- and
     * multi-target programs use the generation-owned descriptor; otherwise the
     * final pass would read a different colortex0 than terrain wrote.
     */
    public static RenderPipeline pipelineForTerrain(final RenderPipeline pipeline) {
        Instance instance = active;
        if (instance == null || !Instance.isSodiumPipeline(pipeline)) {
            return pipeline;
        }
        TerrainKind kind = Instance.discriminate(pipeline);
        if (isShadowPassActive()) {
            RenderPipeline shadow = instance.shadowSyntheticPipeline(pipeline, kind.shadowKey);
            if (shadow == null) {
                throw new IllegalStateException(
                        "Iris Metal shadow terrain has no atomic PSO for " + kind.shadowKey
                );
            }
            return shadow;
        }
        int[] drawBuffers = instance.drawBuffersFor(kind);
        String originalLocation = pipeline.getLocation().toString();
        RenderPipeline synthetic = instance.syntheticPipeline(kind, pipeline);
        if (synthetic == null) {
            instance.requireNoFallback(
                    "no translated terrain pipeline for " + kind + " from " + originalLocation
            );
            IrisMetalPassTrace.observeTerrainPipeline(
                    kind.name(), drawBuffers, originalLocation, originalLocation,
                    "native-fallback", false
            );
            return pipeline;
        }
        String status = drawBuffers.length <= 1
                ? "synthetic-single-target"
                : "synthetic-extended-targets";
        IrisMetalPassTrace.observeTerrainPipeline(
                kind.name(), drawBuffers, originalLocation, synthetic.getLocation().toString(),
                status, true
        );
        return synthetic;
    }

    /** Atomic descriptor/PSO selection for Mojang's non-Sodium prepared draws. */
    public static @Nullable CoreDrawOverride prepareCoreDraw(
            final RenderPipeline source,
            final @Nullable WorldRenderingPipeline worldPipeline,
            final Supplier<String> label,
            final GpuTextureView sceneColor,
            final Optional<Vector4fc> clearColor,
            final @Nullable GpuTextureView sceneDepth,
            final java.util.OptionalDouble clearDepth
    ) {
        Instance instance = active;
        if (instance == null) {
            return null;
        }
        if (!(worldPipeline instanceof MetalWorldRenderingPipeline metalPipeline)) {
            instance.requireNoFallback(
                    "core draw " + source.getLocation() + " has no active Metal world pipeline"
            );
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gameRenderer == null) {
            instance.requireNoFallback(
                    "core draw " + source.getLocation() + " has no initialized Minecraft render target"
            );
            return null;
        }
        RenderTarget mainTarget = minecraft.gameRenderer.mainRenderTarget();
        boolean shadow = isShadowPassActive();
        boolean writesMainTarget = sceneColor == mainTarget.getColorTextureView()
                && sceneDepth == mainTarget.getDepthTextureView();
        if (!shadow && !metalPipeline.shouldOverrideCoreShaders(writesMainTarget)) {
            return null;
        }
        ShaderKey key = IrisMetalCoreGbufferPipelines.resolve(source, worldPipeline);
        if (key == null) {
            instance.requireNoFallback(
                    "no ShaderKey routing exists for core pipeline " + source.getLocation()
            );
            return null;
        }
        if (key.isShadow() != shadow) {
            instance.requireNoFallback(
                    "core pipeline " + source.getLocation() + " resolved " + key
                            + " with shadow=" + key.isShadow() + " during shadow=" + shadow
            );
            return null;
        }
        return instance.prepareCoreDraw(
                source,
                key,
                label,
                sceneColor,
                clearColor.orElse(null),
                sceneDepth,
                clearDepth.isPresent() ? clearDepth.getAsDouble() : null
        );
    }

    public record CoreDrawOverride(RenderPipeline pipeline, RenderPassDescriptor descriptor) {
    }

    private IrisMetalPipelineOverrides() {
    }

    static boolean isShadowPassActive() {
        return net.irisshaders.iris.shadows.ShadowRenderingState.areShadowsCurrentlyBeingRendered();
    }

    enum TerrainKind {
        SOLID(ShaderKey.SODIUM_TERRAIN_SOLID, ShaderKey.SHADOW_SODIUM_TERRAIN_SOLID),
        CUTOUT(ShaderKey.SODIUM_TERRAIN_CUTOUT, ShaderKey.SHADOW_SODIUM_TERRAIN_CUTOUT),
        TRANSLUCENT(ShaderKey.SODIUM_TERRAIN_TRANSLUCENT, ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT);

        final ShaderKey shaderKey;
        final ShaderKey shadowKey;

        TerrainKind(final ShaderKey shaderKey, final ShaderKey shadowKey) {
            this.shaderKey = shaderKey;
            this.shadowKey = shadowKey;
        }
    }

    static Instance activate(
            final ProgramSet programSet,
            final Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap,
            final FrameUpdateNotifier updateNotifier,
            final IntSupplier renderStageSource
    ) {
        Instance instance = prepare(programSet, textureMap, updateNotifier, renderStageSource);
        select(instance);
        return instance;
    }

    /** Builds a generation without publishing it to any draw path. */
    static Instance prepare(
            final ProgramSet programSet,
            final Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap,
            final FrameUpdateNotifier updateNotifier,
            final IntSupplier renderStageSource
    ) {
        return create(
                programSet,
                textureMap,
                updateNotifier,
                renderStageSource,
                true,
                true
        );
    }

    /**
     * Headless compilation tests do not have a booted {@link Minecraft}
     * singleton, which Iris's fixed world-uniform registration requires. Keep
     * that limitation explicit instead of weakening the production uniform
     * graph or branching on Iris's global testing flag.
     */
    static Instance activateForTests(
            final ProgramSet programSet,
            final Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap
    ) {
        return activateForTests(programSet, textureMap, false);
    }

    static Instance activateForTests(
            final ProgramSet programSet,
            final Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap,
            final boolean strict
    ) {
        Instance instance = prepareForTests(programSet, textureMap, strict);
        select(instance);
        return instance;
    }

    static Instance prepareForTests(
            final ProgramSet programSet,
            final Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap,
            final boolean strict
    ) {
        return create(
                programSet,
                textureMap,
                new FrameUpdateNotifier(),
                () -> 0,
                false,
                strict
        );
    }

    private static Instance create(
            final ProgramSet programSet,
            final Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap,
            final FrameUpdateNotifier updateNotifier,
            final IntSupplier renderStageSource,
            final boolean productionLifecycle,
            final boolean strict
    ) {
        return new Instance(
                GENERATIONS.incrementAndGet(),
                programSet,
                textureMap,
                updateNotifier,
                renderStageSource,
                productionLifecycle,
                strict
        );
    }

    /** Selects one of Iris's cached per-dimension generations without retiring the others. */
    static void select(final Instance instance) {
        Objects.requireNonNull(instance, "instance");
        if (instance.closed) {
            throw new IllegalStateException(
                    "Cannot select closed Iris Metal generation " + instance.generation
            );
        }
        if (active == instance) {
            return;
        }
        active = instance;
        IrisMetalPackLifecycle.onSemanticPipelineSelected(instance.generation());
        IrisMetalPassTrace.activate(instance.programSet, instance.generation());
    }

    static void deactivate() {
        deactivate(active);
    }

    /** Retires only the generation owned by the pipeline being destroyed. */
    static void deactivate(final @Nullable Instance expected) {
        if (expected == null) {
            return;
        }
        boolean wasActive = active == expected;
        if (wasActive) {
            active = null;
        }
        expected.close();
        if (wasActive) {
            IrisMetalPackLifecycle.onSemanticPipelineDestroyed(expected.generation());
            IrisMetalPassTrace.close();
        }
    }

    /** Per-frame uniform refresh; driven by {@link MetalWorldRenderingPipeline#beginLevelRendering()}. */
    static void updateFrame() {
        Instance instance = active;
        if (instance == null) {
            return;
        }
        IrisMetalPassTrace.observeLifecycle("update_frame_enter");
        IrisMetalPassTrace.observeFogState("update_frame_enter");
        // Iris dispatches setup[] only when RenderTargets.resizeIfNeeded()
        // reports a resource recreation. A full clear is a separate contract
        // and must not re-run setup programs on an otherwise stable generation.
        instance.setupRequiredThisFrame = false;
        // Every GPU resource the draw path may need is created and uploaded
        // HERE, not on demand in pushDescriptor. Allocating or uploading while
        // a render encoder is live ends that encoder (writeToTexture /
        // writeToBuffer / clearDepthTexture all open a blit encoder), and the
        // caller then writes into a closed handle — see handoff §6 iteration 5.
        instance.prewarm(MetalDevice.current());
        IrisMetalPassTrace.observeLifecycle("prewarm_complete");
        IrisMetalPassTrace.observeFogState("prewarm_complete");
        IrisMetalPassTrace.beginFrame(instance.uniformValues.frameCounter());
        instance.beginFrame();
        instance.uniformValues.updateFrame();
        IrisMetalPassTrace.observeLifecycle("uniform_update_complete");
        IrisMetalPassTrace.observeFogState("uniform_update_complete");
    }

    /** Captures depthtex1 at Iris's opaque-to-translucent phase boundary. */
    static void captureNoTranslucentsDepth() {
        Instance instance = active;
        if (instance != null) {
            instance.captureNoTranslucentsDepth();
        }
    }

    /** Captures depthtex2 at Iris's translucent-to-hand phase boundary. */
    static void captureNoHandDepth() {
        Instance instance = active;
        if (instance != null) {
            instance.captureNoHandDepth();
        }
    }

    /** Samples Iris centerDepthSmooth from live scene depth before depthtex2 is captured. */
    static void sampleCenterDepth() {
        Instance instance = active;
        if (instance != null) {
            instance.sampleCenterDepth();
        }
    }

    static void executePostStage(final IrisMetalPostChain.Stage stage) {
        Instance instance = active;
        if (instance != null) {
            instance.executePostStage(stage);
        }
    }

    static void executeFinal() {
        Instance instance = active;
        if (instance != null) {
            instance.executeFinal();
        }
    }

    static void executeColorSpace(final ColorSpace colorSpace) {
        Instance instance = active;
        if (instance != null) {
            instance.executeColorSpace(colorSpace);
        }
    }

    static boolean shadowsEnabled() {
        Instance instance = active;
        return instance != null && instance.shadowsEnabled();
    }

    static void executeShadowFrame(final IrisMetalShadowPipeline.LevelRendererAdapter adapter) {
        Instance instance = active;
        if (instance == null) {
            throw new IllegalStateException("Iris Metal shadow frame has no active pipeline generation");
        }
        instance.executeShadowFrame(adapter);
    }

    static void completeShadowFrame() {
        Instance instance = active;
        if (instance != null) {
            instance.completeShadowFrame();
        }
    }

    /**
     * Draw-time resource fallback for a bound terrain override, consulted by
     * {@link MetalRenderPass} when a name the PSO declares has no value set.
     *
     * <p>Sodium sets the resources <i>its own</i> shader needs; the pack's
     * program declares more. Rather than teach the sodium mixin about pack
     * resources (at pass-creation time sodium has not yet bound its textures,
     * so they cannot be forwarded), the gap is closed here, where everything
     * sodium bound is already visible.</p>
     *
     * @return the resolved binding, or {@code null} to let the caller raise the
     *         normal missing-resource error
     */
    static MetalRenderPass.@Nullable TextureViewAndSampler fallbackTexture(
            final MetalDevice device,
            final MetalCompiledRenderPipeline pipeline,
            final String name,
            final Map<String, MetalRenderPass.TextureViewAndSampler> bound
    ) {
        Instance instance = active;
        if (instance == null) {
            return null;
        }
        return instance.resolveTexture(device, pipeline, name, bound);
    }

    /** Uniform-buffer counterpart of {@link #fallbackTexture}. */
    static @Nullable GpuBufferSlice fallbackUniform(
            final MetalDevice device, final MetalCompiledRenderPipeline pipeline, final String name
    ) {
        Instance instance = active;
        if (instance == null) {
            return null;
        }
        return instance.resolveUniform(device, pipeline, name, null, null);
    }

    static @Nullable GpuBufferSlice fallbackUniformForDraw(
            final MetalRenderPass pass,
            final MetalDevice device,
            final MetalCompiledRenderPipeline pipeline,
            final String name,
            final Map<String, GpuBufferSlice> bound
    ) {
        Instance instance = active;
        if (instance == null) {
            return null;
        }
        return instance.resolveUniform(device, pipeline, name, pass, bound);
    }

    static @Nullable GpuTextureView fallbackStorageImage(
            final MetalDevice device,
            final MetalCompiledRenderPipeline pipeline,
            final String name
    ) {
        Instance instance = active;
        return instance == null ? null : instance.resolveStorageImage(device, pipeline, name);
    }

    static @Nullable Instance active() {
        return active;
    }

    /**
     * Validation receipt only: {@code -1} means that the shaders-off path owns
     * no Iris Metal generation. Returning the scalar generation rather than
     * the mutable instance keeps diagnostics from becoming another draw-path
     * owner.
     */
    public static int activeGenerationForDiagnostics() {
        Instance instance = active;
        return instance == null ? -1 : instance.generation();
    }

    /**
     * Pipeline-compile hook. Returns a compiled override for recognized sodium
     * terrain pipelines while a pack runtime is active, or {@code null} to let
     * the caller compile the pipeline natively.
     */
    static @Nullable MetalCompiledRenderPipeline tryCompile(
            final MetalDevice device,
            final RenderPipeline pipeline,
            final @Nullable ShaderSource fallbackSource
    ) {
        Instance instance = active;
        if (instance == null) {
            return null;
        }
        return instance.compileOverride(device, pipeline, fallbackSource);
    }

    static final class Instance {
        private final int generation;
        private final boolean productionLifecycle;
        private final boolean strict;
        private final ProgramSet programSet;
        private final ShaderPack pack;
        private final ProgramFallbackResolver coreResolver;
        private final Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap;
        private final Map<TerrainKind, MetalIrisShaderCompiler.GlslProgram> programs = new EnumMap<>(TerrainKind.class);
        private final Map<TerrainKind, RenderPipeline> syntheticPipelines = new EnumMap<>(TerrainKind.class);
        private final Map<ShaderKey, MetalIrisShaderCompiler.GlslProgram> corePrograms = new EnumMap<>(ShaderKey.class);
        private final Map<CorePipelineKey, RenderPipeline> coreSyntheticPipelines = new HashMap<>();
        private final Map<RenderPipeline, ShaderKey> coreSyntheticKeys =
                java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
        private final Map<RenderPipeline, RenderPipeline> coreSyntheticSources =
                java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
        private final Map<Identifier, String> generatedGlsl = new java.util.concurrent.ConcurrentHashMap<>();
        private final Set<TerrainKind> reportedFailures = EnumSet.noneOf(TerrainKind.class);
        private final Set<CorePipelineKey> reportedCoreFailures = java.util.concurrent.ConcurrentHashMap.newKeySet();
        /**
         * Compiled override -> kind, so draw-time fallbacks know whose block to
         * bind. Concurrent because {@code MetalDevice} gained a background
         * prewarm thread: overrides may now be compiled off the render thread
         * while a draw is reading this map.
         */
        private final Map<MetalCompiledRenderPipeline, TerrainKind> compiledKinds =
                java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
        private final Map<MetalCompiledRenderPipeline, ShaderKey> compiledCoreKeys =
                java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
        private final Map<MetalCompiledRenderPipeline, Optional<BlendFunction>> compiledGlobalBlends =
                java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
        private final IrisMetalUniformValues uniformValues;
        private final GpuFormat[] targetFormats;
        private final PackDirectives packDirectives;
        private final IrisMetalPostChain postChain;
        /**
         * Which kinds may use their full DRAWBUFFERS layout, frozen at
         * construction.
         *
         * <p>Reading the mutable static at compile time is a race: with async
         * precompile on, the prewarm thread can build a sodium terrain pipeline
         * <i>before</i> the world loads and the flag flips. That native PSO then
         * lives in the pipeline cache for the rest of the generation while the
         * terrain pass is being given extra attachments — the PSO is selected by
         * attachment signature, so the lookup misses and the draw dies. The
         * decision has to be per-generation and immutable, never per-compile.</p>
         */
        private final Set<TerrainKind> extendedKinds;
        private @Nullable IrisMetalWhitePixel whitePixel;
        private @Nullable IrisMetalNoiseTexture noiseTexture;
        private @Nullable IrisMetalCustomTextures customTextures;
        private @Nullable IrisMetalComputeResources computeResources;
        private @Nullable IrisMetalCenterDepthSampler centerDepthSampler;
        private @Nullable IrisMetalRenderTargets renderTargets;
        private @Nullable IrisMetalShadowPipeline shadowPipeline;
        /** Live Mojang-owned value of Iris's externally managed texture unit 1. */
        private MetalRenderPass.@Nullable TextureViewAndSampler mojangExternalOverlay;
        private boolean postPrepared;
        private boolean setupRequiredThisFrame;
        /** The device the overrides were compiled on; needed to drop them again on teardown. */
        private @Nullable MetalDevice device;
        private boolean reportedMissingVertexFormat;
        private boolean closed;
        private int corePipelineSequence;

        private record CorePipelineKey(RenderPipeline source, ShaderKey key) {
        }

        private Instance(
                final int generation,
                final ProgramSet programSet,
                final Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap,
                final FrameUpdateNotifier updateNotifier,
                final IntSupplier renderStageSource,
                final boolean productionLifecycle,
                final boolean strict
        ) {
            this.generation = generation;
            this.productionLifecycle = productionLifecycle;
            this.strict = strict;
            this.programSet = programSet;
            this.pack = programSet.getPack();
            this.coreResolver = new ProgramFallbackResolver(programSet);
            this.textureMap = textureMap;
            this.packDirectives = programSet.getPackDirectives();
            if (productionLifecycle) {
                CustomUniforms customUniforms = this.pack.customUniforms.build(uniformHolder ->
                        CommonUniforms.addNonDynamicUniforms(
                                uniformHolder,
                                this.pack.getIdMap(),
                                this.packDirectives,
                                updateNotifier
                        )
                );
                CustomUniformFixedInputUniformsHolder.Builder programFixedInputs =
                        new CustomUniformFixedInputUniformsHolder.Builder();
                CommonUniforms.addNonDynamicUniforms(
                        programFixedInputs,
                        this.pack.getIdMap(),
                        this.packDirectives,
                        updateNotifier
                );
                CustomUniformFixedInputUniformsHolder programFixedInputGraph = programFixedInputs.build();
                IrisMetalDynamicUniforms dynamicUniformGraph = IrisMetalDynamicUniforms.create(renderStageSource);
                this.uniformValues = new IrisMetalUniformValues(
                        this.packDirectives.getSunPathRotation(),
                        customUniforms,
                        programFixedInputGraph,
                        dynamicUniformGraph,
                        updateNotifier,
                        renderStageSource
                );
            } else {
                this.uniformValues = new IrisMetalUniformValues(this.packDirectives.getSunPathRotation());
            }
            this.targetFormats = targetFormats(this.packDirectives);
            this.postChain = IrisMetalPostChain.create(
                    generation, programSet, this.targetFormats.length, new java.util.BitSet()
            );
            this.postChain.registerUniforms(this.uniformValues);
            this.extendedKinds = extendedTerrainTargets
                    ? EnumSet.allOf(TerrainKind.class)
                    : EnumSet.noneOf(TerrainKind.class);
            for (TerrainKind kind : TerrainKind.values()) {
                ProgramSource source = resolveSource(programSet, kind.shaderKey.getProgram());
                if (source == null) {
                    requireNoFallback(
                            "fallback chain for terrain " + kind + " ("
                                    + kind.shaderKey.getProgram() + ") is exhausted"
                    );
                    Metallum.LOGGER.warn(
                            "[metallum-iris] no pack program for {} (fallback chain of {} exhausted); terrain kind stays native",
                            kind, kind.shaderKey.getProgram()
                    );
                    continue;
                }
                try {
                    MetalIrisShaderCompiler.GlslProgram program = MetalIrisShaderCompiler.translateSodiumTerrain(
                            source.getName(), source, kind.shaderKey.getAlphaTest(), textureMap
                    );
                    this.programs.put(kind, program);
                    this.uniformValues.register(kind, program);
                    Metallum.LOGGER.info(
                            "[metallum-iris] translated sodium terrain {} from pack program {} (drawBuffers={})",
                            kind, source.getName(),
                            java.util.Arrays.toString(this.programs.get(kind).drawBuffers())
                    );
                } catch (MetalIrisShaderCompiler.TranslationException e) {
                    requireNoFallback(
                            "translation of terrain " + kind + " from " + source.getName()
                                    + " failed in phase " + e.phase(),
                            e
                    );
                    Metallum.LOGGER.error(
                            "[metallum-iris] translation of {} ({}) failed in phase {}: {}; terrain kind stays native",
                            kind, source.getName(), e.phase(), e.getMessage()
                    );
                }
            }
        }

        private void requireNoFallback(final String reason) {
            requireNoFallback(reason, null);
        }

        private void requireNoFallback(final String reason, final @Nullable Throwable cause) {
            if (!this.strict) {
                return;
            }
            String message = "Iris Metal strict mode rejected generation " + this.generation + ": " + reason;
            if (cause == null) {
                throw new IrisMetalPackRejectedException(message);
            }
            throw new IrisMetalPackRejectedException(message, cause);
        }

        int generation() {
            return this.generation;
        }

        private boolean shadowsEnabled() {
            IrisMetalShadowPipeline shadows = this.shadowPipeline;
            return !this.closed && shadows != null && shadows.enabled();
        }

        private void executeShadowFrame(final IrisMetalShadowPipeline.LevelRendererAdapter adapter) {
            IrisMetalShadowPipeline shadows = this.shadowPipeline;
            MetalDevice currentDevice = this.device != null ? this.device : MetalDevice.current();
            if (this.closed || shadows == null || currentDevice == null) {
                throw new IllegalStateException("Iris Metal shadow resources were not prepared before renderShadows");
            }
            shadows.executeFrame(currentDevice, adapter, this.postResources);
            IrisMetalPassTrace.observePhase("shadow", "executed");
        }

        private void completeShadowFrame() {
            IrisMetalShadowPipeline shadows = this.shadowPipeline;
            MetalDevice currentDevice = this.device != null ? this.device : MetalDevice.current();
            if (this.closed || shadows == null || currentDevice == null) {
                return;
            }
            shadows.completeWithoutRendering(currentDevice.commandEncoder());
            IrisMetalPassTrace.observePhase("shadow", "cleared");
        }

        MetalIrisShaderCompiler.@Nullable GlslProgram program(final TerrainKind kind) {
            return this.programs.get(kind);
        }

        /** The DRAWBUFFERS-derived color-target layout for a kind, {@code {0}} when the directive is absent. */
        int[] drawBuffersFor(final TerrainKind kind) {
            MetalIrisShaderCompiler.GlslProgram program = this.programs.get(kind);
            if (program == null || program.drawBuffers().length == 0) {
                return new int[]{0};
            }
            return program.drawBuffers();
        }

        /** Format of one logical Iris colortex target in this pack generation. */
        GpuFormat targetFormat(final int logicalTarget) {
            if (logicalTarget < 0 || logicalTarget >= this.targetFormats.length) {
                throw new IllegalArgumentException(
                        "Iris logical color target out of range: " + logicalTarget
                                + " (count=" + this.targetFormats.length + ")"
                );
            }
            return this.targetFormats[logicalTarget];
        }

        private @Nullable CoreDrawOverride prepareCoreDraw(
                final RenderPipeline source,
                final ShaderKey key,
                final Supplier<String> label,
                final GpuTextureView sceneColor,
                final @Nullable Vector4fc clearColor,
                final @Nullable GpuTextureView sceneDepth,
                final @Nullable Double clearDepth
        ) {
            if (this.closed) {
                requireNoFallback("core draw " + key + " reached a retired generation");
                return null;
            }
            MetalIrisShaderCompiler.GlslProgram program = coreProgram(key);
            if (program == null) {
                requireNoFallback("no translated core program for " + key);
                return null;
            }
            RenderPipeline synthetic = coreSyntheticPipeline(source, key, program);
            if (synthetic == null) {
                requireNoFallback("no synthetic core pipeline for " + key);
                return null;
            }
            MetalDevice currentDevice = this.device != null ? this.device : MetalDevice.current();
            if (currentDevice == null) {
                requireNoFallback("no Metal device while preparing core draw " + key);
                return null;
            }
            CorePipelineKey token = new CorePipelineKey(source, key);
            try {
                // Core programs are translated lazily after the frame prewarm.
                // Allocate their block before opening this draw's encoder.
                this.uniformValues.prewarm(currentDevice);
                MetalCompiledRenderPipeline compiled = currentDevice.getOrCompilePipeline(synthetic);
                if (this.compiledCoreKeys.get(compiled) != key) {
                    throw new IllegalStateException(
                            "Synthetic core pipeline was compiled without its generation-owned ShaderKey token"
                    );
                }
                RenderPassDescriptor descriptor;
                if (key.isShadow()) {
                    IrisMetalShadowPipeline shadows = this.shadowPipeline;
                    IrisMetalShadowPipeline.ShadowProgram shadowProgram = shadows == null
                            ? null
                            : shadows.program(key).orElse(null);
                    if (shadowProgram == null) {
                        throw new IllegalStateException("No active Metal shadow program for " + key);
                    }
                    descriptor = shadows.createPersistentGbufferDescriptor(label.get(), shadowProgram);
                } else {
                    IrisMetalRenderTargets targets = this.renderTargets;
                    if (targets == null) {
                        requireNoFallback("render targets are unavailable for core draw " + key);
                        return null;
                    }
                    descriptor = targets.createTerrainWriteDescriptor(
                            label.get(), program.drawBuffers(), sceneColor, clearColor, sceneDepth, clearDepth
                    );
                }
                verifyCorePipelineDescriptor(compiled, descriptor);
                if (this.closed || active != this) {
                    throw new IllegalStateException("Iris generation changed while preparing the core draw");
                }
                return new CoreDrawOverride(synthetic, descriptor);
            } catch (Throwable t) {
                requireNoFallback(
                        "core draw " + key + " could not prepare an atomic PSO/descriptor pair",
                        t
                );
                if (this.reportedCoreFailures.add(token)) {
                    Metallum.LOGGER.error(
                            "[metallum-iris] core draw {} could not prepare an atomic PSO/descriptor pair; draw stays native",
                            key, t
                    );
                }
                return null;
            }
        }

        private static void verifyCorePipelineDescriptor(
                final MetalCompiledRenderPipeline compiled,
                final RenderPassDescriptor descriptor
        ) {
            MTLPixelFormat[] pipelineFormats = compiled.colorAttachmentFormats();
            java.util.List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> attachments =
                    descriptor.colorAttachments();
            if (pipelineFormats.length != attachments.size()) {
                throw new IllegalStateException(
                        "Core pipeline/render-pass color attachment count mismatch: pipeline="
                                + pipelineFormats.length + ", pass=" + attachments.size()
                );
            }
            for (int slot = 0; slot < pipelineFormats.length; slot++) {
                RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = attachments.get(slot);
                MTLPixelFormat attachmentFormat = attachment == null
                        ? MTLPixelFormat.Invalid
                        : metalTexture(attachment.textureView()).mtlPixelFormat();
                if (pipelineFormats[slot] != attachmentFormat) {
                    throw new IllegalStateException(
                            "Core pipeline/render-pass color attachment mismatch at slot " + slot
                                    + ": pipeline=" + pipelineFormats[slot] + ", pass=" + attachmentFormat
                    );
                }
            }

            RenderPassDescriptor.Attachment<java.util.OptionalDouble> depthAttachment = descriptor.depthAttachment();
            MTLPixelFormat depthFormat = MTLPixelFormat.Invalid;
            MTLPixelFormat stencilFormat = MTLPixelFormat.Invalid;
            if (depthAttachment != null) {
                MetalGpuTexture texture = metalTexture(depthAttachment.textureView());
                depthFormat = texture.mtlDepthPixelFormat();
                stencilFormat = texture.mtlStencilPixelFormat();
            }
            compiled.getNativePipeline(depthFormat, stencilFormat);
        }

        private static MetalGpuTexture metalTexture(final GpuTextureView view) {
            if (view.texture() instanceof MetalGpuTexture texture) {
                return texture;
            }
            throw new IllegalStateException(
                    "Iris core render pass contains a non-Metal attachment: " + view.texture().getClass().getName()
            );
        }

        MetalIrisShaderCompiler.@Nullable GlslProgram coreProgram(final ShaderKey key) {
            synchronized (this.corePrograms) {
                MetalIrisShaderCompiler.GlslProgram existing = this.corePrograms.get(key);
                if (existing != null) {
                    return existing;
                }
                CorePipelineKey failureToken = new CorePipelineKey(null, key);
                if (this.reportedCoreFailures.contains(failureToken)) {
                    requireNoFallback("previous core-program admission failed for " + key);
                    return null;
                }
                if (key.isShadow()) {
                    IrisMetalShadowPipeline shadows = this.shadowPipeline;
                    IrisMetalShadowPipeline.ShadowProgram shadow = shadows == null
                            ? null
                            : shadows.program(key).orElse(null);
                    if (shadow == null) {
                        this.reportedCoreFailures.add(failureToken);
                        requireNoFallback("no active shadow program for " + key);
                        return null;
                    }
                    MetalIrisShaderCompiler.GlslProgram translated = shadow.translated();
                    this.corePrograms.put(key, translated);
                    this.uniformValues.register(key, "shadow_" + key.getName(), translated);
                    return translated;
                }
                ProgramSource source = this.coreResolver.resolve(key.getProgram()).orElse(null);
                if (source == null) {
                    this.reportedCoreFailures.add(failureToken);
                    requireNoFallback(
                            "fallback chain for core key " + key + " (" + key.getProgram() + ") is exhausted"
                    );
                    Metallum.LOGGER.warn(
                            "[metallum-iris] no pack program for core key {} (fallback chain of {} exhausted)",
                            key, key.getProgram()
                    );
                    return null;
                }
                try {
                    MetalIrisShaderCompiler.GlslProgram translated =
                            MetalIrisShaderCompiler.translateVanillaGbuffers(
                                    key.getName(),
                                    source,
                                    key,
                                    this.coreResolver.has(ProgramId.Line),
                                    this.textureMap
                            );
                    this.corePrograms.put(key, translated);
                    this.uniformValues.register(key, "core_" + key.getName(), translated);
                    Metallum.LOGGER.info(
                            "[metallum-iris] translated core {} from pack program {} (drawBuffers={})",
                            key, source.getName(), java.util.Arrays.toString(translated.drawBuffers())
                    );
                    return translated;
                } catch (Throwable t) {
                    this.reportedCoreFailures.add(failureToken);
                    requireNoFallback(
                            "translation of core " + key + " from " + source.getName() + " failed",
                            t
                    );
                    Metallum.LOGGER.error(
                            "[metallum-iris] translation of core {} from {} failed; draw stays native",
                            key, source.getName(), t
                    );
                    return null;
                }
            }
        }

        @Nullable RenderPipeline coreSyntheticPipeline(
                final RenderPipeline source,
                final ShaderKey key,
                final MetalIrisShaderCompiler.GlslProgram program
        ) {
            CorePipelineKey token = new CorePipelineKey(source, key);
            synchronized (this.coreSyntheticPipelines) {
                RenderPipeline existing = this.coreSyntheticPipelines.get(token);
                if (existing != null) {
                    return existing;
                }
                if (this.reportedCoreFailures.contains(token)) {
                    requireNoFallback(
                            "previous synthetic core-pipeline construction failed for " + key
                                    + " from " + source.getLocation()
                    );
                    return null;
                }
                try {
                    RenderPipeline synthetic = buildCoreSynthetic(source, key, program);
                    this.coreSyntheticPipelines.put(token, synthetic);
                    this.coreSyntheticKeys.put(synthetic, key);
                    this.coreSyntheticSources.put(synthetic, source);
                    return synthetic;
                } catch (Throwable t) {
                    this.reportedCoreFailures.add(token);
                    requireNoFallback(
                            "could not build core pipeline " + key + " for " + source.getLocation(),
                            t
                    );
                    Metallum.LOGGER.error(
                            "[metallum-iris] could not build core pipeline {} for {}; draw stays native",
                            key, source.getLocation(), t
                    );
                    return null;
                }
            }
        }

        private @Nullable RenderPipeline shadowSyntheticPipeline(
                final RenderPipeline source,
                final ShaderKey key
        ) {
            MetalIrisShaderCompiler.GlslProgram program = coreProgram(key);
            return program == null ? null : coreSyntheticPipeline(source, key, program);
        }

        private @Nullable RenderPass createShadowTerrainRenderPass(
                final CommandEncoder encoder,
                final Supplier<String> label,
                final TerrainKind kind
        ) {
            IrisMetalShadowPipeline shadows = this.shadowPipeline;
            if (this.closed || shadows == null) {
                requireNoFallback("shadow targets are unavailable for terrain " + kind);
                return null;
            }
            IrisMetalShadowPipeline.ShadowProgram program = shadows.program(kind.shadowKey).orElse(null);
            if (program == null) {
                throw new IllegalStateException("No pack shadow program for " + kind.shadowKey);
            }
            MetalDevice currentDevice = this.device != null ? this.device : MetalDevice.current();
            if (currentDevice == null) {
                throw new IllegalStateException("No Metal device while opening the Iris shadow terrain pass");
            }
            // Sodium creates its RenderPass before setPipeline. Resolve the
            // matching lazy shadow program now so its ShaderKey uniform block
            // is registered before prewarm; doing this from pipelineForTerrain
            // would be too late because the render encoder is already live.
            if (coreProgram(kind.shadowKey) != program.translated()) {
                throw new IllegalStateException(
                        "Shadow terrain program registration changed for " + kind.shadowKey
                );
            }
            this.uniformValues.prewarm(currentDevice);
            return encoder.createRenderPass(shadows.createPersistentGbufferDescriptor(label.get(), program));
        }

        private @Nullable RenderPass createTerrainRenderPass(
                final CommandEncoder encoder,
                final Supplier<String> label,
                final GpuTextureView mainColor,
                final @Nullable Vector4fc clearColor,
                final GpuTextureView sceneDepth,
                final @Nullable Double clearDepth,
                final TerrainKind kind
        ) {
            IrisMetalRenderTargets targets = this.renderTargets;
            if (targets == null) {
                requireNoFallback(
                        "render targets are unavailable for terrain " + kind + " DRAWBUFFERS "
                                + java.util.Arrays.toString(drawBuffersFor(kind))
                );
                if (this.reportedFailures.add(kind)) {
                    Metallum.LOGGER.warn(
                            "[metallum-iris] terrain {} needs DRAWBUFFERS {} but Iris targets are not initialized;"
                                    + " keeping Sodium's single-attachment pass",
                            kind, java.util.Arrays.toString(drawBuffersFor(kind))
                    );
                }
                return null;
            }
            return encoder.createRenderPass(targets.createTerrainWriteDescriptor(
                    label.get(), drawBuffersFor(kind), mainColor, clearColor, sceneDepth, clearDepth
            ));
        }

        static TerrainKind discriminate(final RenderPipeline pipeline) {
            ColorTargetState target = pipeline.getColorTargetState();
            if (target != null && target.blendFunction().isPresent()) {
                return TerrainKind.TRANSLUCENT;
            }
            if (pipeline.getShaderDefines().asSourceDirectives().contains("CUTOUT")) {
                return TerrainKind.CUTOUT;
            }
            return TerrainKind.SOLID;
        }

        static boolean isSodiumPipeline(final RenderPipeline pipeline) {
            return pipeline.getLocation().getNamespace().contains("sodium");
        }

        private boolean isSyntheticPipeline(final RenderPipeline pipeline) {
            String path = pipeline.getLocation().getPath();
            return pipeline.getLocation().getNamespace().equals("metallum")
                    && path.startsWith("iris/gen" + this.generation + "/sodium_terrain_");
        }

        private @Nullable TerrainKind syntheticKind(final RenderPipeline pipeline) {
            if (!isSyntheticPipeline(pipeline)) {
                return null;
            }
            String prefix = "iris/gen" + this.generation + "/sodium_terrain_";
            String suffix = pipeline.getLocation().getPath().substring(prefix.length());
            try {
                return TerrainKind.valueOf(suffix.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }

        /**
         * Returns the synthetic RenderPipeline for a translated terrain kind.
         * The cache is synchronized because async Metal prewarm and the render
         * thread can reach this method for the same generation concurrently.
         */
        private @Nullable RenderPipeline syntheticPipeline(
                final TerrainKind kind,
                final RenderPipeline source
        ) {
            if (this.closed || this.programs.get(kind) == null) {
                requireNoFallback("no admitted terrain program for " + kind);
                return null;
            }
            int[] drawBuffers = drawBuffersFor(kind);
            if (drawBuffers.length > 1 && !this.extendedKinds.contains(kind)) {
                requireNoFallback(
                        "terrain " + kind + " requires unprovided MRT DRAWBUFFERS "
                                + java.util.Arrays.toString(drawBuffers)
                );
                return null;
            }
            VertexFormat chunkFormat = chunkVertexFormat();
            if (chunkFormat == null) {
                if (!this.reportedMissingVertexFormat) {
                    this.reportedMissingVertexFormat = true;
                    Metallum.LOGGER.error(
                            "[metallum-iris] WorldRenderingSettings has no chunk vertex format; terrain overrides disabled"
                    );
                }
                requireNoFallback("WorldRenderingSettings has no chunk vertex format for terrain " + kind);
                return null;
            }
            synchronized (this.syntheticPipelines) {
                return this.syntheticPipelines.computeIfAbsent(
                        kind, k -> buildSynthetic(k, this.programs.get(k), source, chunkFormat)
                );
            }
        }

        private @Nullable MetalCompiledRenderPipeline compileOverride(
                final MetalDevice device,
                final RenderPipeline pipeline,
                final @Nullable ShaderSource fallbackSource
        ) {
            if (this.closed) {
                return null;
            }
            ShaderKey coreKey = this.coreSyntheticKeys.get(pipeline);
            if (coreKey != null) {
                return compileCoreOverride(device, pipeline, coreKey, fallbackSource);
            }
            boolean synthetic = isSyntheticPipeline(pipeline);
            if (!synthetic && !isSodiumPipeline(pipeline)) {
                // The mainline ShaderChunkRendererMetalFxMixin owns the one-shot
                // warning for the MetalFX CUTOUT namespace substitution. Keeping
                // another warning here would report the same event twice after
                // the Iris branch is merged.
                return null;
            }
            TerrainKind kind = synthetic ? syntheticKind(pipeline) : discriminate(pipeline);
            if (kind == null) {
                return null;
            }
            MetalIrisShaderCompiler.GlslProgram program = this.programs.get(kind);
            if (program == null) {
                requireNoFallback("no admitted terrain program for compile of " + kind);
                return null;
            }
            int[] drawBuffers = drawBuffersFor(kind);
            if (!synthetic && drawBuffers.length > 1 && !this.extendedKinds.contains(kind)) {
                requireNoFallback(
                        "terrain " + kind + " compile requires unprovided MRT DRAWBUFFERS "
                                + java.util.Arrays.toString(drawBuffers)
                );
                // The compiled PSO is looked up by the render pass's attachment
                // signature, so a multi-target program can only be used once the
                // sodium terrain pass actually carries those extra attachments
                // (handoff step S6). Until then this kind fails open rather than
                // producing a PSO nothing can bind.
                if (this.reportedFailures.add(kind)) {
                    Metallum.LOGGER.warn(
                            "[metallum-iris] terrain {} writes DRAWBUFFERS {} but the sodium terrain pass still has a"
                                    + " single attachment; staying native for this kind until the pass is extended",
                            kind, java.util.Arrays.toString(drawBuffers)
                    );
                }
                return null;
            }
            try {
                RenderPipeline compilePipeline = synthetic
                        ? pipeline
                        : this.syntheticPipeline(kind, pipeline);
                if (compilePipeline == null) {
                    requireNoFallback("no synthetic terrain pipeline available while compiling " + kind);
                    return null;
                }
                ShaderSource source = (id, type) -> {
                    String generated = this.generatedGlsl.get(id);
                    if (generated != null) {
                        return generated;
                    }
                    return fallbackSource == null ? null : fallbackSource.get(id, type);
                };
                Metallum.LOGGER.info(
                        "[metallum-iris] compiling terrain override {} for {} via {}",
                        kind, pipeline.getLocation(), compilePipeline.getLocation()
                );
                MetalCompiledRenderPipeline compiled = MetalCrossShaderCompiler.compile(device, compilePipeline, source);
                this.compiledKinds.put(compiled, kind);
                this.compiledGlobalBlends.put(
                        compiled,
                        worldGlobalBlend(
                                this.coreSyntheticSources.getOrDefault(pipeline, pipeline),
                                resolveSource(this.programSet, kind.shaderKey.getProgram()),
                                kind.shaderKey.getProgram().getBlendModeOverride()
                        )
                );
                this.device = device;
                return compiled;
            } catch (Throwable t) {
                requireNoFallback("terrain override " + kind + " failed to compile", t);
                if (this.reportedFailures.add(kind)) {
                    Metallum.LOGGER.error(
                            "[metallum-iris] terrain override {} failed to compile; staying native for this kind",
                            kind, t
                    );
                }
                return null;
            }
        }

        private MetalCompiledRenderPipeline compileCoreOverride(
                final MetalDevice device,
                final RenderPipeline pipeline,
                final ShaderKey key,
                final @Nullable ShaderSource fallbackSource
        ) {
            MetalIrisShaderCompiler.GlslProgram program;
            synchronized (this.corePrograms) {
                program = this.corePrograms.get(key);
            }
            if (program == null) {
                throw new IllegalStateException("No translated core program registered for " + key);
            }
            try {
                ShaderSource source = (id, type) -> {
                    String generated = this.generatedGlsl.get(id);
                    if (generated != null) {
                        return generated;
                    }
                    return fallbackSource == null ? null : fallbackSource.get(id, type);
                };
                Metallum.LOGGER.info(
                        "[metallum-iris] compiling core override {} via {}",
                        key, pipeline.getLocation()
                );
                MetalCompiledRenderPipeline compiled = MetalCrossShaderCompiler.compile(device, pipeline, source);
                this.compiledCoreKeys.put(compiled, key);
                this.compiledGlobalBlends.put(
                        compiled,
                        worldGlobalBlend(
                                pipeline,
                                this.coreResolver.resolve(key.getProgram()).orElse(null),
                                key.getProgram().getBlendModeOverride()
                        )
                );
                this.device = device;
                return compiled;
            } catch (Throwable t) {
                CorePipelineKey failureToken = new CorePipelineKey(pipeline, key);
                if (this.reportedCoreFailures.add(failureToken)) {
                    Metallum.LOGGER.error(
                            "[metallum-iris] core override {} failed to compile; refusing ordinary synthetic-shader fallback",
                            key, t
                    );
                }
                throw new IllegalStateException("Failed to compile Iris core override " + key, t);
            }
        }

        private RenderPipeline buildSynthetic(
                final TerrainKind kind,
                final MetalIrisShaderCompiler.GlslProgram program,
                final RenderPipeline source,
                final VertexFormat chunkFormat
        ) {
            String base = "iris/gen" + this.generation + "/sodium_terrain_" + kind.name().toLowerCase(Locale.ROOT);
            Identifier vertexId = Identifier.fromNamespaceAndPath("metallum", base + "_v");
            Identifier fragmentId = Identifier.fromNamespaceAndPath("metallum", base + "_f");
            this.generatedGlsl.put(vertexId, program.vertexGlsl());
            this.generatedGlsl.put(fragmentId, program.fragmentGlsl());

            RenderPipeline.Builder builder = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath("metallum", base))
                    .withVertexShader(vertexId)
                    .withFragmentShader(fragmentId)
                    .withCull(source.isCull())
                    .withPolygonMode(source.getPolygonMode())
                    .withPrimitiveTopology(source.getPrimitiveTopology());

            ColorTargetState sourceTarget = source.getColorTargetState();
            if (sourceTarget == null) {
                throw new IllegalStateException("Sodium pipeline " + source.getLocation() + " has no color target");
            }
            ProgramSource sourceProgram = resolveSource(this.programSet, kind.shaderKey.getProgram());
            if (sourceProgram == null) {
                throw new IllegalStateException("No pack program remains for terrain kind " + kind);
            }
            Optional<BlendFunction> globalBlend = sourceTarget.blendFunction();
            BlendModeOverride globalOverride = sourceProgram.getDirectives().getBlendModeOverride()
                    .orElse(kind.shaderKey.getProgram().getBlendModeOverride());
            if (globalOverride != null) {
                globalBlend = irisBlendFunction(globalOverride);
            }
            int[] drawBuffers = drawBuffersFor(kind);
            for (int index = 0; index < drawBuffers.length; index++) {
                int logicalTarget = drawBuffers[index];
                Optional<BlendFunction> blend = globalBlend;
                for (var bufferOverride : sourceProgram.getDirectives().getBufferBlendOverrides()) {
                    if (bufferOverride.index() == logicalTarget) {
                        blend = bufferOverride.blendMode() == null
                                ? Optional.empty()
                                : Optional.of(irisBlendFunction(bufferOverride.blendMode()));
                    }
                }
                builder.withColorTargetState(
                        index,
                        new ColorTargetState(
                                blend,
                                targetFormat(logicalTarget),
                                logicalTarget == 0
                                        ? sourceTarget.writeMask()
                                        : ColorTargetState.WRITE_ALL
                        )
                );
            }

            DepthStencilState depth = source.getDepthStencilState();
            if (depth != null) {
                builder.withDepthStencilState(depth);
            }

            // Sodium's own layout comes over verbatim — it declares texel
            // buffers (u_SectionTimeInfo, R32_SINT) with formats the patched
            // shader still consumes; only names the pack adds get appended.
            Set<String> declared = new java.util.HashSet<>();
            for (BindGroupLayout layout : source.getBindGroupLayouts()) {
                builder.withBindGroupLayout(layout);
                layout.getUniforms().forEach(uniform -> declared.add(uniform.name()));
                declared.addAll(layout.getSamplers());
            }
            BindGroupLayout.Builder extras = BindGroupLayout.builder();
            for (String blockName : program.uniformBlockNames()) {
                if (declared.add(blockName)) {
                    extras.withUniform(blockName, UniformType.UNIFORM_BUFFER);
                }
            }
            for (MetalIrisShaderCompiler.SamplerDecl sampler : program.samplers()) {
                if (!declared.add(sampler.name())) {
                    continue;
                }
                if (sampler.isStorageImage()) {
                    continue;
                }
                if (sampler.isTexelBuffer()) {
                    throw new IllegalStateException(
                            "Pack sampler '" + sampler.name() + "' (" + sampler.glslType()
                                    + ") is a texel buffer with no known GpuFormat; not supported in B2-1"
                    );
                }
                extras.withSampler(sampler.name());
            }
            builder.withBindGroupLayout(extras.build());
            builder.withVertexBinding(0, chunkFormat);
            return builder.build();
        }

        private RenderPipeline buildCoreSynthetic(
                final RenderPipeline source,
                final ShaderKey key,
                final MetalIrisShaderCompiler.GlslProgram program
        ) {
            String base = "iris/gen" + this.generation + "/core_" + key.getName()
                    + "_" + this.corePipelineSequence++;
            Identifier vertexId = Identifier.fromNamespaceAndPath("metallum", base + "_v");
            Identifier fragmentId = Identifier.fromNamespaceAndPath("metallum", base + "_f");
            this.generatedGlsl.put(vertexId, program.vertexGlsl());
            this.generatedGlsl.put(fragmentId, program.fragmentGlsl());

            IrisMetalShadowPipeline.ShadowProgram shadowProgram = null;
            IrisMetalShadowPipeline.ShadowRasterState shadowRaster = null;
            if (key.isShadow()) {
                IrisMetalShadowPipeline shadows = this.shadowPipeline;
                shadowProgram = shadows == null ? null : shadows.program(key).orElse(null);
                if (shadowProgram == null) {
                    throw new IllegalStateException("No Metal shadow program for " + key);
                }
                shadowRaster = IrisMetalShadowPipeline.adaptRasterState(source.getDepthStencilState());
            }
            RenderPipeline.Builder builder = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath("metallum", base))
                    .withVertexShader(vertexId)
                    .withFragmentShader(fragmentId)
                    .withCull(shadowRaster == null ? source.isCull() : shadowRaster.cull())
                    .withPolygonMode(source.getPolygonMode())
                    .withPrimitiveTopology(source.getPrimitiveTopology());

            ColorTargetState sourceTarget = source.getColorTargetState();
            if (sourceTarget == null) {
                throw new IllegalStateException("Core pipeline " + source.getLocation() + " has no color target");
            }
            ProgramSource sourceProgram = shadowProgram == null
                    ? this.coreResolver.resolve(key.getProgram()).orElseThrow()
                    : shadowProgram.source();
            Optional<BlendFunction> globalBlend = sourceTarget.blendFunction();
            BlendModeOverride globalOverride = sourceProgram.getDirectives().getBlendModeOverride()
                    .orElse(key.getProgram().getBlendModeOverride());
            if (globalOverride != null) {
                globalBlend = irisBlendFunction(globalOverride);
            }
            int[] drawBuffers = program.drawBuffers();
            for (int slot = 0; slot < drawBuffers.length; slot++) {
                int logicalTarget = drawBuffers[slot];
                Optional<BlendFunction> blend = globalBlend;
                for (var bufferOverride : sourceProgram.getDirectives().getBufferBlendOverrides()) {
                    if (bufferOverride.index() == logicalTarget) {
                        blend = bufferOverride.blendMode() == null
                                ? Optional.empty()
                                : Optional.of(irisBlendFunction(bufferOverride.blendMode()));
                    }
                }
                builder.withColorTargetState(
                        slot,
                        new ColorTargetState(
                                blend,
                                shadowProgram == null
                                        ? targetFormat(logicalTarget)
                                        : Objects.requireNonNull(this.shadowPipeline).targetFormat(logicalTarget),
                                sourceTarget.writeMask()
                        )
                );
            }

            DepthStencilState depth = shadowRaster == null
                    ? source.getDepthStencilState()
                    : shadowRaster.depthStencil();
            if (depth != null) {
                builder.withDepthStencilState(depth);
            }

            Set<String> declared = new java.util.HashSet<>();
            for (BindGroupLayout layout : source.getBindGroupLayouts()) {
                builder.withBindGroupLayout(layout);
                layout.getUniforms().forEach(uniform -> declared.add(uniform.name()));
                declared.addAll(layout.getSamplers());
            }
            BindGroupLayout.Builder extras = BindGroupLayout.builder();
            for (String blockName : program.uniformBlockNames()) {
                if (declared.add(blockName)) {
                    extras.withUniform(blockName, UniformType.UNIFORM_BUFFER);
                }
            }
            for (MetalIrisShaderCompiler.SamplerDecl sampler : program.samplers()) {
                if (!declared.add(sampler.name())) {
                    continue;
                }
                if (sampler.isStorageImage()) {
                    continue;
                }
                if (sampler.isTexelBuffer()) {
                    throw new IllegalStateException(
                            "Pack sampler '" + sampler.name() + "' (" + sampler.glslType()
                                    + ") is a texel buffer without a Metal format"
                    );
                }
                extras.withSampler(sampler.name());
            }
            builder.withBindGroupLayout(extras.build());

            VertexFormat[] sourceBindings = source.getVertexFormatBindings();
            for (int binding = 0; binding < sourceBindings.length; binding++) {
                if (sourceBindings[binding] != null) {
                    builder.withVertexBinding(binding, sourceBindings[binding]);
                }
            }
            VertexFormat physicalVertexFormat = shadowProgram == null
                    ? IrisMetalCoreGbufferPipelines.physicalVertexFormat(source, key)
                    : shadowProgram.vertexFormat();
            if (physicalVertexFormat != null) {
                builder.withVertexBinding(0, physicalVertexFormat);
            }
            return builder.build();
        }

        /**
         * Resolves a sampler the pack declared but sodium never bound.
         *
         * <p>Two names map to real content: the pack's {@code gtexture} is the
         * block atlas sodium binds as {@code u_BlockTex}, and {@code lightmap}
         * is its {@code u_LightTex}. Noise, render targets and completed shadow
         * targets resolve from generation-owned resources. Every unresolved
         * sampler fails closed; substituting a colour texture would silently
         * change the pack's resource semantics.</p>
         */
        private MetalRenderPass.@Nullable TextureViewAndSampler resolveTexture(
                final MetalDevice device,
                final MetalCompiledRenderPipeline pipeline,
                final String name,
                final Map<String, MetalRenderPass.TextureViewAndSampler> bound
        ) {
            TerrainKind terrainKind = this.compiledKinds.get(pipeline);
            ShaderKey coreKey = this.compiledCoreKeys.get(pipeline);
            if (this.closed || (terrainKind == null && coreKey == null)) {
                return null;
            }
            MetalIrisShaderCompiler.GlslProgram resourceProgram = coreKey == null
                    ? this.programs.get(terrainKind)
                    : this.corePrograms.get(coreKey);
            IrisMetalCustomTextures customs = this.customTextures;
            if (customs != null) {
                java.util.List<String> aliases = gbufferCustomTextureAliases(
                        coreKey, declaresSampler(resourceProgram, "watershadow"), name
                );
                if (!aliases.isEmpty()) {
                    MetalRenderPass.TextureViewAndSampler custom = customs.resolve(
                            TextureStage.GBUFFERS_AND_SHADOW, aliases.toArray(String[]::new)
                    );
                    if (custom != null) {
                        IrisMetalPassTrace.observeSampler(name, "iris:custom-GBUFFERS_AND_SHADOW");
                        return custom;
                    }
                }
            }
            if (coreKey != null && coreKey.patch != Patch.SODIUM && coreUsesWhitePixel(coreKey, name)) {
                IrisMetalWhitePixel white = this.whitePixel;
                if (white == null) {
                    return null;
                }
                IrisMetalPassTrace.observeSampler(name, "iris:white-pixel");
                return white.binding();
            }
            if (coreKey != null
                    && coreKey.patch != Patch.SODIUM
                    && coreUsesMojangExternalOverlay(coreKey, name)) {
                String overlayAlias = coreSamplerAlias(name);
                MetalRenderPass.TextureViewAndSampler drawLocal =
                        overlayAlias == null ? null : bound.get(overlayAlias);
                MetalRenderPass.TextureViewAndSampler overlay =
                        selectMojangExternalOverlayBinding(
                                device, coreKey, name, bound, this.mojangExternalOverlay
                        );
                if (overlay != null) {
                    IrisMetalPassTrace.observeSampler(
                            name,
                            overlay == drawLocal
                                    ? "mojang:" + overlayAlias
                                    : "mojang:external-unit1-overlay"
                    );
                }
                return overlay;
            }
            MetalRenderPass.TextureViewAndSampler alias;
            String aliasSource;
            if (coreKey != null && coreKey.patch != Patch.SODIUM) {
                aliasSource = coreSamplerAlias(name);
                alias = aliasSource == null ? null : bound.get(aliasSource);
            } else {
                alias = switch (name) {
                    case "gtexture", "tex", "texture" -> bound.get("u_BlockTex");
                    case "lightmap" -> bound.get("u_LightTex");
                    default -> null;
                };
                aliasSource = name.equals("lightmap") ? "u_LightTex" : "u_BlockTex";
            }
            if (alias != null) {
                IrisMetalPassTrace.observeSampler(name, coreKey == null
                        ? "sodium:" + aliasSource
                        : "mojang:" + aliasSource);
                return alias;
            }

            if ("normals".equals(name) || "specular".equals(name)) {
                MetalRenderPass.TextureViewAndSampler albedo = coreKey == null
                        ? bound.get("u_BlockTex")
                        : bound.get("Sampler0");
                MetalRenderPass.TextureViewAndSampler pbr = resolvePbrTexture(
                        device, albedo, "normals".equals(name) ? PBRType.NORMAL : PBRType.SPECULAR
                );
                if (pbr != null) {
                    IrisMetalPassTrace.observeSampler(name, "iris:pbr-" + name);
                }
                return pbr;
            }

            if ("noisetex".equals(name)) {
                IrisMetalNoiseTexture noise = this.noiseTexture;
                if (noise == null) {
                    return null;
                }
                IrisMetalPassTrace.observeSampler(name, "iris:" + noise.source());
                return noise.binding();
            }

            MetalRenderPass.TextureViewAndSampler targetBinding = resolveRenderTargetSampler(name);
            if (targetBinding != null) {
                IrisMetalPassTrace.observeSampler(
                        name,
                        IrisMetalPostChain.renderTargetIndex(name) >= 0
                                ? "iris:colortex-read"
                                : "iris:depthtex-view"
                );
                return targetBinding;
            }
            MetalIrisShaderCompiler.SamplerDecl sampler = declaredSampler(resourceProgram, name);
            if (sampler != null && IrisMetalShadowPipeline.isShadowSamplerName(name)) {
                IrisMetalShadowPipeline shadows = this.shadowPipeline;
                if (shadows == null) {
                    return null;
                }
                MetalRenderPass.TextureViewAndSampler shadow = coreKey != null && coreKey.isShadow()
                        ? shadows.resolveShadowSampler(
                                sampler, shadows.finalReadsFromAlt(), declaresSampler(resourceProgram, "watershadow")
                        )
                        : shadows.resolveWorldShadowSampler(
                                sampler, declaresSampler(resourceProgram, "watershadow")
                        );
                if (shadow != null) {
                    IrisMetalPassTrace.observeSampler(
                            name,
                            IrisMetalShadowPipeline.isComparisonSampler(sampler)
                                    ? "iris:shadow-depth-compare"
                                    : "iris:shadow-texture"
                    );
                }
                return shadow;
            }
            return null;
        }

        /** Resolves fixed Iris's per-albedo PBR holder for normals/specular samplers. */
        private static MetalRenderPass.@Nullable TextureViewAndSampler resolvePbrTexture(
                final MetalDevice device,
                final MetalRenderPass.@Nullable TextureViewAndSampler albedo,
                final PBRType type
        ) {
            if (albedo == null || !(albedo.textureView().texture() instanceof MetalGpuTexture base)
                    || base.isClosed() || !base.isOwnedBy(device)) {
                return null;
            }
            PBRTextureHolder holder = PBRTextureManager.INSTANCE.getOrLoadHolder(base.iris$getGlId());
            net.minecraft.client.renderer.texture.AbstractTexture texture = switch (type) {
                case NORMAL -> holder.normalTexture();
                case SPECULAR -> holder.specularTexture();
            };
            TextureFormat format = TextureFormatLoader.getFormat();
            if (format != null) {
                format.setupTextureParameters(type, texture);
            }
            return IrisMetalCustomTextures.checkedExternalBinding(
                    device, texture.getTextureView(), texture.getSampler(), "pbr/" + type.name().toLowerCase(Locale.ROOT)
            );
        }

        /** Routes Iris sampler names to the generation's real target views. */
        private MetalRenderPass.@Nullable TextureViewAndSampler resolveRenderTargetSampler(final String name) {
            IrisMetalRenderTargets targets = this.renderTargets;
            if (targets == null) {
                return null;
            }
            int colorTarget = gbufferRenderTargetIndex(name);
            if (colorTarget >= 0 && colorTarget < targets.colorTargets().targetCount()) {
                return new MetalRenderPass.TextureViewAndSampler(
                        targets.colorTargets().sampleReadView(colorTarget), targets.colorSampler(colorTarget)
                );
            }
            if (name.startsWith("depthtex")) {
                int index = parseTargetIndex(name, "depthtex");
                MetalGpuTextureView view = switch (index) {
                    case 0 -> null;
                    case 1 -> targets.noTranslucentsDepthView();
                    case 2 -> targets.noHandDepthView();
                    default -> null;
                };
                Minecraft minecraft = Minecraft.getInstance();
                if (index == 0 && minecraft != null && minecraft.gameRenderer != null
                        && minecraft.gameRenderer.mainRenderTarget().getDepthTextureView()
                        instanceof MetalGpuTextureView sceneDepth) {
                    view = sceneDepth;
                }
                if (view != null) {
                    return new MetalRenderPass.TextureViewAndSampler(view, targets.depthSampler());
                }
            }
            return null;
        }

        private static int parseTargetIndex(final String name, final String prefix) {
            try {
                return Integer.parseInt(name.substring(prefix.length()));
            } catch (RuntimeException ignored) {
                return -1;
            }
        }

        /** Mirrors IrisSamplers.addRenderTargetSamplers(..., fullscreen=false). */
        static int gbufferRenderTargetIndex(final String name) {
            int target = IrisMetalPostChain.renderTargetIndex(name);
            return target >= 4 ? target : -1;
        }

        private static MetalIrisShaderCompiler.@Nullable SamplerDecl declaredSampler(
                final MetalIrisShaderCompiler.@Nullable GlslProgram program,
                final String name
        ) {
            if (program == null) {
                return null;
            }
            for (MetalIrisShaderCompiler.SamplerDecl sampler : program.samplers()) {
                if (sampler.name().equals(name)) {
                    return sampler;
                }
            }
            return null;
        }

        private static boolean declaresSampler(
                final MetalIrisShaderCompiler.@Nullable GlslProgram program,
                final String name
        ) {
            return declaredSampler(program, name) != null;
        }

        /**
         * Creates everything the draw path will need. Must run outside any
         * encoder; {@link #updateFrame()} calls it from
         * {@code beginLevelRendering}.
         */
        private void prewarm(final @Nullable MetalDevice device) {
            if (this.closed || device == null) {
                return;
            }
            this.mojangExternalOverlay = prewarmMojangExternalOverlay(device);
            ensureRenderTargets(device);
            if (this.whitePixel == null) {
                this.whitePixel = new IrisMetalWhitePixel(device);
            }
            if (this.noiseTexture == null) {
                this.noiseTexture = new IrisMetalNoiseTexture(
                        device,
                        this.packDirectives.getNoiseTextureResolution(),
                        this.pack.getCustomNoiseTexture()
                );
            }
            if (this.customTextures == null) {
                this.customTextures = new IrisMetalCustomTextures(
                        device, this.pack
                );
                this.customTextures.prewarmAll();
            }
            IrisMetalRenderTargets targets = this.renderTargets;
            if (targets != null) {
                if (this.computeResources == null) {
                    this.computeResources = new IrisMetalComputeResources(
                            device, this.pack, targets.width(), targets.height()
                    );
                } else {
                    this.computeResources.resize(targets.width(), targets.height());
                }
            }
            if (this.productionLifecycle && this.shadowPipeline == null) {
                this.shadowPipeline = new IrisMetalShadowPipeline(device, this.programSet, this.generation);
                this.shadowPipeline.registerUniforms(this.uniformValues);
                this.shadowPipeline.prepare(device, device.activeShaderSource());
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (!this.postPrepared && targets != null && minecraft != null && minecraft.gameRenderer != null) {
                GpuFormat finalFormat = minecraft.gameRenderer.mainRenderTarget().getColorTexture().getFormat();
                this.postChain.prepare(
                        device, targets, finalFormat, device.activeShaderSource(), this.postResources
                );
                this.postPrepared = true;
            }
            if (this.postPrepared
                    && this.centerDepthSampler == null
                    && this.postChain.requiresSampler(IrisMetalCenterDepthSampler.SAMPLER_NAME)) {
                this.centerDepthSampler = new IrisMetalCenterDepthSampler(
                        device,
                        this.generation,
                        this.packDirectives.getCenterDepthHalfLife(),
                        device.activeShaderSource()
                );
            }
            this.uniformValues.prewarm(device);
        }

        /**
         * Snapshots Mojang's externally managed overlay binding outside a live
         * encoder. The snapshot is refreshed every frame, so resource reloads
         * and device replacement cannot leave a generation holding a stale
         * view or sampler.
         */
        private MetalRenderPass.@Nullable TextureViewAndSampler prewarmMojangExternalOverlay(
                final MetalDevice device
        ) {
            if (!this.productionLifecycle) {
                return null;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.gameRenderer == null) {
                return null;
            }
            GpuTextureView view = minecraft.gameRenderer.overlayTexture().getTextureView();
            GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            return checkedMojangExternalOverlayBinding(device, view, sampler);
        }

        /** Creates or resizes the generation-owned targets outside a live encoder. */
        private void ensureRenderTargets(final MetalDevice device) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.gameRenderer == null) {
                return;
            }
            com.mojang.blaze3d.pipeline.RenderTarget mainTarget =
                    minecraft.gameRenderer.mainRenderTarget();
            int width = mainTarget.width;
            int height = mainTarget.height;
            if (width <= 0 || height <= 0) {
                return;
            }
            if (this.renderTargets == null) {
                this.renderTargets = new IrisMetalRenderTargets(
                        device,
                        this.targetFormats,
                        width,
                        height,
                        this.packDirectives.getRenderTargetDirectives().getRenderTargetSettings(),
                        this.postChain.mipmappedTargets(),
                        this.postChain.storageImageTargets()
                );
                this.setupRequiredThisFrame = true;
                IrisMetalPassTrace.observeTargets(
                        "allocated", width, height, this.targetFormats.length, formatNames(this.targetFormats)
                );
                Metallum.LOGGER.info(
                        "[metallum-iris] render targets allocated for generation {} at {}x{} ({} logical targets)",
                        this.generation, width, height, this.targetFormats.length
                );
            } else if (this.renderTargets.width() != width || this.renderTargets.height() != height) {
                this.renderTargets.resize(width, height);
                this.setupRequiredThisFrame = true;
                IrisMetalPassTrace.observeTargets(
                        "resized", width, height, this.targetFormats.length, formatNames(this.targetFormats)
                );
                Metallum.LOGGER.info(
                        "[metallum-iris] render targets resized for generation {} to {}x{}",
                        this.generation, width, height
                );
            }
        }

        private void beginFrame() {
            if (!this.productionLifecycle) {
                return;
            }
            IrisMetalRenderTargets targets = this.renderTargets;
            MetalDevice device = MetalDevice.current();
            if (targets == null || device == null || !this.postPrepared) {
                throw new IllegalStateException("Iris Metal frame began before generation resources were prepared");
            }
            Vector3d fog = CapturedRenderingState.INSTANCE.getFogColor();
            boolean fullClear = targets.clearForFrame(
                    device.commandEncoder(),
                    new Vector4f((float) fog.x, (float) fog.y, (float) fog.z, 1.0F)
            );
            IrisMetalComputeResources compute = this.computeResources;
            if (compute != null) {
                compute.clearForFrame(device.commandEncoder());
            }
            targets.colorTargets().restore(this.postChain.stageInput(
                    fullClear ? IrisMetalPostChain.Stage.SETUP : IrisMetalPostChain.Stage.BEGIN
            ));
            IrisMetalShadowPipeline shadows = this.shadowPipeline;
            if (shadows != null) {
                shadows.beginFrame(device, this.postResources);
                IrisMetalPassTrace.observePhase("shadow", "began");
            }
            IrisMetalPassTrace.observePhase("targets-clear", fullClear ? "full" : "directed");
        }

        private void captureNoTranslucentsDepth() {
            Minecraft minecraft = Minecraft.getInstance();
            if (this.renderTargets == null || minecraft == null || minecraft.gameRenderer == null) {
                return;
            }
            com.mojang.blaze3d.textures.GpuTexture depth =
                    minecraft.gameRenderer.mainRenderTarget().getDepthTexture();
            MetalDevice device = MetalDevice.current();
            if (depth == null || device == null) {
                return;
            }
            this.renderTargets.captureNoTranslucentsDepth(device.commandEncoder(), depth);
            IrisMetalPassTrace.observeDepth("depthtex1");
        }

        private void captureNoHandDepth() {
            Minecraft minecraft = Minecraft.getInstance();
            if (this.renderTargets == null || minecraft == null || minecraft.gameRenderer == null) {
                return;
            }
            com.mojang.blaze3d.textures.GpuTexture depth =
                    minecraft.gameRenderer.mainRenderTarget().getDepthTexture();
            MetalDevice device = MetalDevice.current();
            if (depth == null || device == null) {
                return;
            }
            this.renderTargets.captureNoHandDepth(device.commandEncoder(), depth);
            IrisMetalPassTrace.observeDepth("depthtex2");
        }

        private void sampleCenterDepth() {
            IrisMetalCenterDepthSampler sampler = this.centerDepthSampler;
            if (sampler == null) {
                if (this.postChain.requiresSampler(IrisMetalCenterDepthSampler.SAMPLER_NAME)) {
                    throw new IllegalStateException("Iris center-depth sampler was required but not prepared");
                }
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.gameRenderer == null) {
                throw new IllegalStateException("Iris center-depth sampler has no live Minecraft depth target");
            }
            GpuTextureView depth = minecraft.gameRenderer.mainRenderTarget().getDepthTextureView();
            if (depth == null) {
                throw new IllegalStateException("Iris center-depth sampler has no live depth texture view");
            }
            sampler.sample(depth, net.irisshaders.iris.uniforms.SystemTimeUniforms.TIMER.getLastFrameTime());
        }

        private void executePostStage(final IrisMetalPostChain.Stage stage) {
            MetalDevice device = MetalDevice.current();
            IrisMetalRenderTargets targets = this.renderTargets;
            if (device == null || targets == null || !this.postPrepared) {
                throw new IllegalStateException("Iris Metal post stage ran before generation resources were prepared");
            }
            if (stage == IrisMetalPostChain.Stage.BEGIN && this.setupRequiredThisFrame) {
                IrisMetalPostChain.ExecutionReceipt setup = this.postChain.executeStage(
                        IrisMetalPostChain.Stage.SETUP, device, targets, this.postResources
                );
                this.setupRequiredThisFrame = false;
                IrisMetalPassTrace.observePhase(
                        "setup", setup.passes().isEmpty() ? "empty" : "executed"
                );
            }
            IrisMetalPostChain.ExecutionReceipt receipt = this.postChain.executeStage(
                    stage, device, targets, this.postResources
            );
            IrisMetalPassTrace.observePhase(
                    stage.name().toLowerCase(Locale.ROOT),
                    receipt.passes().isEmpty() ? "empty" : "executed"
            );
        }

        private void executeFinal() {
            MetalDevice device = MetalDevice.current();
            IrisMetalRenderTargets targets = this.renderTargets;
            Minecraft minecraft = Minecraft.getInstance();
            if (device == null || targets == null || minecraft == null || minecraft.gameRenderer == null
                    || !this.postPrepared) {
                throw new IllegalStateException("Iris Metal final stage ran before generation resources were prepared");
            }
            IrisMetalPostChain.FinalReceipt receipt = this.postChain.executeFinal(
                    device,
                    targets,
                    minecraft.gameRenderer.mainRenderTarget().getColorTextureView(),
                    this.postResources
            );
            IrisMetalPassTrace.observePhase(
                    "final", receipt.mainTargetResolved() ? "executed" : "failed"
            );
        }

        private void executeColorSpace(final ColorSpace colorSpace) {
            MetalDevice device = MetalDevice.current();
            IrisMetalRenderTargets targets = this.renderTargets;
            Minecraft minecraft = Minecraft.getInstance();
            if (device == null || targets == null || minecraft == null || minecraft.gameRenderer == null
                    || !this.postPrepared) {
                throw new IllegalStateException(
                        "Iris Metal color-space stage ran before generation resources were prepared"
                );
            }
            boolean executed = this.postChain.executeColorSpace(
                    device,
                    targets,
                    minecraft.gameRenderer.mainRenderTarget().getColorTextureView(),
                    colorSpace
            );
            IrisMetalPassTrace.observePhase("color-space", executed ? colorSpace.name() : "bypassed");
        }

        private final IrisMetalPostChain.ResourceProvider postResources =
                new IrisMetalPostChain.ResourceProvider() {
                    @Override
                    public @Nullable GpuBufferSlice uniform(
                            final IrisMetalPostChain.PassInfo pass,
                            final String blockName
                    ) {
                        return MetalIrisShaderCompiler.UNIFORM_BLOCK_NAME.equals(blockName)
                                ? postChain.uniformSlice(uniformValues, pass)
                                : null;
                    }

                    @Override
                    public @Nullable GpuBufferSlice uniform(
                            final IrisMetalPostChain.PassInfo pass,
                            final String blockName,
                            final Object token
                    ) {
                        return MetalIrisShaderCompiler.UNIFORM_BLOCK_NAME.equals(blockName)
                                ? uniformValues.slice(token)
                                : null;
                    }

                    @Override
                    public @Nullable GpuBufferSlice uniform(
                            final IrisMetalPostChain.PassInfo pass,
                            final String blockName,
                            final Object token,
                            final IrisMetalUniformValues.DrawUniformContext context
                    ) {
                        if (!MetalIrisShaderCompiler.UNIFORM_BLOCK_NAME.equals(blockName)) {
                            return null;
                        }
                        MetalDevice device = MetalDevice.current();
                        if (device == null) {
                            throw new IllegalStateException(
                                    "Iris pass " + pass.name() + " has no current Metal device for dynamic uniforms"
                            );
                        }
                        return materializeUniform(device, token, null, null, context);
                    }

                    @Override
                    public IrisMetalPostChain.@Nullable TextureBinding texture(
                            final IrisMetalPostChain.PassInfo pass,
                            final String samplerName
                    ) {
                        if (IrisMetalCenterDepthSampler.SAMPLER_NAME.equals(samplerName)) {
                            IrisMetalCenterDepthSampler centerDepth = centerDepthSampler;
                            if (centerDepth != null) {
                                MetalRenderPass.TextureViewAndSampler binding = centerDepth.binding();
                                IrisMetalPassTrace.observeSampler(samplerName, "iris:center-depth-smooth");
                                return new IrisMetalPostChain.TextureBinding(
                                        binding.textureView(), binding.sampler()
                                );
                            }
                            return null;
                        }
                        TextureStage textureStage = pass.stage().textureStage;
                        IrisMetalCustomTextures customs = customTextures;
                        if (customs != null && pass.allowsCustomTextureOverride(samplerName)) {
                            MetalRenderPass.TextureViewAndSampler custom = customs.resolve(textureStage, samplerName);
                            if (custom != null) {
                                IrisMetalPassTrace.observeSampler(samplerName, "iris:custom-" + textureStage.name());
                                return new IrisMetalPostChain.TextureBinding(custom.textureView(), custom.sampler());
                            }
                        }
                        if ("noisetex".equals(samplerName)) {
                            IrisMetalNoiseTexture noise = noiseTexture;
                            if (noise != null) {
                                MetalRenderPass.TextureViewAndSampler binding = noise.binding();
                                IrisMetalPassTrace.observeSampler(samplerName, "iris:" + noise.source());
                                return new IrisMetalPostChain.TextureBinding(binding.textureView(), binding.sampler());
                            }
                        }
                        IrisMetalComputeResources compute = computeResources;
                        if (compute != null) {
                            IrisMetalPostChain.TextureBinding image = compute.sampledImage(samplerName);
                            if (image != null) {
                                IrisMetalPassTrace.observeSampler(samplerName, "iris:custom-image");
                                return image;
                            }
                        }
                        int colorTarget = IrisMetalPostChain.renderTargetIndex(samplerName);
                        IrisMetalRenderTargets worldTargets = renderTargets;
                        if (colorTarget >= 0 && worldTargets != null) {
                            if (colorTarget >= worldTargets.colorTargets().targetCount()) {
                                throw new IllegalStateException(
                                        "Iris sampler '" + samplerName + "' exceeds generation target count"
                                );
                            }
                            return new IrisMetalPostChain.TextureBinding(
                                    worldTargets.colorTargets().sampleReadView(colorTarget),
                                    worldTargets.colorSampler(colorTarget)
                            );
                        }
                        if ("depthtex0".equals(samplerName)) {
                            Minecraft minecraft = Minecraft.getInstance();
                            if (minecraft != null && minecraft.gameRenderer != null) {
                                GpuTextureView view = minecraft.gameRenderer.mainRenderTarget().getDepthTextureView();
                                IrisMetalRenderTargets targets = renderTargets;
                                if (view != null && targets != null) {
                                    IrisMetalPassTrace.observeSampler(samplerName, "minecraft:live-depth");
                                    return new IrisMetalPostChain.TextureBinding(view, targets.depthSampler());
                                }
                            }
                        }
                        return null;
                    }

                    @Override
                    public IrisMetalPostChain.@Nullable TextureBinding texture(
                            final IrisMetalPostChain.PassInfo pass,
                            final MetalIrisShaderCompiler.SamplerDecl sampler
                    ) {
                        IrisMetalPostChain.TextureBinding external = texture(pass, sampler.name());
                        if (external != null || !IrisMetalShadowPipeline.isShadowSamplerName(sampler.name())) {
                            return external;
                        }
                        IrisMetalShadowPipeline shadows = shadowPipeline;
                        if (shadows == null) {
                            return null;
                        }
                        MetalRenderPass.TextureViewAndSampler binding =
                                pass.stage() == IrisMetalPostChain.Stage.SHADOW_COMPOSITE
                                        ? shadows.resolveShadowSampler(
                                                sampler,
                                                pass.readsFromAlt(),
                                                pass.declaresSampler("watershadow")
                                        )
                                        : shadows.resolveWorldShadowSampler(
                                                sampler, pass.declaresSampler("watershadow")
                                        );
                        if (binding == null) {
                            return null;
                        }
                        IrisMetalPassTrace.observeSampler(
                                sampler.name(),
                                IrisMetalShadowPipeline.isComparisonSampler(sampler)
                                        ? "iris:shadow-depth-compare"
                                        : "iris:shadow-texture"
                        );
                        return new IrisMetalPostChain.TextureBinding(
                                binding.textureView(), binding.sampler()
                        );
                    }

                    @Override
                    public @Nullable GpuTextureView storageImage(
                            final IrisMetalPostChain.PassInfo pass,
                            final String imageName
                    ) {
                        int colorTarget = IrisMetalPostChain.colorImageIndex(imageName);
                        IrisMetalRenderTargets targets = renderTargets;
                        if (colorTarget >= 0 && targets != null) {
                            if (colorTarget >= targets.colorTargets().targetCount()) {
                                throw new IllegalStateException(
                                        "Iris storage image '" + imageName + "' exceeds generation target count"
                                );
                            }
                            return targets.colorTargets().sampleReadView(colorTarget);
                        }
                        IrisMetalComputeResources compute = computeResources;
                        return compute == null ? null : compute.storageImage(imageName);
                    }

                    @Override
                    public @Nullable GpuBufferSlice storageBuffer(final int binding) {
                        IrisMetalComputeResources compute = computeResources;
                        return compute == null ? null : compute.storageBuffer(binding);
                    }
                };

        private @Nullable GpuBufferSlice resolveUniform(
                final MetalDevice device,
                final MetalCompiledRenderPipeline pipeline,
                final String name,
                final @Nullable MetalRenderPass pass,
                final @Nullable Map<String, GpuBufferSlice> bound
        ) {
            if (this.closed) {
                return null;
            }
            int storageBinding = MetalCrossShaderCompiler.storageBufferLogicalBinding(name);
            if (storageBinding >= 0) {
                IrisMetalComputeResources compute = this.computeResources;
                return compute == null ? null : compute.storageBuffer(storageBinding);
            }
            if (!MetalIrisShaderCompiler.UNIFORM_BLOCK_NAME.equals(name)) {
                return null;
            }
            TerrainKind kind = this.compiledKinds.get(pipeline);
            ShaderKey coreKey = this.compiledCoreKeys.get(pipeline);
            Object token = kind != null ? kind : coreKey;
            if (token == null) {
                return null;
            }
            if (pass == null || bound == null) {
                return this.uniformValues.slice(token);
            }
            if (this.uniformValues.drawBlockSize(token) == 0) {
                return this.uniformValues.slice(token);
            }

            ByteBuffer dynamicTransforms = this.uniformValues.requiresDynamicTransforms(token)
                    ? readableUniformData(bound.get("DynamicTransforms"), "DynamicTransforms")
                    : null;
            ByteBuffer projection = this.uniformValues.requiresProjection(token)
                    ? readableUniformData(bound.get("Projection"), "Projection")
                    : null;
            return materializeUniform(
                    device,
                    token,
                    dynamicTransforms,
                    projection,
                    worldDrawUniformContext(device, pipeline, pass)
            );
        }

        private @Nullable GpuBufferSlice materializeUniform(
                final MetalDevice device,
                final Object token,
                final @Nullable ByteBuffer dynamicTransforms,
                final @Nullable ByteBuffer projection,
                final IrisMetalUniformValues.DrawUniformContext context
        ) {
            GpuBufferSlice base = this.uniformValues.slice(token);
            int blockSize = this.uniformValues.drawBlockSize(token);
            if (blockSize == 0) {
                return base;
            }
            try (GpuBufferSlice.MappedView mapped = device.commandEncoder().transientMemory()
                    .allocateGpuMapped(blockSize, 16L, GpuBuffer.USAGE_UNIFORM)) {
                this.uniformValues.materializeDraw(
                        token, mapped.data(), dynamicTransforms, projection, context
                );
                return mapped.slice();
            }
        }

        private IrisMetalUniformValues.DrawUniformContext worldDrawUniformContext(
                final MetalDevice device,
                final MetalCompiledRenderPipeline pipeline,
                final MetalRenderPass pass
        ) {
            ShaderKey coreKey = this.compiledCoreKeys.get(pipeline);
            MetalRenderPass.TextureViewAndSampler albedo = pass.boundTexture(
                    coreKey == null ? "u_BlockTex" : "Sampler0"
            );
            int atlasWidth = 0;
            int atlasHeight = 0;
            if (albedo != null && albedo.textureView().texture() instanceof MetalGpuTexture texture
                    && TextureTracker.INSTANCE.getTexture(texture.iris$getGlId()) instanceof TextureAtlas) {
                atlasWidth = albedo.textureView().getWidth(0);
                atlasHeight = albedo.textureView().getHeight(0);
            }
            MetalRenderPass.TextureViewAndSampler gtexture = resolveTexture(
                    device, pipeline, "gtexture", pass.boundTextures()
            );
            return new IrisMetalUniformValues.DrawUniformContext(
                    gtexture == null ? null : gtexture.textureView(),
                    atlasWidth,
                    atlasHeight,
                    this.compiledGlobalBlends.getOrDefault(pipeline, Optional.empty())
            );
        }

        private static Optional<BlendFunction> worldGlobalBlend(
                final RenderPipeline source,
                final @Nullable ProgramSource program,
                final @Nullable BlendModeOverride fallback
        ) {
            ColorTargetState sourceTarget = source.getColorTargetState();
            Optional<BlendFunction> blend = sourceTarget == null
                    ? Optional.empty()
                    : sourceTarget.blendFunction();
            BlendModeOverride override = program == null
                    ? fallback
                    : program.getDirectives().getBlendModeOverride().orElse(fallback);
            return override == null ? blend : irisBlendFunction(override);
        }

        private @Nullable GpuTextureView resolveStorageImage(
                final MetalDevice device,
                final MetalCompiledRenderPipeline pipeline,
                final String name
        ) {
            if (this.closed
                    || (!this.compiledKinds.containsKey(pipeline)
                    && !this.compiledCoreKeys.containsKey(pipeline))) {
                return null;
            }
            int colorTarget = IrisMetalPostChain.colorImageIndex(name);
            if (colorTarget >= 0) {
                IrisMetalRenderTargets targets = this.renderTargets;
                return targets == null || colorTarget >= targets.colorTargets().targetCount()
                        ? null
                        : targets.colorTargets().sampleReadView(colorTarget);
            }
            if (name.startsWith("shadowcolorimg")) {
                IrisMetalShadowPipeline shadows = this.shadowPipeline;
                return shadows == null ? null : shadows.resolveStorageImage(name);
            }
            IrisMetalComputeResources compute = this.computeResources;
            return compute == null ? null : compute.storageImage(name);
        }

        private static @Nullable ByteBuffer readableUniformData(
                final @Nullable GpuBufferSlice slice,
                final String blockName
        ) {
            if (slice == null) {
                return null;
            }
            if (!(slice.buffer() instanceof MetalGpuBuffer buffer)) {
                throw new IllegalStateException(
                        "Iris core draw " + blockName + " is not backed by a Metal buffer"
                );
            }
            try {
                return buffer.sliceStorage(slice.offset(), slice.length());
            } catch (IllegalStateException failure) {
                throw new IllegalStateException(
                        "Iris core draw " + blockName + " uniform data is not CPU-readable",
                        failure
                );
            }
        }

        /** Offline-gate hook: the bytes last written for a kind's uniform block. */
        java.nio.@Nullable ByteBuffer uniformStaging(final TerrainKind kind) {
            return this.uniformValues.lastUpload(kind);
        }

        @Nullable ShaderKey compiledCoreKey(final MetalCompiledRenderPipeline pipeline) {
            return this.compiledCoreKeys.get(pipeline);
        }

        private void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            // The overrides are cached against sodium's own RenderPipeline
            // objects, which outlive this instance; without dropping the cache a
            // pack reload (or turning shaders off) would keep drawing terrain
            // with the previous pack's PSOs.
            // Must NOT be conditional on having compiled something: an instance
            // whose overrides all failed still has to invalidate whatever native
            // PSOs were built in its place (sodium's program map is a private
            // static that never turns over, so a stale PSO would survive for the
            // life of the JVM), and clearPipelineCache is also what advances
            // pipelineCacheGeneration — the guard that stops an in-flight
            // background compile from landing in the next generation's cache.
            MetalDevice device = this.device != null ? this.device : MetalDevice.current();
            if (device != null) {
                device.clearPipelineCache();
            }
            this.device = null;
            this.uniformValues.close();
            this.postChain.close();
            if (this.whitePixel != null) {
                this.whitePixel.close();
                this.whitePixel = null;
            }
            if (this.noiseTexture != null) {
                this.noiseTexture.close();
                this.noiseTexture = null;
            }
            if (this.customTextures != null) {
                this.customTextures.close();
                this.customTextures = null;
            }
            if (this.computeResources != null) {
                this.computeResources.close();
                this.computeResources = null;
            }
            if (this.centerDepthSampler != null) {
                this.centerDepthSampler.close();
                this.centerDepthSampler = null;
            }
            if (this.shadowPipeline != null) {
                this.shadowPipeline.close();
                this.shadowPipeline = null;
            }
            this.mojangExternalOverlay = null;
            this.setupRequiredThisFrame = false;
            if (this.renderTargets != null) {
                this.renderTargets.close();
                this.renderTargets = null;
            }
            this.compiledKinds.clear();
            this.compiledCoreKeys.clear();
            this.compiledGlobalBlends.clear();
            this.coreSyntheticKeys.clear();
            this.coreSyntheticSources.clear();
            this.coreSyntheticPipelines.clear();
            this.corePrograms.clear();
            this.reportedCoreFailures.clear();
            this.generatedGlsl.clear();
        }
    }

    private static Field irisBlendModeField() {
        try {
            Field field = BlendModeOverride.class.getDeclaredField("blendMode");
            if (!field.trySetAccessible()) {
                throw new IllegalStateException("Iris BlendModeOverride.blendMode is not accessible");
            }
            return field;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Iris blend ABI changed: expected BlendModeOverride.blendMode from Iris 1.11.2",
                    e
            );
        }
    }

    static @Nullable String coreSamplerAlias(final String name) {
        return switch (name) {
            // Iris's level sampler ABI reserves texture unit 0 for the draw's
            // albedo texture. gcolor is normally renamed to gtexture by the
            // common transformer; an active colortex0 declaration that survives
            // a gbuffer transform still has GLSL's default sampler value 0. It
            // must not read the generation-owned render target while that target
            // is also being written by this draw. The no-UV case is intercepted
            // by coreUsesWhitePixel before this alias is consulted.
            case "gtexture", "tex", "texture", "u_MainSampler", "gcolor", "colortex0" ->
                    "Sampler0";
            case "iris_overlay", "overlay" -> "Sampler1";
            case "lightmap" -> "Sampler2";
            default -> null;
        };
    }

    /**
     * Iris 1.11.2 registers overlay as externally managed texture unit 1 when
     * the selected vanilla ShaderKey carries UV1. Some Mojang RenderTypes that
     * Iris maps to that key do not bind Sampler1 for the individual draw, so
     * Metal must preserve the external-unit contract rather than treating the
     * sampler as optional.
     */
    static boolean coreUsesMojangExternalOverlay(final ShaderKey key, final String name) {
        if (key.patch != Patch.VANILLA
                || !("iris_overlay".equals(name) || "overlay".equals(name))) {
            return false;
        }
        return MetalIrisShaderCompiler.vanillaPatchSemantics(key, false)
                .attributes()
                .hasOverlay();
    }

    /**
     * Selects fixed Iris's external texture-unit-1 value without making it
     * optional. A draw-local {@code Sampler1} is the most recent value of that
     * unit and wins; otherwise the validated Mojang-owned overlay snapshot
     * supplies the external state.
     */
    static MetalRenderPass.@Nullable TextureViewAndSampler selectMojangExternalOverlayBinding(
            final MetalDevice device,
            final ShaderKey key,
            final String name,
            final Map<String, MetalRenderPass.TextureViewAndSampler> bound,
            final MetalRenderPass.@Nullable TextureViewAndSampler external
    ) {
        if (!coreUsesMojangExternalOverlay(key, name)) {
            return null;
        }
        String alias = coreSamplerAlias(name);
        MetalRenderPass.TextureViewAndSampler drawLocal =
                alias == null ? null : bound.get(alias);
        return drawLocal != null
                ? drawLocal
                : checkedMojangExternalOverlayBinding(device, external);
    }

    static MetalRenderPass.@Nullable TextureViewAndSampler checkedMojangExternalOverlayBinding(
            final MetalDevice device,
            final MetalRenderPass.@Nullable TextureViewAndSampler binding
    ) {
        return binding == null
                ? null
                : checkedMojangExternalOverlayBinding(
                        device, binding.textureView(), binding.sampler()
                );
    }

    /**
     * Validates the real Mojang overlay binding without manufacturing or
     * owning either resource. A wrong backend, device, lifetime or sampler
     * contract remains a required-input failure.
     */
    static MetalRenderPass.@Nullable TextureViewAndSampler checkedMojangExternalOverlayBinding(
            final MetalDevice device,
            final @Nullable GpuTextureView view,
            final @Nullable GpuSampler sampler
    ) {
        if (!(view instanceof MetalGpuTextureView metalView)
                || !(metalView.texture() instanceof MetalGpuTexture texture)
                || !(sampler instanceof MetalGpuSampler metalSampler)
                || metalView.isClosed()
                || texture.isClosed()
                || metalSampler.isClosed()
                || !texture.isOwnedBy(device)
                || !metalSampler.isOwnedBy(device)
                || (texture.usage() & GpuTexture.USAGE_TEXTURE_BINDING) == 0
                || metalSampler.getAddressModeU() != AddressMode.CLAMP_TO_EDGE
                || metalSampler.getAddressModeV() != AddressMode.CLAMP_TO_EDGE
                || metalSampler.getMinFilter() != FilterMode.LINEAR
                || metalSampler.getMagFilter() != FilterMode.LINEAR) {
            return null;
        }
        return new MetalRenderPass.TextureViewAndSampler(metalView, metalSampler);
    }

    /**
     * Alias groups intercepted by Iris's GBUFFERS_AND_SHADOW custom-texture holder.
     * Core level samplers deliberately stay outside that interceptor in Iris 1.11.2;
     * Sodium terrain level samplers are intercepted.
     */
    static java.util.List<String> gbufferCustomTextureAliases(
            final @Nullable ShaderKey coreKey,
            final boolean waterShadowDeclared,
            final String name
    ) {
        int target = IrisMetalPostChain.renderTargetIndex(name);
        if (target >= 4) {
            String modern = "colortex" + target;
            if (target < PackRenderTargetDirectives.LEGACY_RENDER_TARGETS.size()) {
                return java.util.List.of(
                        modern,
                        PackRenderTargetDirectives.LEGACY_RENDER_TARGETS.get(target)
                );
            }
            return java.util.List.of(modern);
        }

        java.util.List<String> standard = switch (name) {
            case "dhDepthTex", "dhDepthTex0" -> java.util.List.of("dhDepthTex", "dhDepthTex0");
            case "dhDepthTex1", "depthtex0", "depthtex1", "depthtex2", "noisetex",
                    "shadowtex0HW", "shadowtex1HW", "shadowcolor" -> java.util.List.of(name);
            case "shadowtex0", "watershadow" -> waterShadowDeclared
                    ? java.util.List.of("shadowtex0", "watershadow")
                    : java.util.List.of("shadowtex0", "shadow");
            case "shadowtex1" -> waterShadowDeclared
                    ? java.util.List.of("shadowtex1", "shadow")
                    : java.util.List.of("shadowtex1");
            case "shadow" -> waterShadowDeclared
                    ? java.util.List.of("shadowtex1", "shadow")
                    : java.util.List.of("shadowtex0", "shadow");
            default -> name.startsWith("shadowcolor") && !name.startsWith("shadowcolorimg")
                    ? java.util.List.of(name)
                    : java.util.List.of();
        };
        if (!standard.isEmpty()) {
            return standard;
        }

        boolean sodium = coreKey == null || coreKey.patch == Patch.SODIUM;
        if (!sodium) {
            return java.util.List.of();
        }
        return switch (name) {
            case "tex", "texture", "gtexture", "u_MainSampler" ->
                    java.util.List.of("tex", "texture", "gtexture", "u_MainSampler");
            case "lightmap", "iris_overlay", "normals", "specular" -> java.util.List.of(name);
            default -> java.util.List.of();
        };
    }

    static boolean coreUsesWhitePixel(final ShaderKey key, final String name) {
        MetalIrisShaderCompiler.VanillaPatchSemantics semantics =
                MetalIrisShaderCompiler.vanillaPatchSemantics(key, false);
        return switch (name) {
            case "gtexture", "tex", "texture", "u_MainSampler", "gcolor", "colortex0" ->
                    !semantics.attributes().hasTex();
            case "lightmap" -> !semantics.attributes().hasLight();
            case "iris_overlay", "overlay" -> !semantics.attributes().hasOverlay();
            default -> false;
        };
    }

    static Optional<BlendFunction> irisBlendFunction(final BlendModeOverride override) {
        try {
            BlendMode blendMode = (BlendMode) IRIS_BLEND_MODE.get(override);
            return blendMode == null ? Optional.empty() : Optional.of(irisBlendFunction(blendMode));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not read Iris blend override", e);
        }
    }

    static BlendFunction irisBlendFunction(final BlendMode blendMode) {
        return new BlendFunction(
                irisBlendFactor(blendMode.srcRgb()),
                irisBlendFactor(blendMode.dstRgb()),
                irisBlendFactor(blendMode.srcAlpha()),
                irisBlendFactor(blendMode.dstAlpha())
        );
    }

    private static BlendFactor irisBlendFactor(final int glId) {
        for (BlendModeFunction function : BlendModeFunction.values()) {
            if (function.getGlId() == glId) {
                return BlendFactor.valueOf(function.name());
            }
        }
        throw new IllegalArgumentException("Unsupported Iris blend factor GL id " + glId);
    }

    private static @Nullable ProgramSource resolveSource(final ProgramSet programSet, final ProgramId start) {
        ProgramId current = start;
        while (current != null) {
            Optional<ProgramSource> source = programSet.get(current);
            if (source.isPresent()) {
                return source.get();
            }
            current = current.getFallback().orElse(null);
        }
        return null;
    }

    /** The Blaze3D vertex format of the active sodium chunk vertex type, if a pack runtime configured one. */
    static @Nullable VertexFormat chunkVertexFormat() {
        var chunkVertexType = WorldRenderingSettings.INSTANCE.getVertexFormat();
        return chunkVertexType == null ? null : chunkVertexType.getVertexFormat();
    }

    /**
     * Converts Iris's logical render-target format declarations to the Metal
     * formats used by the generation-owned texture set, including target zero.
     */
    private static GpuFormat[] targetFormats(final PackDirectives directives) {
        int highest = 16;
        for (Integer index : directives.getRenderTargetDirectives().getRenderTargetSettings().keySet()) {
            if (index != null && index >= 0) {
                highest = Math.max(highest, index);
            }
        }
        GpuFormat[] formats = new GpuFormat[highest + 1];
        java.util.Arrays.fill(formats, EXTENDED_TARGET_FORMAT);
        for (Map.Entry<Integer, RenderTargetSettings> entry : directives.getRenderTargetDirectives().getRenderTargetSettings().entrySet()) {
            int index = entry.getKey();
            RenderTargetSettings settings = entry.getValue();
            if (settings.getInternalFormat() != null) {
                formats[index] = formatForInternalName(settings.getInternalFormat().name());
            }
        }
        return formats;
    }

    private static String formatNames(final GpuFormat[] formats) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < formats.length; index++) {
            if (index > 0) {
                result.append(',');
            }
            result.append(formats[index]);
        }
        return result.toString();
    }

    static GpuFormat formatForInternalName(final String name) {
        return switch (name) {
            case "R8" -> GpuFormat.R8_UNORM;
            case "RG8" -> GpuFormat.RG8_UNORM;
            // Metal has no renderable three-channel RGB texture formats. Iris
            // exposes RGB as a logical pack format, so retain the component
            // precision in a four-channel attachment; GLSL vec3 reads/writes
            // keep their original semantics and the unused alpha lane is
            // ignored by the pack.
            case "RGB8" -> GpuFormat.RGBA8_UNORM;
            case "RGBA", "RGBA8" -> GpuFormat.RGBA8_UNORM;
            case "R8_SNORM" -> GpuFormat.R8_SNORM;
            case "RG8_SNORM" -> GpuFormat.RG8_SNORM;
            case "RGB8_SNORM" -> GpuFormat.RGBA8_SNORM;
            case "RGBA8_SNORM" -> GpuFormat.RGBA8_SNORM;
            case "R16" -> GpuFormat.R16_UNORM;
            case "RG16" -> GpuFormat.RG16_UNORM;
            case "RGB16" -> GpuFormat.RGBA16_UNORM;
            case "RGBA16" -> GpuFormat.RGBA16_UNORM;
            case "R16_SNORM" -> GpuFormat.R16_SNORM;
            case "RG16_SNORM" -> GpuFormat.RG16_SNORM;
            case "RGB16_SNORM" -> GpuFormat.RGBA16_SNORM;
            case "RGBA16_SNORM" -> GpuFormat.RGBA16_SNORM;
            case "R16F" -> GpuFormat.R16_FLOAT;
            case "RG16F" -> GpuFormat.RG16_FLOAT;
            case "RGB16F" -> GpuFormat.RGBA16_FLOAT;
            case "RGBA16F" -> GpuFormat.RGBA16_FLOAT;
            case "R32F" -> GpuFormat.R32_FLOAT;
            case "RG32F" -> GpuFormat.RG32_FLOAT;
            case "RGB32F" -> GpuFormat.RGBA32_FLOAT;
            case "RGBA32F" -> GpuFormat.RGBA32_FLOAT;
            case "R8I" -> GpuFormat.R8_SINT;
            case "RG8I" -> GpuFormat.RG8_SINT;
            case "RGB8I" -> GpuFormat.RGBA8_SINT;
            case "RGBA8I" -> GpuFormat.RGBA8_SINT;
            case "R8UI" -> GpuFormat.R8_UINT;
            case "RG8UI" -> GpuFormat.RG8_UINT;
            case "RGB8UI" -> GpuFormat.RGBA8_UINT;
            case "RGBA8UI" -> GpuFormat.RGBA8_UINT;
            case "R16I" -> GpuFormat.R16_SINT;
            case "RG16I" -> GpuFormat.RG16_SINT;
            case "RGB16I" -> GpuFormat.RGBA16_SINT;
            case "RGBA16I" -> GpuFormat.RGBA16_SINT;
            case "R16UI" -> GpuFormat.R16_UINT;
            case "RG16UI" -> GpuFormat.RG16_UINT;
            case "RGB16UI" -> GpuFormat.RGBA16_UINT;
            case "RGBA16UI" -> GpuFormat.RGBA16_UINT;
            case "R32I" -> GpuFormat.R32_SINT;
            case "RG32I" -> GpuFormat.RG32_SINT;
            case "RGB32I" -> GpuFormat.RGBA32_SINT;
            case "RGBA32I" -> GpuFormat.RGBA32_SINT;
            case "R32UI" -> GpuFormat.R32_UINT;
            case "RG32UI" -> GpuFormat.RG32_UINT;
            case "RGB32UI" -> GpuFormat.RGBA32_UINT;
            case "RGBA32UI" -> GpuFormat.RGBA32_UINT;
            case "RGB10_A2" -> GpuFormat.RGB10A2_UNORM;
            case "RGB10_A2UI" -> GpuFormat.RGB10A2_UINT;
            case "R11F_G11F_B10F" -> GpuFormat.RG11B10_FLOAT;
            default -> throw new IllegalArgumentException("Unsupported Iris render-target format " + name);
        };
    }
}
