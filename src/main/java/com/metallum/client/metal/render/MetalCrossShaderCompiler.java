package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.GlslangBridge;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BindGroupLayout.UniformDescription;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout.VulkanBindGroupEntryType;
import com.mojang.blaze3d.vulkan.glsl.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslShaderInterfaceVar2;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Environment(EnvType.CLIENT)
public final class MetalCrossShaderCompiler {
    private static final Set<String> BUILT_IN_UNIFORMS = Set.of("Projection", "Lighting", "Fog", "Globals");
    private static final int MSL_VERSION_4_0 = 0x040000;
    private static final Pattern VERTEX_ENTRY_PATTERN = Pattern.compile("\\bvertex\\s+\\w+\\s+(\\w+)\\s*\\(");
    private static final Pattern FRAGMENT_ENTRY_PATTERN = Pattern.compile("\\bfragment\\s+\\w+\\s+(\\w+)\\s*\\(");

    /**
     * 在 iOS 上，Amethyst 启动器捆绑的 libMoltenVK.dylib 内部静态链接了 SPIRV-Cross，
     * 但只编译了 Vulkan 后端（MoltenVK 自己用 C++ API 做 SPIR-V→MSL 转换，不需要 C API
     * 的 MSL 后端）。LWJGL 在 iOS 上没有自己的 iOS natives，回退到 dlsym(RTLD_DEFAULT,
     * ...) 时找到的是 MoltenVK 的精简版符号，导致 spvc_context_create_compiler(
     * SPVC_BACKEND_MSL) 返回 -4 "Invalid backend"。
     *
     * 修复：在 LWJGL 的 Spvc 类被首次加载之前，从 jar 中抽取完整版 libspvc.dylib
     * （带 MSL 后端），用 System.load 加载（经 Amethyst 的 hooked dlopen），然后设置
     * Configuration.SPVC_LIBRARY_NAME 指向该路径。LWJGL 加载时会用该绝对路径直接
     * dlopen，dlsym(handle, ...) 只查询该镜像的符号，不会被 MoltenVK 抢占。
     *
     * <p><b>关键：必须在 Spvc 类首次初始化前调用。</b> Spvc.SPVC 是 static final 字段，
     * 类初始化时通过 Library.loadNative(...) 读取 Configuration.SPVC_LIBRARY_NAME
     * 并缓存。一旦 Spvc 类被加载，后续修改 Configuration.SPVC_LIBRARY_NAME 无效。
     * MetalBackend.createDevice 已经在最开头调用了 ensureSpvcLibraryConfigured，
     * 此处的静态块作为兜底，防止其他路径在 MetalBackend 之前触发 Spvc 类加载。
     */
    static {
        MetalNativeBridge.ensureSpvcLibraryConfigured();
    }

    private MetalCrossShaderCompiler() {
    }

    static MetalCompiledRenderPipeline compile(final MetalDevice device, final RenderPipeline pipeline, final ShaderSource shaderSource) {
        try {
            IntermediaryShaderModule vertexSpirv = device.getOrCompileShader(pipeline.getVertexShader(), ShaderType.VERTEX, pipeline.getShaderDefines(), shaderSource);
            IntermediaryShaderModule fragmentSpirv = device.getOrCompileShader(pipeline.getFragmentShader(), ShaderType.FRAGMENT, pipeline.getShaderDefines(), shaderSource);
            if (vertexSpirv == IntermediaryShaderModule.INVALID || fragmentSpirv == IntermediaryShaderModule.INVALID) {
                throw new IllegalStateException(
                        "Couldn't compile shader for pipeline " + pipeline.getLocation()
                );
            }

            List<VulkanBindGroupLayout.Entry> layoutEntries = new ArrayList<>();
            addToBindGroup(layoutEntries, vertexSpirv, pipeline);
            addToBindGroup(layoutEntries, fragmentSpirv, pipeline);
            List<String> vertexOutputs = extractVariableNames(vertexSpirv.outputs());

            vertexSpirv.rebind(tolerateUnprovidedInputs(MetalPipelineSupport.vertexAttributeNames(pipeline), vertexSpirv.inputs()), layoutEntries);
            boolean enablePointSize = pipeline.getPrimitiveTopology() == com.mojang.blaze3d.PrimitiveTopology.POINTS;
            MslShader vertexMsl = spirvToMsl(vertexSpirv.spirv(), layoutEntries.size(), vertexAttributeFormats(pipeline), enablePointSize);

            fragmentSpirv.rebind(tolerateUnprovidedInputs(vertexOutputs, fragmentSpirv.inputs()), layoutEntries);
            MslShader fragmentMsl = spirvToMsl(fragmentSpirv.spirv(), layoutEntries.size(), Map.of(), true);

            String vertexEntryPoint = extractEntryPoint(vertexMsl.source(), VERTEX_ENTRY_PATTERN, "main0");
            String fragmentEntryPoint = extractEntryPoint(fragmentMsl.source(), FRAGMENT_ENTRY_PATTERN, "main0");
            List<MetalCompiledRenderPipeline.ResourceBinding> resources = buildResourceBindings(layoutEntries, vertexMsl, fragmentMsl);
            return new MetalCompiledRenderPipeline(
                    device,
                    pipeline,
                    vertexMsl.source(),
                    fragmentMsl.source(),
                    vertexEntryPoint,
                    fragmentEntryPoint,
                    resources
            );
        } catch (ShaderCompileException e) {
            throw new IllegalStateException("Failed to compile Metal cross shader for pipeline " + pipeline.getLocation(), e);
        }
    }

    /**
     * Compiles a shaderpack (Iris light-shader) GLSL pair to a Metal pipeline.
     *
     * <p>Unlike {@link #compile(MetalDevice, RenderPipeline, ShaderSource)}, this
     * entry point bypasses the blaze3d {@code device.getOrCompileShader} SPIR-V
     * cache and compiles GLSL directly via {@link GlslangBridge} (GLSL&#8594;SPIR-V),
     * then reuses the existing SPIRV-Cross SPIR-V&#8594;MSL path ({@link #spirvToMsl}).
     *
     * <p>The vertex&#8594;fragment input/output rebind tolerance applied in the
     * vanilla path ({@code tolerateUnprovidedInputs} /
     * {@code IntermediaryShaderModule.rebind}) is intentionally skipped: there is
     * no {@code IntermediaryShaderModule} for the raw SPIR-V produced by glslang,
     * and Iris's {@code TransformPatcher} is expected to already emit
     * vertex/fragment interfaces that match.
     *
     * @param device                   the Metal device.
     * @param name                     logical name used in error messages.
     * @param vertexGlsl               vertex GLSL source (must declare its own {@code #version}).
     * @param fragmentGlsl             fragment GLSL source (must declare its own {@code #version}).
     * @param defines                  optional preprocessor defines forwarded to glslang.
     * @param bindGroupEntries         resource bindings; its size selects the
     *                                 push-constant binding slot (mirrors the
     *                                 vanilla path's {@code layoutEntries.size()}).
     * @param vertexAttributeFormats   vertex attribute formats for integer-input
     *                                 conversion (may be empty).
     * @param enablePointSize          whether to emit Metal {@code [[point_size]]}
     *                                 (POINTS topology only).
     * @param cull                     back-face cull enabled.
     * @param polygonMode              fill / wireframe.
     * @param primitiveTopology        primitive topology.
     * @param vertexFormatBindings     vertex format bindings.
     * @param depthStencilState        depth/stencil state (nullable).
     * @param colorTarget              color target state (nullable).
     * @return the compiled Metal render pipeline.
     * @throws ShaderCompileException if GLSL&#8594;SPIR-V or SPIR-V&#8594;MSL fails.
     */
    static MetalCompiledRenderPipeline compileShaderpack(
            final MetalDevice device,
            final String name,
            final String vertexGlsl,
            final String fragmentGlsl,
            final String defines,
            final List<VulkanBindGroupLayout.Entry> bindGroupEntries,
            final Map<String, GpuFormat> vertexAttributeFormats,
            final boolean enablePointSize,
            final boolean cull,
            final PolygonMode polygonMode,
            final PrimitiveTopology primitiveTopology,
            final VertexFormat[] vertexFormatBindings,
            final DepthStencilState depthStencilState,
            final ColorTargetState colorTarget
    ) throws ShaderCompileException {
        final int[] vertexSpvWords;
        final int[] fragmentSpvWords;
        try {
            vertexSpvWords = GlslangBridge.compileGlslToSpv(GlslangBridge.Stage.VERTEX, vertexGlsl, defines);
        } catch (GlslangBridge.ShaderCompileException e) {
            throw wrapGlslangError("Failed to compile shaderpack vertex shader '" + name + "'", e);
        }
        try {
            fragmentSpvWords = GlslangBridge.compileGlslToSpv(GlslangBridge.Stage.FRAGMENT, fragmentGlsl, defines);
        } catch (GlslangBridge.ShaderCompileException e) {
            throw wrapGlslangError("Failed to compile shaderpack fragment shader '" + name + "'", e);
        }

        final int pushConstantBinding = bindGroupEntries.size();
        final MslShader vertexMsl = spirvToMsl(spirvWordsToByteBuffer(vertexSpvWords), pushConstantBinding, vertexAttributeFormats, enablePointSize);
        final MslShader fragmentMsl = spirvToMsl(spirvWordsToByteBuffer(fragmentSpvWords), pushConstantBinding, Map.of(), true);

        final String vertexEntryPoint = extractEntryPoint(vertexMsl.source(), VERTEX_ENTRY_PATTERN, "main0");
        final String fragmentEntryPoint = extractEntryPoint(fragmentMsl.source(), FRAGMENT_ENTRY_PATTERN, "main0");
        final List<MetalCompiledRenderPipeline.ResourceBinding> resources = buildResourceBindings(bindGroupEntries, vertexMsl, fragmentMsl);

        return new MetalCompiledRenderPipeline(
                device,
                name,
                vertexMsl.source(),
                fragmentMsl.source(),
                vertexEntryPoint,
                fragmentEntryPoint,
                resources,
                cull,
                polygonMode,
                primitiveTopology,
                vertexFormatBindings,
                depthStencilState,
                colorTarget
        );
    }

    /**
     * Cache of shaderpack programs that have been successfully dry-compiled to
     * MSL, keyed by program name. Populated by
     * {@link #tryCompileShaderpackMsl} and intended for retrieval by the
     * (forthcoming) full Iris&rarr;Metal pipeline-binding step, which needs the
     * compiled MSL sources and entry points to construct a
     * {@link MetalCompiledRenderPipeline}.
     */
    private static final Map<String, ShaderpackMslResult> SHADERPACK_MSL_CACHE = new ConcurrentHashMap<>();

    /**
     * Dry-compile an Iris shaderpack program through the full
     * glslang&#8594;SPIRV-Cross&#8594;MSL pipeline WITHOUT creating a
     * {@link MetalCompiledRenderPipeline} or requiring a {@link MetalDevice}.
     *
     * <p>This entry point validates that a shaderpack program's GLSL (already
     * patched and {@code #include}-expanded by Iris's {@code TransformPatcher})
     * can be cross-compiled to MSL, and caches the resulting MSL sources for
     * the subsequent pipeline-binding step. It is the natural progression from
     * {@link #compileShaderpack}: same GLSL&#8594;MSL pipeline, but decoupled
     * from {@code MetalDevice} so it can be invoked from Iris
     * {@code ShaderCreator.link} interception before a Metal pipeline state
     * object is assembled.
     *
     * <p><b>Limitations.</b>
     * <ul>
     *   <li>Geometry and tessellation (tessControl/tessEval) stages are accepted
     *       for API symmetry with {@code ShaderCreator.link} but are <b>not</b>
     *       compiled: the current Metal pipeline is vertex+fragment only. A
     *       warning is logged when any non-null non-vertex/fragment stage is
     *       present.</li>
     *   <li>Vertex attribute integer&#8594;MSL conversion
     *       ({@link #registerIntegerInputConversions}) is skipped (empty
     *       attribute-format map); the dry-compiled MSL therefore uses default
     *       vertex input declarations. Full conversion is applied in
     *       {@link #compileShaderpack} once the {@code VertexFormat} bindings
     *       are known.</li>
     *   <li>The push-constant binding slot defaults to {@code 0} (no bind-group
     *       entries); {@link #compileShaderpack} derives it from
     *       {@code bindGroupEntries.size()}.</li>
     *   <li>{@code enablePointSize} is {@code false} for the vertex stage in
     *       dry-compile (the actual topology is not known here).</li>
     * </ul>
     *
     * <p>On success the result is cached in {@link #SHADERPACK_MSL_CACHE} under
     * {@code name} (overwriting any prior entry) so the pipeline-binding step
     * can retrieve it without recompiling.
     *
     * @param name             logical program name (also the cache key).
     * @param vertexGlsl       vertex GLSL source (must be non-null and declare
     *                         its own {@code #version}).
     * @param geometryGlsl     geometry GLSL source (nullable; ignored with a
     *                         warning if non-null).
     * @param tessControlGlsl  tessellation-control GLSL source (nullable;
     *                         ignored with a warning if non-null).
     * @param tessEvalGlsl     tessellation-evaluation GLSL source (nullable;
     *                         ignored with a warning if non-null).
     * @param fragmentGlsl     fragment GLSL source (must be non-null and declare
     *                         its own {@code #version}).
     * @param defines          optional preprocessor defines forwarded to
     *                         glslang (may be {@code null}).
     * @return the dry-compiled MSL result (also cached).
     * @throws ShaderCompileException if GLSL&#8594;SPIR-V or SPIR-V&#8594;MSL
     *                               fails; the exception message includes the
     *                               glslang info log.
     */
    public static ShaderpackMslResult tryCompileShaderpackMsl(
            final String name,
            final @Nullable String vertexGlsl,
            final @Nullable String geometryGlsl,
            final @Nullable String tessControlGlsl,
            final @Nullable String tessEvalGlsl,
            final @Nullable String fragmentGlsl,
            final @Nullable String defines
    ) throws ShaderCompileException {
        if (vertexGlsl == null || fragmentGlsl == null) {
            throw new ShaderCompileException(
                    "Cannot dry-compile shaderpack program '" + name + "': vertex or fragment GLSL is null "
                            + "(vertex=" + (vertexGlsl == null ? "null" : "present")
                            + ", fragment=" + (fragmentGlsl == null ? "null" : "present") + ")."
            );
        }
        if (geometryGlsl != null || tessControlGlsl != null || tessEvalGlsl != null) {
            Metallum.LOGGER.warn(
                    "[MetalUniversal/Iris] Shaderpack program '{}' declares geometry/tessellation stages, "
                            + "which have no Metal equivalent in the current vertex+fragment pipeline; "
                            + "they are skipped by tryCompileShaderpackMsl.",
                    name
            );
        }

        final int[] vertexSpvWords;
        final int[] fragmentSpvWords;
        try {
            vertexSpvWords = GlslangBridge.compileGlslToSpv(GlslangBridge.Stage.VERTEX, vertexGlsl, defines);
        } catch (GlslangBridge.ShaderCompileException e) {
            throw wrapGlslangError("Failed to dry-compile shaderpack vertex shader '" + name + "'", e);
        }
        try {
            fragmentSpvWords = GlslangBridge.compileGlslToSpv(GlslangBridge.Stage.FRAGMENT, fragmentGlsl, defines);
        } catch (GlslangBridge.ShaderCompileException e) {
            throw wrapGlslangError("Failed to dry-compile shaderpack fragment shader '" + name + "'", e);
        }

        // Dry-compile defaults: no vertex-attribute integer conversion, no point
        // size, push-constant binding slot 0 (no bind-group entries). These are
        // refined by compileShaderpack once the full pipeline state is known.
        final MslShader vertexMsl = spirvToMsl(spirvWordsToByteBuffer(vertexSpvWords), 0, Map.of(), false);
        final MslShader fragmentMsl = spirvToMsl(spirvWordsToByteBuffer(fragmentSpvWords), 0, Map.of(), true);

        final String vertexEntryPoint = extractEntryPoint(vertexMsl.source(), VERTEX_ENTRY_PATTERN, "main0");
        final String fragmentEntryPoint = extractEntryPoint(fragmentMsl.source(), FRAGMENT_ENTRY_PATTERN, "main0");

        final ShaderpackMslResult result = new ShaderpackMslResult(
                name, vertexMsl.source(), fragmentMsl.source(), vertexEntryPoint, fragmentEntryPoint
        );
        SHADERPACK_MSL_CACHE.put(name, result);
        return result;
    }

    /**
     * Retrieves a previously dry-compiled shaderpack MSL result by program name,
     * or {@code null} if {@code name} has not been dry-compiled (or was evicted).
     * Intended for the forthcoming Iris&rarr;Metal pipeline-binding step.
     *
     * @param name the program name used as the cache key.
     * @return the cached MSL result, or {@code null}.
     */
    public static @Nullable ShaderpackMslResult getCachedShaderpackMsl(final String name) {
        return SHADERPACK_MSL_CACHE.get(name);
    }

    /**
     * Cache of shaderpack programs whose Metal render pipeline state object
     * ({@link MetalCompiledRenderPipeline}) has been successfully constructed,
     * keyed by program name. Populated by
     * {@link #compileShaderpackPipeline} and intended for retrieval by the
     * (forthcoming) Iris&rarr;Metal render dispatch step.
     */
    private static final Map<String, MetalCompiledRenderPipeline> SHADERPACK_PIPELINE_CACHE = new ConcurrentHashMap<>();

    /**
     * Constructs a {@link MetalCompiledRenderPipeline} for an Iris shaderpack
     * program, using the active {@link MetalDevice} from
     * {@link MetalDeviceRegistry} and default pipeline states.
     *
     * <p>This is the public entry point called from the Iris intercept mixin
     * ({@code ShaderCreatorMixin}) when the Metal backend is active. It
     * performs the full GLSL&#8594;SPIR-V&#8594;MSL&#8594;pipeline construction
     * in one shot, caching the resulting pipeline under {@code name} for later
     * retrieval by the render dispatch path.
     *
     * <p><b>Default pipeline states.</b> Because Iris manages framebuffers,
     * depth/stencil, blend, and cull states outside of
     * {@code ShaderCreator.link}, this method uses conservative defaults:
     * <ul>
     *   <li>{@code cull = false} (shaderpacks manage their own culling)</li>
     *   <li>{@code polygonMode = FILL}</li>
     *   <li>{@code primitiveTopology = TRIANGLES}</li>
     *   <li>{@code depthStencilState = null} (Iris manages depth via
     *       framebuffers)</li>
     *   <li>{@code colorTarget = null} (Iris manages color attachments via
     *       framebuffers)</li>
     *   <li>{@code bindGroupEntries = empty} (resource bindings will be
     *       populated by SPIR-V reflection in a future refinement; the pipeline
     *       compiles but uniform/sampler bindings are not yet wired)</li>
     * </ul>
     *
     * <p><b>Limitations.</b> The returned pipeline compiles and links the MSL
     * shaders into a Metal pipeline state object, but the resource bindings
     * (uniform buffers, samplers) are not yet mapped from Iris's sampler/uniform
     * model to Metal's bind-group slots. Rendering with this pipeline will
     * require the forthcoming bind-group mapping step.
     *
     * @param name             logical program name (also the cache key).
     * @param vertexGlsl       vertex GLSL source (must be non-null).
     * @param fragmentGlsl     fragment GLSL source (must be non-null).
     * @param defines          optional preprocessor defines (may be null).
     * @param vertexFormat     the Iris vertex format (drives vertex attribute
     *                         integer conversion and the Metal vertex
     *                         descriptor).
     * @param enablePointSize  whether to emit Metal {@code [[point_size]]}
     *                         (true for POINTS topology programs).
     * @return {@code true} if the pipeline was successfully constructed and
     *         cached; {@code false} if no Metal device is active.
     * @throws ShaderCompileException if GLSL&#8594;SPIR-V, SPIR-V&#8594;MSL,
     *                               or Metal pipeline state creation fails.
     */
    public static boolean compileShaderpackPipeline(
            final String name,
            final String vertexGlsl,
            final String fragmentGlsl,
            final @Nullable String defines,
            final VertexFormat vertexFormat,
            final boolean enablePointSize
    ) throws ShaderCompileException {
        final MetalDevice device = MetalDeviceRegistry.getActiveDevice();
        if (device == null) {
            return false;
        }

        final Map<String, GpuFormat> vertexAttributeFormats = new LinkedHashMap<>();
        for (VertexFormatElement element : vertexFormat.getElements()) {
            vertexAttributeFormats.putIfAbsent(element.name(), element.format());
        }

        final MetalCompiledRenderPipeline pipeline = compileShaderpack(
                device,
                name,
                vertexGlsl,
                fragmentGlsl,
                defines,
                List.of(),
                vertexAttributeFormats,
                enablePointSize,
                false,
                PolygonMode.FILL,
                PrimitiveTopology.TRIANGLES,
                new VertexFormat[]{vertexFormat},
                null,
                null
        );
        SHADERPACK_PIPELINE_CACHE.put(name, pipeline);
        return true;
    }

    /**
     * Returns whether a Metal render pipeline has been constructed and cached
     * for the given shaderpack program name.
     *
     * @param name the program name.
     * @return {@code true} if a cached pipeline exists.
     */
    public static boolean hasCachedShaderpackPipeline(final String name) {
        return SHADERPACK_PIPELINE_CACHE.containsKey(name);
    }

    /**
     * Retrieves a cached shaderpack Metal render pipeline by program name.
     * Intended for internal use by the Metal render dispatch path (within the
     * {@code com.metallum.client.metal.render} package).
     *
     * @param name the program name.
     * @return the cached pipeline, or {@code null}.
     */
    static @Nullable MetalCompiledRenderPipeline getCachedShaderpackPipeline(final String name) {
        return SHADERPACK_PIPELINE_CACHE.get(name);
    }

    /**
     * Result of a successful shaderpack dry-compile: the program name, the
     * compiled vertex/fragment MSL sources, and their entry-point function
     * names. Cached in {@link #SHADERPACK_MSL_CACHE} for retrieval by the
     * pipeline-binding step.
     */
    public record ShaderpackMslResult(
            String name,
            String vertexMsl,
            String fragmentMsl,
            String vertexEntryPoint,
            String fragmentEntryPoint
    ) {
    }

    /**
     * Wraps a SPIR-V word array into a heap {@link ByteBuffer} in
     * {@link ByteOrder#LITTLE_ENDIAN} order. SPIR-V is a little-endian word
     * stream; the resulting buffer's position is left at {@code 0} so that
     * {@code asIntBuffer()} views (used by {@link #spirvToMsl}) start at the
     * first word. The view buffer advances its own position independently of
     * this buffer's position.
     */
    private static ByteBuffer spirvWordsToByteBuffer(final int[] words) {
        final ByteBuffer buffer = ByteBuffer.allocate(words.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asIntBuffer().put(words);
        return buffer;
    }

    /**
     * Rewraps a {@link GlslangBridge.ShaderCompileException} (an unchecked
     * {@code RuntimeException} from the native glslang bridge) as a blaze3d
     * {@link ShaderCompileException}, preserving the original failure as the
     * cause via {@code initCause}. The blaze3d type is the one already thrown
     * throughout this class and expected by callers of {@link #compile}.
     */
    private static ShaderCompileException wrapGlslangError(final String message, final GlslangBridge.ShaderCompileException cause) {
        final ShaderCompileException wrapped = new ShaderCompileException(message);
        wrapped.initCause(cause);
        return wrapped;
    }

    private static void addToBindGroup(
            final List<VulkanBindGroupLayout.Entry> entries,
            final IntermediaryShaderModule shader,
            final RenderPipeline pipeline
    ) throws ShaderCompileException {
        List<UniformDescription> uniforms = BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts());
        List<String> samplers = BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts());
        for (SpvUniformBuffer buffer : shader.uniformBuffers()) {
            String name = buffer.name();
            if (findUniform(uniforms, name) == null && !BUILT_IN_UNIFORMS.contains(name)) {
                throw new ShaderCompileException("Unable to find shader defined uniform (" + name + ")");
            }
            addBindingIfAbsent(entries, VulkanBindGroupEntryType.UNIFORM_BUFFER, name, null);
        }

        for (SpvSampler sampler : shader.samplers()) {
            String name = sampler.name();
            UniformDescription uniform = findUniform(uniforms, name);
            int dimensions = sampler.dimensions();
            if (uniform != null) {
                if (dimensions != Spv.SpvDimBuffer) {
                    throw new ShaderCompileException("UTB (" + name + ") must have type of SpvDimBuffer");
                }
                addBindingIfAbsent(entries, VulkanBindGroupEntryType.TEXEL_BUFFER, name, uniform.gpuFormat());
            } else {
                if (!samplers.contains(name)) {
                    throw new ShaderCompileException("Unable to find shader defined uniform (" + name + ")");
                }
                if (dimensions != Spv.SpvDim2D && dimensions != Spv.SpvDimCube) {
                    throw new ShaderCompileException("Sampled texture (" + name + ") must have type of SpvDim2D or SpvDimCube");
                }
                addBindingIfAbsent(entries, VulkanBindGroupEntryType.SAMPLED_IMAGE, name, null);
            }
        }
    }

    @Nullable
    private static UniformDescription findUniform(final List<UniformDescription> uniforms, final String name) {
        for (UniformDescription uniform : uniforms) {
            if (uniform.name().equals(name)) {
                return uniform;
            }
        }
        return null;
    }

    private static void addBindingIfAbsent(
            final List<VulkanBindGroupLayout.Entry> entries,
            final VulkanBindGroupEntryType type,
            final String name,
            @Nullable final GpuFormat texelBufferFormat
    ) {
        for (VulkanBindGroupLayout.Entry entry : entries) {
            if (entry.type() == type && entry.name().equals(name)) {
                return;
            }
        }
        entries.add(new VulkanBindGroupLayout.Entry(type, name, texelBufferFormat));
    }

    private static List<String> tolerateUnprovidedInputs(final List<String> provided, final List<SpvVariable> shaderInputs) {
        List<String> result = null;
        for (SpvVariable input : shaderInputs) {
            String name = input.name();
            if (!provided.contains(name)) {
                if (result == null) {
                    result = new ArrayList<>(provided);
                }
                if (!result.contains(name)) {
                    result.add(name);
                }
            }
        }
        return result == null ? provided : result;
    }

    private static List<String> extractVariableNames(final List<SpvVariable> variables) {
        List<String> names = new ArrayList<>(variables.size());
        for (SpvVariable variable : variables) {
            names.add(variable.name());
        }
        return names;
    }

    private static String extractEntryPoint(final String msl, final Pattern pattern, final String fallback) {
        Matcher matcher = pattern.matcher(msl);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    private static List<MetalCompiledRenderPipeline.ResourceBinding> buildResourceBindings(
            final List<VulkanBindGroupLayout.Entry> entries,
            final MslShader vertexMsl,
            final MslShader fragmentMsl
    ) {
        List<MetalCompiledRenderPipeline.ResourceBinding> resources = new ArrayList<>(entries.size() + 1);
        for (int index = 0; index < entries.size(); index++) {
            VulkanBindGroupLayout.Entry entry = entries.get(index);
            MetalCompiledRenderPipeline.ResourceKind kind = switch (entry.type()) {
                case UNIFORM_BUFFER -> MetalCompiledRenderPipeline.ResourceKind.UNIFORM_BUFFER;
                case SAMPLED_IMAGE -> MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE;
                case TEXEL_BUFFER -> MetalCompiledRenderPipeline.ResourceKind.TEXEL_BUFFER;
            };
            GpuFormat texelFormat = entry.type() == VulkanBindGroupLayout.VulkanBindGroupEntryType.TEXEL_BUFFER ? entry.texelBufferFormat() : null;
            resources.add(new MetalCompiledRenderPipeline.ResourceBinding(kind, entry.name(), index, stageMask(entry.name(), vertexMsl, fragmentMsl), texelFormat));
        }

        int pushConstantStageMask = (vertexMsl.hasPushConstants() ? MetalCompiledRenderPipeline.STAGE_VERTEX : 0)
                | (fragmentMsl.hasPushConstants() ? MetalCompiledRenderPipeline.STAGE_FRAGMENT : 0);
        if (pushConstantStageMask != 0) {
            resources.add(new MetalCompiledRenderPipeline.ResourceBinding(
                    MetalCompiledRenderPipeline.ResourceKind.UNIFORM_BUFFER,
                    "push_constants",
                    entries.size(),
                    pushConstantStageMask,
                    null
            ));
        }
        return resources;
    }

    private static int stageMask(
            final String name,
            final MslShader vertexMsl,
            final MslShader fragmentMsl
    ) {
        int mask = 0;
        if (vertexMsl.activeResources().contains(name)) {
            mask |= MetalCompiledRenderPipeline.STAGE_VERTEX;
        }
        if (fragmentMsl.activeResources().contains(name)) {
            mask |= MetalCompiledRenderPipeline.STAGE_FRAGMENT;
        }
        if (mask == 0) {
            mask = MetalCompiledRenderPipeline.STAGE_ALL;
        }

        return mask;
    }

    private static Map<String, GpuFormat> vertexAttributeFormats(final RenderPipeline pipeline) {
        Map<String, GpuFormat> formats = new LinkedHashMap<>();
        for (VertexFormat binding : pipeline.getVertexFormatBindings()) {
            if (binding != null) {
                for (VertexFormatElement element : binding.getElements()) {
                    formats.putIfAbsent(element.name(), element.format());
                }
            }
        }
        return formats;
    }

    private static void registerIntegerInputConversions(
            final MemoryStack stack,
            final long compiler,
            final Map<String, GpuFormat> attributeFormats
    ) throws ShaderCompileException {
        if (attributeFormats.isEmpty()) {
            return;
        }

        PointerBuffer pResources = stack.mallocPointer(1);
        checkSpvc(Spvc.spvc_compiler_create_shader_resources(compiler, pResources), "spvc_compiler_create_shader_resources");

        PointerBuffer pList = stack.mallocPointer(1);
        PointerBuffer pCount = stack.mallocPointer(1);
        checkSpvc(Spvc.spvc_resources_get_resource_list_for_type(pResources.get(0), Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT, pList, pCount), "spvc_resources_get_resource_list_for_type(STAGE_INPUT)");
        int count = (int) pCount.get(0);
        if (count == 0) {
            return;
        }

        SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(pList.get(0), count);
        for (int i = 0; i < count; i++) {
            SpvcReflectedResource input = list.get(i);
            GpuFormat format = attributeFormats.get(input.nameString());
            if (format == null || !format.name().endsWith("_UINT")) {
                continue;
            }
            int width = format.name().contains("8") ? Spvc.SPVC_MSL_SHADER_VARIABLE_FORMAT_UINT8
                    : format.name().contains("16") ? Spvc.SPVC_MSL_SHADER_VARIABLE_FORMAT_UINT16
                      : Spvc.SPVC_MSL_SHADER_VARIABLE_FORMAT_OTHER;
            if (width == Spvc.SPVC_MSL_SHADER_VARIABLE_FORMAT_OTHER) {
                continue;
            }

            long typeHandle = Spvc.spvc_compiler_get_type_handle(compiler, input.type_id());
            int baseType = Spvc.spvc_type_get_basetype(typeHandle);
            if (baseType != Spvc.SPVC_BASETYPE_INT8 && baseType != Spvc.SPVC_BASETYPE_INT16
                    && baseType != Spvc.SPVC_BASETYPE_INT32 && baseType != Spvc.SPVC_BASETYPE_INT64) {
                continue;
            }

            SpvcMslShaderInterfaceVar2 var = SpvcMslShaderInterfaceVar2.malloc(stack);
            Spvc.spvc_msl_shader_interface_var_init_2(var);
            var.location(Spvc.spvc_compiler_get_decoration(compiler, input.id(), Spv.SpvDecorationLocation));
            var.vecsize(Spvc.spvc_type_get_vector_size(typeHandle));
            var.format(width);
            var.rate(Spvc.SPVC_MSL_SHADER_VARIABLE_RATE_PER_VERTEX);
            checkSpvc(Spvc.spvc_compiler_msl_add_shader_input_2(compiler, var), "spvc_compiler_msl_add_shader_input_2");
        }
    }

    private static MslShader spirvToMsl(final ByteBuffer spirvBytes, final int pushConstantBinding, final Map<String, GpuFormat> attributeFormats, final boolean enablePointSize) throws ShaderCompileException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer spirvWords = spirvBytes.asIntBuffer();
            int wordCount = spirvWords.remaining();

            // SPIR-V 二进制必须至少包含 5 个字（头部：magic、version、generator、bound、schema）。
            // 空或过短的 SPIR-V 会导致 spvc_context_parse_spirv 在某些版本中行为不确定。
            if (wordCount < 5) {
                throw new ShaderCompileException(
                        "SPIR-V is too small: " + wordCount + " words (minimum 5 required). " +
                        "ByteBuffer remaining=" + spirvBytes.remaining() + " byteOrder=" + spirvBytes.order()
                );
            }

            int magic = spirvWords.get(0);

            PointerBuffer pContext = stack.mallocPointer(1);
            checkSpvc(Spvc.spvc_context_create(pContext), "spvc_context_create");
            long context = pContext.get(0);
            try {
                PointerBuffer pIr = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_context_parse_spirv(context, spirvWords, wordCount, pIr), "spvc_context_parse_spirv");

                long ir = pIr.get(0);
                if (ir == 0L) {
                    // spvc_context_parse_spirv 返回了成功但未写入 IR 指针。
                    // 这通常表示加载的 libspvc.dylib 版本与 LWJGL 绑定不匹配，
                    // 或者 MoltenVK 导出的 spvc_ 符号覆盖了 LWJGL 的实现。
                    String lastError = Spvc.spvc_context_get_last_error_string(context);
                    throw new ShaderCompileException(
                            "spvc_context_parse_spirv returned SPVC_SUCCESS but parsed_ir is NULL. " +
                            "This indicates a version mismatch between the loaded libspvc.dylib and LWJGL's Java bindings, " +
                            "or symbol interposition from another library (e.g. libMoltenVK.dylib). " +
                            "SPIR-V: " + wordCount + " words, magic=0x" + Integer.toHexString(magic) + ". " +
                            "Last error: " + lastError
                    );
                }

                PointerBuffer pCompiler = stack.mallocPointer(1);
                int createCompilerResult = Spvc.spvc_context_create_compiler(
                        context, Spvc.SPVC_BACKEND_MSL, ir, Spvc.SPVC_CAPTURE_MODE_COPY, pCompiler
                );
                if (createCompilerResult != Spvc.SPVC_SUCCESS) {
                    String lastError = Spvc.spvc_context_get_last_error_string(context);
                    throw new ShaderCompileException(
                            "SPIRV-Cross error at spvc_context_create_compiler: " + createCompilerResult +
                            " (context=0x" + Long.toHexString(context) + ", ir=0x" + Long.toHexString(ir) +
                            ", backend=MSL, mode=COPY). Last error: " + lastError
                    );
                }
                long compiler = pCompiler.get(0);

                PointerBuffer pOptions = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_compiler_create_compiler_options(compiler, pOptions), "spvc_compiler_create_compiler_options");
                long options = pOptions.get(0);
                checkSpvc(
                        Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS),
                        "spvc_compiler_options_set_uint(MSL_PLATFORM)"
                );
                checkSpvc(
                        Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION_4_0),
                        "spvc_compiler_options_set_uint(MSL_VERSION)"
                );
                checkSpvc(
                        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING, true),
                        "spvc_compiler_options_set_bool(MSL_ENABLE_DECORATION_BINDING)"
                );
                checkSpvc(
                        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true),
                        "spvc_compiler_options_set_bool(MSL_TEXTURE_BUFFER_NATIVE)"
                );
                checkSpvc(
                        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true),
                        "spvc_compiler_options_set_bool(FLIP_VERTEX_Y)"
                );
                // Metal 拒绝非 Point 拓扑管线携带 [[point_size]] 顶点输出（报错：
                // "Vertex shader writes point size but inputPrimitiveTopology is ..."）。
                // 仅 POINTS 拓扑需要 point_size；对 DEBUG_LINES/TRIANGLES/QUADS 等拓扑抑制该内建，
                // 使 makeRenderPipelineState 不再失败（修复 litematica 覆盖轮廓渲染崩溃）。
                checkSpvc(
                        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_BUILTIN, enablePointSize),
                        "spvc_compiler_options_set_bool(MSL_ENABLE_POINT_SIZE_BUILTIN)"
                );
                checkSpvc(Spvc.spvc_compiler_install_compiler_options(compiler, options), "spvc_compiler_install_compiler_options");

                registerIntegerInputConversions(stack, compiler, attributeFormats);

                PointerBuffer pActiveSet = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_compiler_get_active_interface_variables(compiler, pActiveSet), "spvc_compiler_get_active_interface_variables");
                long activeSet = pActiveSet.get(0);
                checkSpvc(Spvc.spvc_compiler_set_enabled_interface_variables(compiler, activeSet), "spvc_compiler_set_enabled_interface_variables");

                Set<String> activeResources = collectActiveResourceNames(stack, compiler, activeSet);

                PointerBuffer pResources = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_compiler_create_shader_resources(compiler, pResources), "spvc_compiler_create_shader_resources");
                long resources = pResources.get(0);

                PointerBuffer pList = stack.mallocPointer(1);
                PointerBuffer pCount = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_resources_get_resource_list_for_type(resources, Spvc.SPVC_RESOURCE_TYPE_PUSH_CONSTANT, pList, pCount), "spvc_resources_get_resource_list_for_type");
                boolean hasPushConstants = pCount.get(0) > 0;
                if (hasPushConstants) {
                    SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(pList.get(0), 1);
                    Spvc.spvc_compiler_set_decoration(compiler, list.get(0).id(), Spv.SpvDecorationBinding, pushConstantBinding);
                }

                PointerBuffer pSource = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_compiler_compile(compiler, pSource), "spvc_compiler_compile");
                return new MslShader(MemoryUtil.memUTF8(pSource.get(0)), hasPushConstants, activeResources);
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    record MslShader(String source, boolean hasPushConstants, Set<String> activeResources) {
    }

    private static Set<String> collectActiveResourceNames(final MemoryStack stack, final long compiler, final long activeSet) throws ShaderCompileException {
        PointerBuffer pResources = stack.mallocPointer(1);
        checkSpvc(
                Spvc.spvc_compiler_create_shader_resources_for_active_variables(compiler, pResources, activeSet),
                "spvc_compiler_create_shader_resources_for_active_variables"
        );
        long resources = pResources.get(0);

        Set<String> names = new HashSet<>();
        collectResourceNames(stack, resources, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, names);
        collectResourceNames(stack, resources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, names);
        collectResourceNames(stack, resources, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE, names);
        collectResourceNames(stack, resources, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS, names);
        return names;
    }

    private static void collectResourceNames(
            final MemoryStack stack,
            final long resources,
            final int resourceType,
            final Set<String> out
    ) throws ShaderCompileException {
        PointerBuffer pList = stack.mallocPointer(1);
        PointerBuffer pCount = stack.mallocPointer(1);
        checkSpvc(Spvc.spvc_resources_get_resource_list_for_type(resources, resourceType, pList, pCount), "spvc_resources_get_resource_list_for_type");
        int count = (int) pCount.get(0);
        if (count == 0) {
            return;
        }
        SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(pList.get(0), count);
        for (int i = 0; i < count; i++) {
            out.add(list.get(i).nameString());
        }
    }

    private static void checkSpvc(final int result, final String stage) throws ShaderCompileException {
        if (result != Spvc.SPVC_SUCCESS) {
            throw new ShaderCompileException("SPIRV-Cross error at " + stage + ": " + result);
        }
    }
}
