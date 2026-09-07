package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLVertexFormat;
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
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Environment(EnvType.CLIENT)
final class MetalCrossShaderCompiler {
    private static final String IRIS_SSBO_DESCRIPTOR_PREFIX = "iris_ssbo/";
    private static final Set<String> BUILT_IN_UNIFORMS = Set.of("Projection", "Lighting", "Fog", "Globals");
    private static final int MSL_VERSION_4_0 = 0x040000;
    static final Pattern VERTEX_ENTRY_PATTERN = Pattern.compile("\\bvertex\\s+\\w+\\s+(\\w+)\\s*\\(");
    static final Pattern FRAGMENT_ENTRY_PATTERN = Pattern.compile("\\bfragment\\s+\\w+\\s+(\\w+)\\s*\\(");
    /** 未连接 varying 的一次性告警去重（pipeline+变量名）。 */
    private static final Set<String> UNLINKED_VARYING_REPORTS = ConcurrentHashMap.newKeySet();
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

    private enum RasterStorageKind {
        BUFFER,
        IMAGE
    }

    private record RasterStorageUse(
            RasterStorageKind kind,
            String resourceName,
            String descriptorName,
            int logicalBinding,
            int stageMask,
            ByteBuffer spirv,
            int bindingWordOffset
    ) {
    }

    private record RasterStorageResource(
            RasterStorageKind kind,
            String descriptorName,
            int physicalBinding,
            int stageMask
    ) {
    }

    static String storageBufferDescriptorName(final int logicalBinding, final String resourceName) {
        if (logicalBinding < 0) {
            throw new IllegalArgumentException("SSBO binding must be non-negative: " + logicalBinding);
        }
        return IRIS_SSBO_DESCRIPTOR_PREFIX + logicalBinding + '/' + resourceName;
    }

    static int storageBufferLogicalBinding(final String descriptorName) {
        if (!descriptorName.startsWith(IRIS_SSBO_DESCRIPTOR_PREFIX)) {
            return -1;
        }
        int start = IRIS_SSBO_DESCRIPTOR_PREFIX.length();
        int end = descriptorName.indexOf('/', start);
        if (end < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(descriptorName.substring(start, end));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    record CacheLookup(
            @Nullable MetalMslDiskCache diskCache,
            @Nullable String cacheKey,
            MetalMslDiskCache.@Nullable Entry cached,
            float sampleLodBias
    ) {
    }

    /**
     * Performs only stable-source preparation, cache-key hashing and disk JSON
     * lookup. It deliberately does not create Metal functions/PSOs and does not
     * mutate hit/miss telemetry until a locked compile actually consumes it.
     */
    static CacheLookup tryLoadCacheLookup(
            final RenderPipeline pipeline, final ShaderSource shaderSource
    ) {
        float sampleLodBias = MetalFxManager.shaderSampleLodBias();
        MetalMslDiskCache diskCache = MetalMslDiskCache.instance();
        if (diskCache == null) {
            return new CacheLookup(null, null, null, sampleLodBias);
        }
        String rawVertex = shaderSource.get(pipeline.getVertexShader(), ShaderType.VERTEX);
        String rawFragment = shaderSource.get(pipeline.getFragmentShader(), ShaderType.FRAGMENT);
        if (rawVertex == null || rawFragment == null) {
            return new CacheLookup(diskCache, null, null, sampleLodBias);
        }
        String cacheKey = MetalMslDiskCache.key(
                MetalDevice.prepareShaderSource(rawVertex, pipeline.getShaderDefines()),
                MetalDevice.prepareShaderSource(rawFragment, pipeline.getShaderDefines()),
                // explicitFragmentOutputLocations parses the raw comment-carrying text.
                rawFragment,
                vertexFormatSignature(pipeline),
                bindGroupSignature(pipeline),
                Integer.toHexString(Float.floatToIntBits(sampleLodBias)),
                MetalMslDiskCache.CACHE_SALT
        );
        return new CacheLookup(diskCache, cacheKey, diskCache.load(cacheKey), sampleLodBias);
    }

    static MetalCompiledRenderPipeline compile(
            final MetalDevice device, final RenderPipeline pipeline, final ShaderSource shaderSource
    ) {
        return compile(device, pipeline, shaderSource, null);
    }

    static MetalCompiledRenderPipeline compile(
            final MetalDevice device,
            final RenderPipeline pipeline,
            final ShaderSource shaderSource,
            @Nullable final CacheLookup preloadedLookup
    ) {
        try {
            CacheLookup lookup = preloadedLookup;
            float currentLodBias = MetalFxManager.shaderSampleLodBias();
            if (lookup == null
                    || Float.floatToIntBits(lookup.sampleLodBias()) != Float.floatToIntBits(currentLodBias)) {
                lookup = tryLoadCacheLookup(pipeline, shaderSource);
            }
            float sampleLodBias = lookup.sampleLodBias();
            MetalMslDiskCache diskCache = lookup.diskCache();
            String cacheKey = lookup.cacheKey();
            MetalMslDiskCache.Entry cached = lookup.cached();
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
                        cached.resources(),
                        cached.genericVertexInputs()
                );
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
            List<RasterStorageResource> storageResources = rebindRasterStorageResources(
                    vertexSpirv, fragmentSpirv, layoutEntries.size()
            );
            List<String> vertexOutputs = extractVariableNames(vertexSpirv.outputs());
            VaryingLayout varyings = relocateVertexOutputs(vertexSpirv);

            VertexInputLayout vertexInputs = vertexInputLayout(pipeline, vertexSpirv.inputs());
            rebind(
                    vertexSpirv,
                    tolerateUnprovidedInputs(vertexInputs.names(), vertexSpirv.inputs()),
                    layoutEntries
            );
            applyVertexInputLocations(vertexSpirv, vertexInputs);
            List<GenericVertexInput> genericVertexInputs = genericVertexInputs(
                    vertexSpirv.spirv(), vertexInputs.names()
            );
            MslShader vertexMsl = spirvToMsl(
                    vertexSpirv.spirv(),
                    layoutEntries.size() + storageResources.size(),
                    vertexInputs.formats(),
                    Map.of()
            );

            rebind(
                    fragmentSpirv,
                    tolerateUnprovidedInputs(vertexOutputs, fragmentSpirv.inputs()),
                    layoutEntries
            );
            relocateFragmentInputs(pipeline, fragmentSpirv, varyings);
            String fragmentSource = shaderSource.get(pipeline.getFragmentShader(), ShaderType.FRAGMENT);
            MslShader fragmentMsl = spirvToMsl(
                    fragmentSpirv.spirv(),
                    layoutEntries.size() + storageResources.size(),
                    Map.of(),
                    explicitFragmentOutputLocations(fragmentSource)
            );
            validateFragmentOutputSignature(pipeline, fragmentMsl.stageOutputLocations());
            String fragmentMslSource = applySampleLodBias(
                    fragmentMsl.source(),
                    sampleLodBias
            );

            String vertexEntryPoint = extractEntryPoint(vertexMsl.source(), VERTEX_ENTRY_PATTERN, "main0");
            String fragmentEntryPoint = extractEntryPoint(fragmentMslSource, FRAGMENT_ENTRY_PATTERN, "main0");
            if ("1".equals(System.getenv("METALLUM_MRT_ABI_DEBUG"))) {
                System.err.printf(
                        "[Metallum] MRT diagnostic for %s fragment entry %s:%n%s%n",
                        pipeline.getLocation(), fragmentEntryPoint, fragmentMslSource
                );
            }
            List<MetalCompiledRenderPipeline.ResourceBinding> resources = buildResourceBindings(
                    layoutEntries, storageResources, vertexMsl, fragmentMsl
            );
            MetalMslDiskCache.recordMiss(System.nanoTime() - translateStart);
            if (cacheKey != null && diskCache != null) {
                diskCache.store(cacheKey, new MetalMslDiskCache.Entry(
                        vertexMsl.source(), fragmentMslSource, vertexEntryPoint, fragmentEntryPoint,
                        resources, genericVertexInputs
                ));
            }
            return new MetalCompiledRenderPipeline(
                    device,
                    pipeline,
                    vertexMsl.source(),
                    fragmentMslSource,
                    vertexEntryPoint,
                    fragmentEntryPoint,
                    resources,
                    genericVertexInputs
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
                if (dimensions == Spv.SpvDimBuffer || dimensions == Spv.SpvDimSubpassData) {
                    throw new ShaderCompileException(
                            "Sampled texture (" + name + ") has unsupported SPIR-V dimension " + dimensions
                    );
                }
                addBindingIfAbsent(entries, VulkanBindGroupEntryType.SAMPLED_IMAGE, name, null);
            }
        }
    }

    /**
     * Mojang's rebind helper rejects every sampled image except 2D/Cube.
     * Preserve its exact location/binding rewrite and missing-resource checks,
     * while allowing the additional sampled dimensions exposed by fixed Iris.
     */
    private static void rebind(
            final IntermediaryShaderModule shader,
            final List<String> providedInputs,
            final List<VulkanBindGroupLayout.Entry> entries
    ) throws ShaderCompileException {
        boolean needsExtendedDimensions = shader.samplers().stream().anyMatch(sampler ->
                sampler.dimensions() != Spv.SpvDim2D
                        && sampler.dimensions() != Spv.SpvDimCube
                        && sampler.dimensions() != Spv.SpvDimBuffer
        );
        if (!needsExtendedDimensions) {
            shader.rebind(providedInputs, entries);
            return;
        }
        IntBuffer spirv = shader.spirv().asIntBuffer();
        Set<String> missingInputs = new HashSet<>();
        Set<String> missingSamplers = new HashSet<>();
        Set<String> missingUniforms = new HashSet<>();
        shader.inputs().forEach(input -> missingInputs.add(input.name()));
        shader.samplers().forEach(sampler -> missingSamplers.add(sampler.name()));
        shader.uniformBuffers().forEach(uniform -> missingUniforms.add(uniform.name()));

        String previous = null;
        int location = 0;
        for (String name : providedInputs) {
            SpvVariable input = shader.inputs().stream()
                    .filter(candidate -> candidate.name().equals(name))
                    .findFirst()
                    .orElse(null);
            if (input != null) {
                if (!name.equals(previous)) {
                    spirv.put(input.locationOffset(), location);
                    missingInputs.remove(name);
                }
                location++;
                previous = name;
            }
        }

        for (int binding = 0; binding < entries.size(); binding++) {
            int bindingIndex = binding;
            VulkanBindGroupLayout.Entry entry = entries.get(binding);
            switch (entry.type()) {
                case UNIFORM_BUFFER -> shader.uniformBuffers().stream()
                        .filter(candidate -> candidate.name().equals(entry.name()))
                        .findFirst()
                        .ifPresent(uniform -> {
                            spirv.put(uniform.bindingOffset(), bindingIndex);
                            missingUniforms.remove(entry.name());
                        });
                case SAMPLED_IMAGE -> shader.samplers().stream()
                        .filter(candidate -> candidate.name().equals(entry.name()))
                        .findFirst()
                        .ifPresent(sampler -> {
                            if (sampler.dimensions() == Spv.SpvDimBuffer
                                    || sampler.dimensions() == Spv.SpvDimSubpassData) {
                                throw new IllegalArgumentException(
                                        "Sampler " + entry.name() + " is not a sampled texture dimension: "
                                                + sampler.dimensions()
                                );
                            }
                            spirv.put(sampler.bindingOffset(), bindingIndex);
                            missingSamplers.remove(entry.name());
                        });
                case TEXEL_BUFFER -> shader.samplers().stream()
                        .filter(candidate -> candidate.name().equals(entry.name()))
                        .findFirst()
                        .ifPresent(sampler -> {
                            if (sampler.dimensions() != Spv.SpvDimBuffer) {
                                throw new IllegalArgumentException(
                                        "Texel buffer " + entry.name() + " has SPIR-V dimension "
                                                + sampler.dimensions()
                                );
                            }
                            spirv.put(sampler.bindingOffset(), bindingIndex);
                            missingSamplers.remove(entry.name());
                        });
            }
        }

        if (!missingInputs.isEmpty()) {
            throw new ShaderCompileException("Missing inputs " + missingInputs);
        }
        if (!missingUniforms.isEmpty()) {
            throw new ShaderCompileException("Missing uniform buffers " + missingUniforms);
        }
        if (!missingSamplers.isEmpty()) {
            throw new ShaderCompileException("Missing samplers " + missingSamplers);
        }
    }

    /**
     * Mojang's intermediary module only exposes UBOs and sampled images. Iris
     * raster shaders also use SSBOs and storage images, so reflect those from
     * the same SPIR-V and assign collision-free Metal slots before MSL export.
     */
    private static List<RasterStorageResource> rebindRasterStorageResources(
            final IntermediaryShaderModule vertex,
            final IntermediaryShaderModule fragment,
            final int firstPhysicalBinding
    ) throws ShaderCompileException {
        List<RasterStorageUse> uses = new ArrayList<>();
        collectRasterStorageUses(vertex.spirv(), MetalCompiledRenderPipeline.STAGE_VERTEX, uses);
        collectRasterStorageUses(fragment.spirv(), MetalCompiledRenderPipeline.STAGE_FRAGMENT, uses);
        if (uses.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> physicalByDescriptor = new LinkedHashMap<>();
        Map<String, Integer> stagesByDescriptor = new LinkedHashMap<>();
        Map<String, RasterStorageKind> kindByDescriptor = new LinkedHashMap<>();
        for (RasterStorageUse use : uses) {
            int physical = physicalByDescriptor.computeIfAbsent(
                    use.descriptorName(), ignored -> firstPhysicalBinding + physicalByDescriptor.size()
            );
            RasterStorageKind previousKind = kindByDescriptor.putIfAbsent(use.descriptorName(), use.kind());
            if (previousKind != null && previousKind != use.kind()) {
                throw new ShaderCompileException(
                        "Raster resource '" + use.descriptorName() + "' is both "
                                + previousKind + " and " + use.kind()
                );
            }
            stagesByDescriptor.merge(use.descriptorName(), use.stageMask(), (left, right) -> left | right);
            use.spirv().asIntBuffer().put(use.bindingWordOffset(), physical);
        }

        List<RasterStorageResource> resources = new ArrayList<>(physicalByDescriptor.size());
        physicalByDescriptor.forEach((descriptor, physical) -> resources.add(new RasterStorageResource(
                kindByDescriptor.get(descriptor), descriptor, physical, stagesByDescriptor.get(descriptor)
        )));
        return List.copyOf(resources);
    }

    private static void collectRasterStorageUses(
            final ByteBuffer spirv,
            final int stageMask,
            final List<RasterStorageUse> output
    ) throws ShaderCompileException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer words = spirv.asIntBuffer();
            PointerBuffer pointer = stack.callocPointer(1);
            checkSpvc(Spvc.spvc_context_create(pointer), "spvc_context_create(raster storage)");
            long context = pointer.get(0);
            try {
                checkSpvc(
                        Spvc.spvc_context_parse_spirv(context, words, words.remaining(), pointer),
                        "spvc_context_parse_spirv(raster storage)"
                );
                long ir = pointer.get(0);
                checkSpvc(
                        Spvc.spvc_context_create_compiler(
                                context, Spvc.SPVC_BACKEND_NONE, ir,
                                Spvc.SPVC_CAPTURE_MODE_COPY, pointer
                        ),
                        "spvc_context_create_compiler(raster storage)"
                );
                long compiler = pointer.get(0);
                checkSpvc(
                        Spvc.spvc_compiler_create_shader_resources(compiler, pointer),
                        "spvc_compiler_create_shader_resources(raster storage)"
                );
                long resources = pointer.get(0);
                collectRasterStorageType(
                        stack, compiler, resources, spirv, stageMask,
                        Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER, RasterStorageKind.BUFFER, output
                );
                collectRasterStorageType(
                        stack, compiler, resources, spirv, stageMask,
                        Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE, RasterStorageKind.IMAGE, output
                );
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    private static void collectRasterStorageType(
            final MemoryStack stack,
            final long compiler,
            final long resources,
            final ByteBuffer spirv,
            final int stageMask,
            final int resourceType,
            final RasterStorageKind kind,
            final List<RasterStorageUse> output
    ) throws ShaderCompileException {
        PointerBuffer listPointer = stack.callocPointer(1);
        PointerBuffer countPointer = stack.callocPointer(1);
        checkSpvc(
                Spvc.spvc_resources_get_resource_list_for_type(
                        resources, resourceType, listPointer, countPointer
                ),
                "spvc_resources_get_resource_list_for_type(raster storage " + resourceType + ')'
        );
        int count = Math.toIntExact(countPointer.get(0));
        if (count == 0) {
            return;
        }
        SpvcReflectedResource.Buffer reflected = SpvcReflectedResource.create(listPointer.get(0), count);
        IntBuffer offset = stack.callocInt(1);
        for (SpvcReflectedResource resource : reflected) {
            if (!Spvc.spvc_compiler_has_decoration(compiler, resource.id(), Spv.SpvDecorationBinding)) {
                throw new ShaderCompileException(
                        "Raster storage resource '" + resource.nameString() + "' has no binding"
                );
            }
            if (!Spvc.spvc_compiler_get_binary_offset_for_decoration(
                    compiler, resource.id(), Spv.SpvDecorationBinding, offset
            )) {
                throw new ShaderCompileException(
                        "Could not locate raster storage binding for '" + resource.nameString() + "'"
                );
            }
            int logicalBinding = Spvc.spvc_compiler_get_decoration(
                    compiler, resource.id(), Spv.SpvDecorationBinding
            );
            String resourceName = resource.nameString();
            if (resourceName == null || resourceName.isBlank()) {
                resourceName = "binding" + logicalBinding;
            }
            if (kind == RasterStorageKind.IMAGE) {
                long type = Spvc.spvc_compiler_get_type_handle(compiler, resource.type_id());
                int dimension = Spvc.spvc_type_get_image_dimension(type);
                if (dimension != Spv.SpvDim2D) {
                    throw new ShaderCompileException(
                            "Raster storage image '" + resourceName + "' is not 2D (SPIR-V dim="
                                    + dimension + ')'
                    );
                }
            }
            String descriptorName = kind == RasterStorageKind.BUFFER
                    ? storageBufferDescriptorName(logicalBinding, resourceName)
                    : resourceName;
            output.add(new RasterStorageUse(
                    kind, resourceName, descriptorName, logicalBinding,
                    stageMask, spirv, offset.get(0)
            ));
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

    static List<String> tolerateUnprovidedInputs(final List<String> provided, final List<SpvVariable> shaderInputs) {
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

    /**
     * varying 的 location 分配结果：名字 → 起始 location，外加下一个空闲 location。
     * 由 vertex 输出侧算出，fragment 输入侧照此对齐。
     */
    private static final class VaryingLayout {
        private final Map<String, Integer> baseLocations = new LinkedHashMap<>();
        private int nextFree;
    }

    /**
     * 按 location 槽位宽度重排 vertex 输出。
     *
     * <p><b>根因</b>：库存链在给 stage 接口变量分配 location 时是“一个变量一个 location”，
     * 不计类型占用的槽数。原版着色器的 varying 只有标量/向量，这没有区别；但光影包会声明
     * 矩阵 varying（Potato 的 {@code out mat2 coord} / {@code flat out mat4x3 colorPalette}），
     * 矩阵按列各占一个 location（mat4x3 占 4 个、mat2 占 2 个），数组按元素同理。于是
     * {@code colorPalette} 拿到 location 0 却实际占用 0..3，紧随其后的变量就落进它的区间，
     * SPIRV-Cross 产出的 MSL 里出现重复的 {@code [[user(locnN)]]}，MTLLibrary 编译直接失败
     * （"duplicated user-defined name 'locnN'"）。{@link IntermediaryShaderModule#rebind}
     * 只改写 inputs，不触碰 outputs，因此 vertex 输出必须由我们自己重排。
     *
     * <p>做法：按当前 location 升序遍历（保持库存链的相对顺序，结果稳定可复现），逐个分配
     * 起始 location 并按该变量的实际槽数推进游标。location 编号对外没有契约，只要 vertex
     * 输出与 fragment 输入两侧一致即可，因此可以自由紧密重排。
     */
    private static VaryingLayout relocateVertexOutputs(final IntermediaryShaderModule vertex)
            throws ShaderCompileException {
        VaryingLayout layout = new VaryingLayout();
        List<SpvVariable> outputs = vertex.outputs();
        if (outputs.isEmpty()) {
            return layout;
        }
        Map<String, Integer> spans = varyingLocationSpans(vertex.spirv(), Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT);
        IntBuffer words = vertex.spirv().asIntBuffer();
        for (SpvVariable output : sortByCurrentLocation(words, outputs)) {
            Integer base = layout.baseLocations.get(output.name());
            if (base == null) {
                base = layout.nextFree;
                layout.baseLocations.put(output.name(), base);
                layout.nextFree += spans.getOrDefault(output.name(), 1);
            }
            words.put(output.locationOffset(), base);
        }
        return layout;
    }

    /**
     * 把 fragment 输入的 location 对齐到 {@link #relocateVertexOutputs} 给出的同名起始
     * location。必须在 {@code fragment.rebind(...)} 之后调用——rebind 会按“一个名字一个
     * location”重编 fragment 输入，正是这一步引入了与多槽位 varying 的重叠。
     *
     * <p>没有同名 vertex 输出的 fragment 输入（未连接的 varying，读到的是未定义值）排在
     * vertex 输出区之后，保证不与已分配区间重叠，并按 pipeline+变量名去重告警一次。
     */
    private static void relocateFragmentInputs(
            final RenderPipeline pipeline,
            final IntermediaryShaderModule fragment,
            final VaryingLayout layout
    ) throws ShaderCompileException {
        List<SpvVariable> inputs = fragment.inputs();
        if (inputs.isEmpty()) {
            return;
        }
        Map<String, Integer> spans = varyingLocationSpans(fragment.spirv(), Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT);
        IntBuffer words = fragment.spirv().asIntBuffer();
        for (SpvVariable input : sortByCurrentLocation(words, inputs)) {
            Integer base = layout.baseLocations.get(input.name());
            if (base == null) {
                base = layout.nextFree;
                layout.baseLocations.put(input.name(), base);
                layout.nextFree += spans.getOrDefault(input.name(), 1);
                if (UNLINKED_VARYING_REPORTS.add(pipeline.getLocation() + "/" + input.name())) {
                    Metallum.LOGGER.warn(
                            "[Metallum] Fragment input '{}' of pipeline {} has no matching vertex output; "
                                    + "assigned location {} (reads undefined values)",
                            input.name(), pipeline.getLocation(), base
                    );
                }
            }
            words.put(input.locationOffset(), base);
        }
    }

    /**
     * 按变量当前的 Location 装饰值升序排列。{@link SpvVariable#locationOffset()} 是该装饰
     * 字面量在 SPIR-V 字流中的字下标；先把排序键取出来再排，避免排序过程中读到被改写的值。
     */
    private static List<SpvVariable> sortByCurrentLocation(final IntBuffer words, final List<SpvVariable> variables) {
        Map<SpvVariable, Integer> keys = new IdentityHashMap<>(variables.size());
        for (SpvVariable variable : variables) {
            keys.put(variable, words.get(variable.locationOffset()));
        }
        List<SpvVariable> sorted = new ArrayList<>(variables);
        sorted.sort(Comparator.comparingInt(keys::get));
        return sorted;
    }

    /**
     * 反射一个 SPIR-V 模块的 stage 输入/输出，算出每个变量占用的 location 槽数。
     *
     * <p>规则（GLSL/SPIR-V location 分配）：向量与标量占 1 个槽，但 64 位类型（double/int64）
     * 超过 2 个分量时占 2 个；矩阵按列数倍增；数组按元素总数倍增。
     */
    private static Map<String, Integer> varyingLocationSpans(final ByteBuffer spirvBytes, final int resourceType)
            throws ShaderCompileException {
        Map<String, Integer> spans = new LinkedHashMap<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer spirvWords = spirvBytes.asIntBuffer();
            PointerBuffer pContext = stack.mallocPointer(1);
            checkSpvc(Spvc.spvc_context_create(pContext), "spvc_context_create");
            long context = pContext.get(0);
            try {
                PointerBuffer pIr = stack.mallocPointer(1);
                checkSpvc(
                        Spvc.spvc_context_parse_spirv(context, spirvWords, spirvWords.remaining(), pIr),
                        "spvc_context_parse_spirv"
                );
                PointerBuffer pCompiler = stack.mallocPointer(1);
                checkSpvc(
                        Spvc.spvc_context_create_compiler(
                                context, Spvc.SPVC_BACKEND_NONE, pIr.get(0), Spvc.SPVC_CAPTURE_MODE_COPY, pCompiler
                        ),
                        "spvc_context_create_compiler"
                );
                long compiler = pCompiler.get(0);

                PointerBuffer pResources = stack.mallocPointer(1);
                checkSpvc(
                        Spvc.spvc_compiler_create_shader_resources(compiler, pResources),
                        "spvc_compiler_create_shader_resources"
                );
                PointerBuffer pList = stack.mallocPointer(1);
                PointerBuffer pCount = stack.mallocPointer(1);
                checkSpvc(
                        Spvc.spvc_resources_get_resource_list_for_type(pResources.get(0), resourceType, pList, pCount),
                        "spvc_resources_get_resource_list_for_type"
                );
                SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(pList.get(0), (int) pCount.get(0));
                for (SpvcReflectedResource resource : list) {
                    long type = Spvc.spvc_compiler_get_type_handle(compiler, resource.type_id());
                    spans.put(resource.nameString(), locationSpan(type));
                }
                return spans;
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    private static int locationSpan(final long type) {
        int basetype = Spvc.spvc_type_get_basetype(type);
        int components = Spvc.spvc_type_get_vector_size(type);
        boolean wide = basetype == Spvc.SPVC_BASETYPE_FP64
                || basetype == Spvc.SPVC_BASETYPE_INT64
                || basetype == Spvc.SPVC_BASETYPE_UINT64;
        int perColumn = wide && components > 2 ? 2 : 1;
        int span = perColumn * Math.max(1, Spvc.spvc_type_get_columns(type));
        int dimensions = Spvc.spvc_type_get_num_array_dimensions(type);
        for (int index = 0; index < dimensions; index++) {
            // 长度为 0 表示 runtime array / spec-constant 长度，varying 上不会出现；保守按 1 计。
            span *= Math.max(1, Spvc.spvc_type_get_array_dimension(type, index));
        }
        return span;
    }

    static String extractEntryPoint(final String msl, final Pattern pattern, final String fallback) {
        Matcher matcher = pattern.matcher(msl);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    static List<MetalCompiledRenderPipeline.ResourceBinding> buildResourceBindings(
            final List<VulkanBindGroupLayout.Entry> entries,
            final List<RasterStorageResource> storageResources,
            final MslShader vertexMsl,
            final MslShader fragmentMsl
    ) {
        List<MetalCompiledRenderPipeline.ResourceBinding> resources =
                new ArrayList<>(entries.size() + storageResources.size() + 1);
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

        for (RasterStorageResource storage : storageResources) {
            MetalCompiledRenderPipeline.ResourceKind kind = switch (storage.kind()) {
                case BUFFER -> MetalCompiledRenderPipeline.ResourceKind.STORAGE_BUFFER;
                case IMAGE -> MetalCompiledRenderPipeline.ResourceKind.STORAGE_IMAGE;
            };
            resources.add(new MetalCompiledRenderPipeline.ResourceBinding(
                    kind,
                    storage.descriptorName(),
                    storage.physicalBinding(),
                    storage.stageMask(),
                    null
            ));
        }

        int pushConstantStageMask = (vertexMsl.hasPushConstants() ? MetalCompiledRenderPipeline.STAGE_VERTEX : 0)
                | (fragmentMsl.hasPushConstants() ? MetalCompiledRenderPipeline.STAGE_FRAGMENT : 0);
        if (pushConstantStageMask != 0) {
            resources.add(new MetalCompiledRenderPipeline.ResourceBinding(
                    MetalCompiledRenderPipeline.ResourceKind.UNIFORM_BUFFER,
                    "push_constants",
                    entries.size() + storageResources.size(),
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

    // Package-private: the Iris terrain-override lane reuses it.
    static Map<String, GpuFormat> vertexAttributeFormats(final RenderPipeline pipeline) {
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

    /**
     * Resolves shader input names onto the ordered physical vertex layout.
     * Iris's vanilla transformer renames Mojang semantics such as
     * {@code Position} to {@code iris_Position}; the vertex descriptor still
     * uses the original physical order and formats. Both the SPIR-V location
     * rebinding and SPIRV-Cross integer conversion metadata must therefore use
     * the same resolved name.
     */
    static VertexInputLayout vertexInputLayout(
            final RenderPipeline pipeline,
            final List<SpvVariable> shaderInputs
    ) {
        Set<String> shaderNames = new HashSet<>();
        for (SpvVariable input : shaderInputs) {
            shaderNames.add(input.name());
        }

        List<String> physicalNames = MetalPipelineSupport.vertexAttributeNames(pipeline);
        Set<String> physicalNameSet = new HashSet<>(physicalNames);
        List<String> resolvedNames = new ArrayList<>(physicalNames.size());
        Map<String, GpuFormat> resolvedFormats = new LinkedHashMap<>();

        for (VertexFormat binding : pipeline.getVertexFormatBindings()) {
            if (binding == null) {
                continue;
            }
            for (VertexFormatElement element : binding.getElements()) {
                String physicalName = element.name();
                String resolvedName = physicalName;
                String irisAlias = "iris_" + physicalName;
                if (!shaderNames.contains(physicalName)
                        && shaderNames.contains(irisAlias)
                        && !physicalNameSet.contains(irisAlias)) {
                    resolvedName = irisAlias;
                }
                resolvedNames.add(resolvedName);
                resolvedFormats.putIfAbsent(resolvedName, element.format());
            }
        }

        return new VertexInputLayout(List.copyOf(resolvedNames), Map.copyOf(resolvedFormats));
    }

    record VertexInputLayout(List<String> names, Map<String, GpuFormat> formats) {
    }

    /**
     * Keeps shader locations aligned with the complete physical descriptor.
     * Mojang's rebind helper only advances for inputs declared by the shader,
     * while Metal's descriptor retains unused elements from the VertexFormat.
     * Generic-current inputs therefore start after every physical element,
     * not merely after the subset active in this shader.
     */
    static void applyVertexInputLocations(
            final IntermediaryShaderModule shader,
            final VertexInputLayout physicalInputs
    ) {
        Map<String, Integer> physicalLocations = new HashMap<>();
        for (int location = 0; location < physicalInputs.names().size(); location++) {
            physicalLocations.putIfAbsent(physicalInputs.names().get(location), location);
        }

        IntBuffer words = shader.spirv().asIntBuffer();
        int genericLocation = physicalInputs.names().size();
        for (SpvVariable input : shader.inputs()) {
            Integer physicalLocation = physicalLocations.get(input.name());
            words.put(input.locationOffset(), physicalLocation == null ? genericLocation++ : physicalLocation);
        }
    }

    enum BaseType {
        FLOAT(0),
        INT(16),
        UINT(32);

        private final int defaultValueOffset;

        BaseType(final int defaultValueOffset) {
            this.defaultValueOffset = defaultValueOffset;
        }

        int defaultValueOffset() {
            return this.defaultValueOffset;
        }
    }

    /**
     * One active vertex input that has no backing element in the pipeline's
     * physical vertex formats. Its location is the final location after
     * {@link IntermediaryShaderModule#rebind(List, List)}.
     */
    record GenericVertexInput(int location, BaseType baseType, int components) {
        GenericVertexInput {
            if (location < 0) {
                throw new IllegalArgumentException("Generic vertex input location must be non-negative");
            }
            Objects.requireNonNull(baseType, "baseType");
            if (components < 1 || components > 4) {
                throw new IllegalArgumentException("Generic vertex input components must be in 1..4");
            }
        }

        MTLVertexFormat metalFormat() {
            return switch (baseType) {
                case FLOAT -> switch (components) {
                    case 1 -> MTLVertexFormat.Float;
                    case 2 -> MTLVertexFormat.Float2;
                    case 3 -> MTLVertexFormat.Float3;
                    case 4 -> MTLVertexFormat.Float4;
                    default -> throw new AssertionError(components);
                };
                case INT -> switch (components) {
                    case 1 -> MTLVertexFormat.Int;
                    case 2 -> MTLVertexFormat.Int2;
                    case 3 -> MTLVertexFormat.Int3;
                    case 4 -> MTLVertexFormat.Int4;
                    default -> throw new AssertionError(components);
                };
                case UINT -> switch (components) {
                    case 1 -> MTLVertexFormat.UInt;
                    case 2 -> MTLVertexFormat.UInt2;
                    case 3 -> MTLVertexFormat.UInt3;
                    case 4 -> MTLVertexFormat.UInt4;
                    default -> throw new AssertionError(components);
                };
            };
        }

        int defaultValueOffset() {
            return baseType.defaultValueOffset();
        }
    }

    static final int GENERIC_VERTEX_DEFAULT_VALUES_SIZE = 48;

    /** Writes float, signed-int and unsigned-int representations of GL's (0,0,0,1) default. */
    static void writeGenericVertexDefaultValues(final ByteBuffer destination) {
        if (destination.remaining() < GENERIC_VERTEX_DEFAULT_VALUES_SIZE) {
            throw new IllegalArgumentException(
                    "Generic vertex default buffer requires " + GENERIC_VERTEX_DEFAULT_VALUES_SIZE + " bytes"
            );
        }
        ByteBuffer values = destination.duplicate().order(ByteOrder.nativeOrder());
        int start = values.position();
        for (int index = 0; index < GENERIC_VERTEX_DEFAULT_VALUES_SIZE; index++) {
            values.put(start + index, (byte) 0);
        }
        values.putFloat(start + BaseType.FLOAT.defaultValueOffset() + 12, 1.0F);
        values.putInt(start + BaseType.INT.defaultValueOffset() + 12, 1);
        values.putInt(start + BaseType.UINT.defaultValueOffset() + 12, 1);
    }

    /**
     * Reflects active stage inputs after location rebinding and returns only
     * those not supplied by a physical vertex format. Metal must describe and
     * bind these inputs even though Mojang's pipeline has no backing element.
     */
    static List<GenericVertexInput> genericVertexInputs(
            final ByteBuffer spirvBytes,
            final List<String> physicalInputNames
    ) throws ShaderCompileException {
        Set<String> physicalInputs = Set.copyOf(physicalInputNames);
        List<GenericVertexInput> result = new ArrayList<>();
        Map<Integer, String> namesByLocation = new HashMap<>();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer spirvWords = spirvBytes.asIntBuffer();
            if (spirvWords.remaining() < 5) {
                throw new ShaderCompileException("SPIR-V is too small to reflect generic vertex inputs");
            }

            PointerBuffer pContext = stack.mallocPointer(1);
            checkSpvc(Spvc.spvc_context_create(pContext), "spvc_context_create(generic vertex inputs)");
            long context = pContext.get(0);
            try {
                PointerBuffer pIr = stack.mallocPointer(1);
                checkSpvc(
                        Spvc.spvc_context_parse_spirv(context, spirvWords, spirvWords.remaining(), pIr),
                        "spvc_context_parse_spirv(generic vertex inputs)"
                );
                PointerBuffer pCompiler = stack.mallocPointer(1);
                checkSpvc(
                        Spvc.spvc_context_create_compiler(
                                context, Spvc.SPVC_BACKEND_NONE, pIr.get(0),
                                Spvc.SPVC_CAPTURE_MODE_COPY, pCompiler
                        ),
                        "spvc_context_create_compiler(generic vertex inputs)"
                );
                long compiler = pCompiler.get(0);

                PointerBuffer pActiveSet = stack.mallocPointer(1);
                checkSpvc(
                        Spvc.spvc_compiler_get_active_interface_variables(compiler, pActiveSet),
                        "spvc_compiler_get_active_interface_variables(generic vertex inputs)"
                );
                PointerBuffer pResources = stack.mallocPointer(1);
                checkSpvc(
                        Spvc.spvc_compiler_create_shader_resources_for_active_variables(
                                compiler, pResources, pActiveSet.get(0)
                        ),
                        "spvc_compiler_create_shader_resources_for_active_variables(generic vertex inputs)"
                );
                PointerBuffer pList = stack.mallocPointer(1);
                PointerBuffer pCount = stack.mallocPointer(1);
                checkSpvc(
                        Spvc.spvc_resources_get_resource_list_for_type(
                                pResources.get(0), Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT, pList, pCount
                        ),
                        "spvc_resources_get_resource_list_for_type(STAGE_INPUT generic vertex inputs)"
                );

                int count = Math.toIntExact(pCount.get(0));
                if (count == 0) {
                    return List.of();
                }
                SpvcReflectedResource.Buffer inputs = SpvcReflectedResource.create(pList.get(0), count);
                for (int index = 0; index < count; index++) {
                    SpvcReflectedResource input = inputs.get(index);
                    String name = input.nameString();
                    if (physicalInputs.contains(name)
                            || Spvc.spvc_compiler_has_decoration(compiler, input.id(), Spv.SpvDecorationBuiltIn)) {
                        continue;
                    }
                    if (!Spvc.spvc_compiler_has_decoration(compiler, input.id(), Spv.SpvDecorationLocation)) {
                        throw new ShaderCompileException(
                                "Active generic vertex input " + name + " has no location"
                        );
                    }

                    long type = Spvc.spvc_compiler_get_type_handle(compiler, input.type_id());
                    int columns = Spvc.spvc_type_get_columns(type);
                    int arrayDimensions = Spvc.spvc_type_get_num_array_dimensions(type);
                    if (columns != 1 || arrayDimensions != 0) {
                        throw new ShaderCompileException(
                                "Unsupported generic vertex input shape for " + name
                                        + ": columns=" + columns + ", arrayDimensions=" + arrayDimensions
                        );
                    }

                    int spvcBaseType = Spvc.spvc_type_get_basetype(type);
                    BaseType baseType = switch (spvcBaseType) {
                        case Spvc.SPVC_BASETYPE_FP32 -> BaseType.FLOAT;
                        case Spvc.SPVC_BASETYPE_INT32 -> BaseType.INT;
                        case Spvc.SPVC_BASETYPE_UINT32 -> BaseType.UINT;
                        default -> throw new ShaderCompileException(
                                "Unsupported generic vertex input base type for " + name + ": " + spvcBaseType
                        );
                    };
                    int components = Spvc.spvc_type_get_vector_size(type);
                    if (components < 1 || components > 4) {
                        throw new ShaderCompileException(
                                "Unsupported generic vertex input vector size for " + name + ": " + components
                        );
                    }

                    int location = Spvc.spvc_compiler_get_decoration(
                            compiler, input.id(), Spv.SpvDecorationLocation
                    );
                    String conflict = namesByLocation.putIfAbsent(location, name);
                    if (conflict != null) {
                        throw new ShaderCompileException(
                                "Generic vertex inputs " + conflict + " and " + name
                                        + " both use location " + location
                        );
                    }
                    result.add(new GenericVertexInput(location, baseType, components));
                }
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }

        result.sort(Comparator.comparingInt(GenericVertexInput::location));
        return List.copyOf(result);
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

    static Map<String, Integer> explicitFragmentOutputLocations(@Nullable final String source)
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

    static void validateFragmentOutputSignature(
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
        if (!targetLocations.containsAll(shaderLocations)) {
            throw new ShaderCompileException(
                    "Fragment output/color-target location mismatch for " + pipeline.getLocation()
                            + ": shader=" + shaderLocations + ", targets=" + targetLocations
            );
        }
    }

    static MslShader spirvToMsl(
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
                boolean hasRectangleSampler = hasSampledImageDimension(
                        stack, compiler, resources, Spv.SpvDimRect
                );

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
                String mslSource = MemoryUtil.memUTF8(pSource.get(0));
                if (hasRectangleSampler) {
                    mslSource = mslSource.replace("unknown_texture_type<", "texture2d<");
                    if (mslSource.contains("unknown_texture_type")) {
                        throw new ShaderCompileException(
                                "SPIRV-Cross emitted an unlowered rectangle texture type"
                        );
                    }
                }
                return new MslShader(
                        mslSource,
                        hasPushConstants,
                        activeResources,
                        stageOutputLocations
                );
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    private static boolean hasSampledImageDimension(
            final MemoryStack stack,
            final long compiler,
            final long resources,
            final int expectedDimension
    ) throws ShaderCompileException {
        PointerBuffer listPointer = stack.mallocPointer(1);
        PointerBuffer countPointer = stack.mallocPointer(1);
        checkSpvc(
                Spvc.spvc_resources_get_resource_list_for_type(
                        resources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, listPointer, countPointer
                ),
                "spvc_resources_get_resource_list_for_type(sampled image dimensions)"
        );
        int count = Math.toIntExact(countPointer.get(0));
        if (count == 0) {
            return false;
        }
        SpvcReflectedResource.Buffer sampled = SpvcReflectedResource.create(listPointer.get(0), count);
        for (SpvcReflectedResource resource : sampled) {
            long type = Spvc.spvc_compiler_get_type_handle(compiler, resource.type_id());
            if (Spvc.spvc_type_get_image_dimension(type) == expectedDimension) {
                return true;
            }
        }
        return false;
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
