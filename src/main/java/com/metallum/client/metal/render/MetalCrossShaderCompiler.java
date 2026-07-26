package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BindGroupLayout.UniformDescription;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
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
import java.nio.IntBuffer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Environment(EnvType.CLIENT)
final class MetalCrossShaderCompiler {
    private static final Set<String> BUILT_IN_UNIFORMS = Set.of("Projection", "Lighting", "Fog", "Globals");
    private static final int MSL_VERSION_4_0 = 0x040000;
    private static final Pattern VERTEX_ENTRY_PATTERN = Pattern.compile("\\bvertex\\s+\\w+\\s+(\\w+)\\s*\\(");
    private static final Pattern FRAGMENT_ENTRY_PATTERN = Pattern.compile("\\bfragment\\s+\\w+\\s+(\\w+)\\s*\\(");
    private static final Pattern EXPLICIT_FRAGMENT_OUTPUT_PATTERN = Pattern.compile(
            "\\blayout\\s*\\(\\s*location\\s*=\\s*(\\d+)[^)]*\\)\\s*"
                    + "(?:(?:flat|smooth|noperspective|centroid|sample|invariant|precise)\\s+)*"
                    + "out\\s+(?:lowp\\s+|mediump\\s+|highp\\s+)?\\w+\\s+(\\w+)\\b"
    );

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
            // S8: disk-cache the translated five-tuple. The raw sources are
            // fetched again inside getOrCompileShader on a miss; that double
            // fetch is string work in the microsecond range and cheaper than
            // threading prepared sources through the vanilla-shaped chain.
            MetalMslDiskCache diskCache = MetalMslDiskCache.instance();
            String cacheKey = null;
            if (diskCache != null) {
                String rawVertex = shaderSource.get(pipeline.getVertexShader(), ShaderType.VERTEX);
                String rawFragment = shaderSource.get(pipeline.getFragmentShader(), ShaderType.FRAGMENT);
                if (rawVertex != null && rawFragment != null) {
                    cacheKey = MetalMslDiskCache.key(
                            MetalDevice.prepareShaderSource(rawVertex, pipeline.getShaderDefines()),
                            MetalDevice.prepareShaderSource(rawFragment, pipeline.getShaderDefines()),
                            // explicitFragmentOutputLocations parses the raw
                            // (comment-carrying) text, not the prepared one.
                            rawFragment,
                            vertexFormatSignature(pipeline),
                            bindGroupSignature(pipeline),
                            Integer.toHexString(Float.floatToIntBits(MetalFxManager.shaderSampleLodBias())),
                            MetalMslDiskCache.CACHE_SALT
                    );
                    MetalMslDiskCache.Entry cached = diskCache.load(cacheKey);
                    if (cached != null) {
                        MetalMslDiskCache.recordHit();
                        if (device.isDebuggingEnabled()) {
                            Metallum.LOGGER.info("[metallum] MSL cache hit for {}", pipeline.getLocation());
                        }
                        return new MetalCompiledRenderPipeline(
                                device,
                                pipeline,
                                cached.vertexMsl(),
                                cached.fragmentMsl(),
                                cached.vertexEntryPoint(),
                                cached.fragmentEntryPoint(),
                                cached.resources()
                        );
                    }
                }
            }
            long translateStart = System.nanoTime();
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
            MslShader vertexMsl = spirvToMsl(
                    vertexSpirv.spirv(),
                    layoutEntries.size(),
                    vertexAttributeFormats(pipeline),
                    Map.of()
            );

            fragmentSpirv.rebind(tolerateUnprovidedInputs(vertexOutputs, fragmentSpirv.inputs()), layoutEntries);
            String fragmentSource = shaderSource.get(pipeline.getFragmentShader(), ShaderType.FRAGMENT);
            MslShader fragmentMsl = spirvToMsl(
                    fragmentSpirv.spirv(),
                    layoutEntries.size(),
                    Map.of(),
                    explicitFragmentOutputLocations(fragmentSource)
            );
            validateFragmentOutputSignature(pipeline, fragmentMsl.stageOutputLocations());
            String fragmentMslSource = applySampleLodBias(
                    fragmentMsl.source(),
                    MetalFxManager.shaderSampleLodBias()
            );

            String vertexEntryPoint = extractEntryPoint(vertexMsl.source(), VERTEX_ENTRY_PATTERN, "main0");
            String fragmentEntryPoint = extractEntryPoint(fragmentMslSource, FRAGMENT_ENTRY_PATTERN, "main0");
            if ("1".equals(System.getenv("METALLUM_MRT_ABI_DEBUG"))) {
                System.err.printf(
                        "[Metallum] MRT diagnostic for %s fragment entry %s:%n%s%n",
                        pipeline.getLocation(), fragmentEntryPoint, fragmentMslSource
                );
            }
            List<MetalCompiledRenderPipeline.ResourceBinding> resources = buildResourceBindings(layoutEntries, vertexMsl, fragmentMsl);
            MetalMslDiskCache.recordMiss(System.nanoTime() - translateStart);
            if (cacheKey != null) {
                diskCache.store(cacheKey, new MetalMslDiskCache.Entry(
                        vertexMsl.source(), fragmentMslSource, vertexEntryPoint, fragmentEntryPoint, resources
                ));
            }
            return new MetalCompiledRenderPipeline(
                    device,
                    pipeline,
                    vertexMsl.source(),
                    fragmentMslSource,
                    vertexEntryPoint,
                    fragmentEntryPoint,
                    resources
            );
        } catch (ShaderCompileException e) {
            throw new IllegalStateException("Failed to compile Metal cross shader for pipeline " + pipeline.getLocation(), e);
        }
    }

    /**
     * Rewrites plain fragment {@code .sample(sampler, coords)} calls to
     * {@code .sample(sampler, coords, bias(b))} so mipmapped material
     * textures keep display-resolution sharpness while the scene renders at
     * MetalFX input resolution. Calls that already carry an LOD option
     * ({@code level}, {@code bias}, {@code gradient2d}, {@code min_lod_clamp})
     * or extra arguments such as an offset are left untouched, because Metal
     * requires sample options to precede the offset argument and forbids
     * combining explicit LOD with bias. Textures without mip chains (GUI,
     * font, lightmap) are unaffected by LOD bias by construction.
     */
    static String applySampleLodBias(final String mslSource, final float lodBias) {
        if (lodBias == 0.0F || !Float.isFinite(lodBias)) {
            return mslSource;
        }
        String marker = ".sample(";
        StringBuilder patched = new StringBuilder(mslSource.length() + 256);
        String biasText = String.format(Locale.ROOT, ", bias(%sf)", lodBias);
        int cursor = 0;
        while (true) {
            int start = mslSource.indexOf(marker, cursor);
            if (start < 0) {
                patched.append(mslSource, cursor, mslSource.length());
                break;
            }
            int argsStart = start + marker.length();
            int depth = 1;
            int topLevelCommas = 0;
            boolean hasLodOption = false;
            int index = argsStart;
            while (index < mslSource.length() && depth > 0) {
                char character = mslSource.charAt(index);
                if (character == '(') {
                    depth++;
                } else if (character == ')') {
                    depth--;
                } else if (character == ',' && depth == 1) {
                    topLevelCommas++;
                }
                index++;
            }
            int close = index - 1;
            if (depth != 0) {
                patched.append(mslSource, cursor, mslSource.length());
                break;
            }
            String args = mslSource.substring(argsStart, close);
            hasLodOption = args.contains("level(") || args.contains("bias(")
                    || args.contains("gradient2d(") || args.contains("min_lod_clamp(");
            patched.append(mslSource, cursor, close);
            if (topLevelCommas == 1 && !hasLodOption) {
                patched.append(biasText);
            }
            patched.append(')');
            cursor = close + 1;
        }
        return patched.toString();
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

    /**
     * Cache-key segment covering everything the vertex-input side feeds the
     * translation: {@code rebind} assigns SPIR-V locations by position in
     * {@link MetalPipelineSupport#vertexAttributeNames}, so the <b>ordered</b>
     * name list (not just the name→format map) is part of the input.
     */
    private static String vertexFormatSignature(final RenderPipeline pipeline) {
        Map<String, GpuFormat> formats = vertexAttributeFormats(pipeline);
        StringBuilder signature = new StringBuilder();
        for (String name : MetalPipelineSupport.vertexAttributeNames(pipeline)) {
            GpuFormat format = formats.get(name);
            signature.append(name).append(':').append(format == null ? "-" : format.name()).append(';');
        }
        return signature.toString();
    }

    /**
     * Cache-key segment for {@code addToBindGroup} inputs that come from the
     * pipeline rather than the GLSL text: UTB detection and texel formats
     * are looked up in the flattened bind group layouts.
     */
    private static String bindGroupSignature(final RenderPipeline pipeline) {
        return BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts())
                + "|" + BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts());
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

    private static Map<String, Integer> explicitFragmentOutputLocations(@Nullable final String source)
            throws ShaderCompileException {
        if (source == null || source.isBlank()) {
            return Map.of();
        }

        Map<String, Integer> locations = new HashMap<>();
        Set<Integer> occupiedLocations = new HashSet<>();
        Matcher matcher = EXPLICIT_FRAGMENT_OUTPUT_PATTERN.matcher(source);
        while (matcher.find()) {
            int location = Integer.parseInt(matcher.group(1));
            String name = matcher.group(2);
            if (location < 0 || location >= ColorTargetState.MAX_COLOR_TARGETS) {
                throw new ShaderCompileException(
                        "Fragment output " + name + " uses color location " + location
                                + "; supported range is 0.." + (ColorTargetState.MAX_COLOR_TARGETS - 1)
                );
            }
            Integer previous = locations.putIfAbsent(name, location);
            if (previous != null && previous != location) {
                throw new ShaderCompileException(
                        "Fragment output " + name + " declares conflicting locations "
                                + previous + " and " + location
                );
            }
            if (previous == null && !occupiedLocations.add(location)) {
                throw new ShaderCompileException("Multiple fragment outputs declare color location " + location);
            }
        }
        return Map.copyOf(locations);
    }

    private static Set<Integer> applyExplicitFragmentOutputLocations(
            final MemoryStack stack,
            final long compiler,
            final Map<String, Integer> explicitLocations
    ) throws ShaderCompileException {
        PointerBuffer pResources = stack.mallocPointer(1);
        checkSpvc(
                Spvc.spvc_compiler_create_shader_resources(compiler, pResources),
                "spvc_compiler_create_shader_resources(fragment outputs)"
        );
        PointerBuffer pList = stack.mallocPointer(1);
        PointerBuffer pCount = stack.mallocPointer(1);
        checkSpvc(
                Spvc.spvc_resources_get_resource_list_for_type(
                        pResources.get(0), Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT, pList, pCount
                ),
                "spvc_resources_get_resource_list_for_type(STAGE_OUTPUT)"
        );

        int count = (int) pCount.get(0);
        if (count == 0) {
            return Set.of();
        }
        SpvcReflectedResource.Buffer outputs = SpvcReflectedResource.create(pList.get(0), count);
        Set<Integer> activeLocations = new HashSet<>();
        for (int index = 0; index < count; index++) {
            SpvcReflectedResource output = outputs.get(index);
            Integer location = explicitLocations.get(output.nameString());
            if (location != null) {
                Spvc.spvc_compiler_set_decoration(
                        compiler, output.id(), Spv.SpvDecorationLocation, location
                );
            }
            if (!Spvc.spvc_compiler_has_decoration(
                    compiler, output.id(), Spv.SpvDecorationBuiltIn
            )) {
                activeLocations.add(Spvc.spvc_compiler_get_decoration(
                        compiler, output.id(), Spv.SpvDecorationLocation
                ));
            }
        }
        return Set.copyOf(activeLocations);
    }

    private static void validateFragmentOutputSignature(
            final RenderPipeline pipeline,
            final Set<Integer> shaderLocations
    ) throws ShaderCompileException {
        Set<Integer> targetLocations = new HashSet<>();
        ColorTargetState[] targets = pipeline.getColorTargetStates();
        for (int index = 0; index < targets.length; index++) {
            if (targets[index] != null) {
                targetLocations.add(index);
            }
        }
        if (!shaderLocations.equals(targetLocations)) {
            throw new ShaderCompileException(
                    "Fragment output/color-target location mismatch for " + pipeline.getLocation()
                            + ": shader=" + shaderLocations + ", targets=" + targetLocations
            );
        }
    }

    private static MslShader spirvToMsl(
            final ByteBuffer spirvBytes,
            final int pushConstantBinding,
            final Map<String, GpuFormat> attributeFormats,
            final Map<String, Integer> explicitFragmentOutputLocations
    ) throws ShaderCompileException {
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
                checkSpvc(Spvc.spvc_compiler_install_compiler_options(compiler, options), "spvc_compiler_install_compiler_options");

                registerIntegerInputConversions(stack, compiler, attributeFormats);
                Set<Integer> stageOutputLocations = applyExplicitFragmentOutputLocations(
                        stack, compiler, explicitFragmentOutputLocations
                );

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
                return new MslShader(
                        MemoryUtil.memUTF8(pSource.get(0)),
                        hasPushConstants,
                        activeResources,
                        stageOutputLocations
                );
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    record MslShader(
            String source,
            boolean hasPushConstants,
            Set<String> activeResources,
            Set<Integer> stageOutputLocations
    ) {
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
