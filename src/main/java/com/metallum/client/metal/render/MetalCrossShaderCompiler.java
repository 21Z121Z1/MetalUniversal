package com.metallum.client.metal.render;

import com.metallum.client.metal.render.mtl.MTLVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.util.ShaderCompileException;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.Version;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslShaderInterfaceVar2;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minecraft 26.3 shader translation boundary.
 *
 * <p>RenderPearl owns GLSL preprocessing, ShaderC compilation, descriptor
 * reflection and binding normalization. The Metal backend therefore starts
 * with {@link SpvModule}; it must not import the removed 26.2 Vulkan GLSL
 * frontend classes.</p>
 */
@Environment(EnvType.CLIENT)
final class MetalCrossShaderCompiler {
    private static final int MSL_VERSION_4_0 = 0x040000;
    static final Pattern VERTEX_ENTRY_PATTERN = Pattern.compile("\\bvertex\\s+\\w+\\s+(\\w+)\\s*\\(");
    static final Pattern FRAGMENT_ENTRY_PATTERN = Pattern.compile("\\bfragment\\s+\\w+\\s+(\\w+)\\s*\\(");

    private MetalCrossShaderCompiler() {
    }

    record CacheLookup(@Nullable MetalMslDiskCache diskCache, @Nullable String cacheKey,
                       MetalMslDiskCache.@Nullable Entry cached, float sampleLodBias) {
    }

    /** Source-side cache lookup is not valid after the 26.3 frontend migration. */
    static CacheLookup tryLoadCacheLookup(final RenderPipeline pipeline, final Object ignoredSource) {
        return new CacheLookup(null, null, null, MetalFxManager.shaderSampleLodBias());
    }

    static BackendRenderPipeline.Pending compilePending(
            final MetalDevice device, final BackendRenderPipeline.CreateInfo info
    ) {
        return () -> compile(device, info);
    }

    static MetalCompiledRenderPipeline compile(
            final MetalDevice device, final BackendRenderPipeline.CreateInfo info
    ) {
        ByteBuffer vertexSpirv = null;
        ByteBuffer fragmentSpirv = null;
        try {
            SpvModule vertex = shader(info, ShaderType.VERTEX);
            SpvModule fragment = shader(info, ShaderType.FRAGMENT);
            if (vertex == null || fragment == null) {
                throw new ShaderCompileException("Render pipeline must contain vertex and fragment SPIR-V modules");
            }

            // RenderPearl owns and may reuse/cache SpvModule instances. Descriptor
            // rebinding is a Metal backend concern, so never patch those shared
            // buffers in place. Work on private native copies instead.
            vertexSpirv = mutableSpirvCopy(vertex.spv());
            fragmentSpirv = mutableSpirvCopy(fragment.spv());

            int firstStorageBinding = info.uniforms().size();
            List<RasterStorageResource> storageResources = rebindRasterStorageResources(
                    vertexSpirv, fragmentSpirv, firstStorageBinding
            );
            int pushConstantBinding = firstStorageBinding + storageResources.size();
            float sampleLodBias = MetalFxManager.shaderSampleLodBias();
            RenderPipeline syntheticPipeline = syntheticPipeline(info);

            MetalMslDiskCache diskCache = MetalMslDiskCache.instance();
            String cacheKey = diskCache == null ? null : renderPearlCacheKey(
                    info, vertexSpirv, fragmentSpirv, storageResources, sampleLodBias
            );
            if (diskCache != null && cacheKey != null) {
                MetalMslDiskCache.Entry cached = diskCache.load(cacheKey);
                if (cached != null) {
                    MetalMslDiskCache.recordHit();
                    return new MetalCompiledRenderPipeline(
                            device,
                            syntheticPipeline,
                            cached.vertexMsl(),
                            cached.fragmentMsl(),
                            cached.vertexEntryPoint(),
                            cached.fragmentEntryPoint(),
                            cached.resources(),
                            cached.genericVertexInputs()
                    );
                }
            }

            long translateStart = System.nanoTime();
            MslShader vertexMsl = spirvToMsl(
                    vertexSpirv, pushConstantBinding,
                    vertexAttributeFormats(vertex, info.attribBindings()), Map.of()
            );
            MslShader fragmentMsl = spirvToMsl(
                    fragmentSpirv, pushConstantBinding, Map.of(), Map.of()
            );
            String vertexSource = applySampleLodBias(vertexMsl.source(), sampleLodBias);
            String fragmentSource = applySampleLodBias(fragmentMsl.source(), sampleLodBias);
            validateFragmentOutputSignature(info.colorTargetStates(), fragmentMsl.stageOutputLocations());
            List<MetalCompiledRenderPipeline.ResourceBinding> resources = buildResourceBindings(
                    info.uniforms(), storageResources, vertexMsl, fragmentMsl
            );
            List<GenericVertexInput> genericInputs = genericVertexInputs(vertex.reflect(), info.attribBindings());
            String vertexEntryPoint = extractEntryPoint(vertexSource, VERTEX_ENTRY_PATTERN, "main0");
            String fragmentEntryPoint = extractEntryPoint(fragmentSource, FRAGMENT_ENTRY_PATTERN, "main0");

            if (diskCache != null && cacheKey != null) {
                MetalMslDiskCache.recordMiss(System.nanoTime() - translateStart);
                diskCache.store(cacheKey, new MetalMslDiskCache.Entry(
                        vertexSource,
                        fragmentSource,
                        vertexEntryPoint,
                        fragmentEntryPoint,
                        resources,
                        genericInputs
                ));
            }

            return new MetalCompiledRenderPipeline(
                    device,
                    syntheticPipeline,
                    vertexSource,
                    fragmentSource,
                    vertexEntryPoint,
                    fragmentEntryPoint,
                    resources,
                    genericInputs
            );
        } catch (ShaderCompileException exception) {
            throw new IllegalStateException("Failed to translate RenderPearl pipeline " + info.name(), exception);
        } finally {
            if (vertexSpirv != null) MemoryUtil.memFree(vertexSpirv);
            if (fragmentSpirv != null) MemoryUtil.memFree(fragmentSpirv);
        }
    }

    private static @Nullable SpvModule shader(
            final BackendRenderPipeline.CreateInfo info, final ShaderType type
    ) {
        for (BackendRenderPipeline.CreateInfo.Shader shader : info.shaders()) {
            if (shader.module().type() == type) return shader.module();
        }
        return null;
    }

    /**
     * Returns a mutable native copy without changing the source position,
     * limit, byte order, or contents. Package-private for the ownership test.
     */
    static ByteBuffer mutableSpirvCopy(final ByteBuffer source) {
        ByteBuffer view = source.duplicate();
        ByteBuffer copy = MemoryUtil.memAlloc(view.remaining()).order(source.order());
        copy.put(view);
        copy.flip();
        return copy;
    }

    private static String renderPearlCacheKey(
            final BackendRenderPipeline.CreateInfo info,
            final ByteBuffer vertexSpirv,
            final ByteBuffer fragmentSpirv,
            final List<RasterStorageResource> storageResources,
            final float sampleLodBias
    ) {
        StringBuilder layout = new StringBuilder();
        layout.append("uniforms:");
        for (BindGroupLayout.UniformDescription uniform : info.uniforms()) {
            layout.append(uniform.name()).append('/')
                    .append(uniform.type()).append('/')
                    .append(uniform.gpuFormat()).append(';');
        }
        layout.append("|storage:");
        for (RasterStorageResource storage : storageResources) {
            layout.append(storage.kind()).append('/')
                    .append(storage.descriptorName()).append('/')
                    .append(storage.physicalBinding()).append('/')
                    .append(storage.stageMask()).append(';');
        }
        layout.append("|vertexBuffers:");
        for (BackendRenderPipeline.CreateInfo.VertexBuffer buffer : info.vertexBuffers()) {
            layout.append(buffer.bufferSlot()).append('/')
                    .append(buffer.stride()).append('/')
                    .append(buffer.stepRate()).append(';');
        }
        layout.append("|attribs:");
        for (BackendRenderPipeline.CreateInfo.AttribBinding attribute : info.attribBindings()) {
            layout.append(attribute.location()).append('/')
                    .append(attribute.bufferSlot()).append('/')
                    .append(attribute.offset()).append('/')
                    .append(attribute.format()).append(';');
        }
        layout.append("|targets:");
        List<@Nullable ColorTargetState> targets = info.colorTargetStates();
        for (int index = 0; index < targets.size(); index++) {
            ColorTargetState target = targets.get(index);
            layout.append(index).append('=')
                    .append(target == null ? "unused" : target.format().toString()).append(';');
        }
        layout.append("|pushConstants=").append(info.pushConstantsSize());

        return MetalMslDiskCache.key(
                "vertex-spv=" + spirvDigest(vertexSpirv),
                "fragment-spv=" + spirvDigest(fragmentSpirv),
                layout.toString(),
                "sample-lod-bias=" + Integer.toHexString(Float.floatToIntBits(sampleLodBias)),
                "lwjgl-spvc=" + Version.getVersion(),
                "msl-version=" + Integer.toHexString(MSL_VERSION_4_0),
                MetalMslDiskCache.CACHE_SALT
        );
    }

    private static String spirvDigest(final ByteBuffer bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes.duplicate());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static RenderPipeline syntheticPipeline(final BackendRenderPipeline.CreateInfo info) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(Identifier.parse(info.name()))
                .withCull(info.cull())
                .withPolygonMode(info.polygonMode())
                .withPrimitiveTopology(info.primitiveTopology())
                .withPushConstantSize(info.pushConstantsSize());
        for (BackendRenderPipeline.CreateInfo.Shader shader : info.shaders()) {
            if (shader.module().type() == ShaderType.VERTEX) builder.withVertexShader(Identifier.parse(shader.name()));
            if (shader.module().type() == ShaderType.FRAGMENT) builder.withFragmentShader(Identifier.parse(shader.name()));
        }
        if (!info.uniforms().isEmpty()) {
            builder.withBindGroupLayout(new BindGroupLayout(info.uniforms()));
        }
        if (info.depthStencilState() != null) builder.withDepthStencilState(info.depthStencilState());
        List<@Nullable ColorTargetState> targets = info.colorTargetStates();
        for (int index = 0; index < targets.size(); index++) {
            ColorTargetState target = targets.get(index);
            if (target == null) builder.withUnusedColorTargetState(index);
            else builder.withColorTargetState(index, target);
        }
        for (BackendRenderPipeline.CreateInfo.VertexBuffer vertexBuffer : info.vertexBuffers()) {
            builder.withVertexBinding(
                    vertexBuffer.bufferSlot(),
                    syntheticVertexFormat(info, vertexBuffer)
            );
        }
        return builder.build();
    }

    /**
     * Rebuilds the public VertexFormat used by the backend-only pipeline.
     *
     * <p>The five-argument VertexFormat.Builder overload describes repeated
     * attributes: its third argument is the distance between repeated
     * elements, not the vertex stride. Passing an absolute attribute offset
     * together with the full stride therefore inflated Sodium's 20-byte
     * format to 36 bytes. Supplying the remaining distance to the end of the
     * vertex preserves both the declared offset and the declared stride,
     * including layouts with padding between attributes.</p>
     */
    private static VertexFormat syntheticVertexFormat(
            final BackendRenderPipeline.CreateInfo info,
            final BackendRenderPipeline.CreateInfo.VertexBuffer vertexBuffer
    ) {
        VertexFormat.Builder builder = VertexFormat.builder(vertexBuffer.stepRate());
        for (BackendRenderPipeline.CreateInfo.AttribBinding attribute : info.attribBindings()) {
            if (attribute.bufferSlot() != vertexBuffer.bufferSlot()) {
                continue;
            }
            int offset = attribute.offset();
            if (offset < 0 || offset >= vertexBuffer.stride()) {
                throw new IllegalStateException(
                        "Invalid RenderPearl vertex layout for " + info.name()
                                + " at location " + attribute.location()
                                + ": offset " + offset
                                + " outside stride " + vertexBuffer.stride()
                );
            }
            builder.addAttribute(
                    "attribute" + attribute.location(),
                    offset,
                    vertexBuffer.stride() - offset,
                    attribute.format(),
                    1
            );
        }
        VertexFormat result = builder.build();
        if (result.getVertexSize() != vertexBuffer.stride()) {
            throw new IllegalStateException(
                    "RenderPearl vertex stride mismatch for " + info.name()
                            + ": declared " + vertexBuffer.stride()
                            + ", reconstructed " + result.getVertexSize()
            );
        }
        return result;
    }

    private static Map<String, GpuFormat> vertexAttributeFormats(
            final SpvModule vertex,
            final List<BackendRenderPipeline.CreateInfo.AttribBinding> attribBindings
    ) throws ShaderCompileException {
        Map<Integer, GpuFormat> formatsByLocation = new HashMap<>();
        for (BackendRenderPipeline.CreateInfo.AttribBinding binding : attribBindings) {
            formatsByLocation.put(binding.location(), binding.format());
        }
        Map<String, GpuFormat> result = new HashMap<>();
        for (SpvModule.Reflection.InterfaceVariable input : vertex.reflect().inputs()) {
            GpuFormat format = formatsByLocation.get(input.location());
            if (format != null) result.put(input.name(), format);
        }
        return result;
    }

    static List<GenericVertexInput> genericVertexInputs(
            final SpvModule.Reflection reflection,
            final List<BackendRenderPipeline.CreateInfo.AttribBinding> bindings
    ) throws ShaderCompileException {
        Set<Integer> physicalLocations = new HashSet<>();
        for (BackendRenderPipeline.CreateInfo.AttribBinding binding : bindings) physicalLocations.add(binding.location());
        List<GenericVertexInput> result = new ArrayList<>();
        for (SpvModule.Reflection.InterfaceVariable input : reflection.inputs()) {
            if (physicalLocations.contains(input.location())) continue;
            BaseType baseType = switch (input.type().baseType()) {
                case 7 -> BaseType.INT;
                case 8 -> BaseType.UINT;
                case 13 -> BaseType.FLOAT;
                default -> throw new ShaderCompileException("Unsupported generic vertex input base type " + input.type().baseType());
            };
            result.add(new GenericVertexInput(input.location(), baseType, input.type().vectorSize()));
        }
        result.sort(Comparator.comparingInt(GenericVertexInput::location));
        return List.copyOf(result);
    }

    private static List<MetalCompiledRenderPipeline.ResourceBinding> buildResourceBindings(
            final List<BindGroupLayout.UniformDescription> uniforms,
            final List<RasterStorageResource> storageResources,
            final MslShader vertex,
            final MslShader fragment
    ) {
        List<MetalCompiledRenderPipeline.ResourceBinding> resources = new ArrayList<>();
        for (int index = 0; index < uniforms.size(); index++) {
            BindGroupLayout.UniformDescription uniform = uniforms.get(index);
            MetalCompiledRenderPipeline.ResourceKind kind = switch (uniform.type()) {
                case UNIFORM_BUFFER -> MetalCompiledRenderPipeline.ResourceKind.UNIFORM_BUFFER;
                case COMBINED_IMAGE_SAMPLER -> MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE;
                case TEXEL_BUFFER -> MetalCompiledRenderPipeline.ResourceKind.TEXEL_BUFFER;
            };
            resources.add(new MetalCompiledRenderPipeline.ResourceBinding(
                    kind, uniform.name(), index, stageMask(uniform.name(), vertex, fragment), uniform.gpuFormat()
            ));
        }
        for (RasterStorageResource storage : storageResources) {
            MetalCompiledRenderPipeline.ResourceKind kind = storage.kind() == RasterStorageKind.BUFFER
                    ? MetalCompiledRenderPipeline.ResourceKind.STORAGE_BUFFER
                    : MetalCompiledRenderPipeline.ResourceKind.STORAGE_IMAGE;
            resources.add(new MetalCompiledRenderPipeline.ResourceBinding(
                    kind, storage.descriptorName(), storage.physicalBinding(), storage.stageMask(), null
            ));
        }
        int pushConstantStageMask = (vertex.hasPushConstants() ? MetalCompiledRenderPipeline.STAGE_VERTEX : 0)
                | (fragment.hasPushConstants() ? MetalCompiledRenderPipeline.STAGE_FRAGMENT : 0);
        if (pushConstantStageMask != 0) {
            resources.add(new MetalCompiledRenderPipeline.ResourceBinding(
                    MetalCompiledRenderPipeline.ResourceKind.UNIFORM_BUFFER, "push_constants",
                    uniforms.size() + storageResources.size(), pushConstantStageMask, null
            ));
        }
        return List.copyOf(resources);
    }

    private static int stageMask(final String name, final MslShader vertex, final MslShader fragment) {
        int mask = 0;
        if (vertex.activeResources().contains(name)) mask |= MetalCompiledRenderPipeline.STAGE_VERTEX;
        if (fragment.activeResources().contains(name)) mask |= MetalCompiledRenderPipeline.STAGE_FRAGMENT;
        return mask == 0 ? MetalCompiledRenderPipeline.STAGE_ALL : mask;
    }

    private enum RasterStorageKind { BUFFER, IMAGE }

    private record RasterStorageResource(
            RasterStorageKind kind, String descriptorName, int physicalBinding, int stageMask
    ) {
    }

    private record RasterStorageUse(
            RasterStorageKind kind, String descriptorName, int stageMask,
            ByteBuffer spirv, int bindingWordOffset
    ) {
    }

    private static List<RasterStorageResource> rebindRasterStorageResources(
            final ByteBuffer vertexSpirv, final ByteBuffer fragmentSpirv, final int firstPhysicalBinding
    ) throws ShaderCompileException {
        List<RasterStorageUse> uses = new ArrayList<>();
        collectRasterStorageUses(vertexSpirv, MetalCompiledRenderPipeline.STAGE_VERTEX, uses);
        collectRasterStorageUses(fragmentSpirv, MetalCompiledRenderPipeline.STAGE_FRAGMENT, uses);
        Map<String, Integer> physicalByDescriptor = new LinkedHashMap<>();
        Map<String, Integer> stagesByDescriptor = new LinkedHashMap<>();
        Map<String, RasterStorageKind> kindsByDescriptor = new LinkedHashMap<>();
        for (RasterStorageUse use : uses) {
            int physical = physicalByDescriptor.computeIfAbsent(
                    use.descriptorName(), ignored -> firstPhysicalBinding + physicalByDescriptor.size()
            );
            RasterStorageKind previous = kindsByDescriptor.putIfAbsent(use.descriptorName(), use.kind());
            if (previous != null && previous != use.kind()) throw new ShaderCompileException("Raster resource changes kind");
            stagesByDescriptor.merge(use.descriptorName(), use.stageMask(), (left, right) -> left | right);
            use.spirv().asIntBuffer().put(use.bindingWordOffset(), physical);
        }
        List<RasterStorageResource> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : physicalByDescriptor.entrySet()) {
            result.add(new RasterStorageResource(
                    kindsByDescriptor.get(entry.getKey()), entry.getKey(), entry.getValue(), stagesByDescriptor.get(entry.getKey())
            ));
        }
        return List.copyOf(result);
    }

    private static void collectRasterStorageUses(
            final ByteBuffer spirv, final int stageMask, final List<RasterStorageUse> output
    ) throws ShaderCompileException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer words = spirv.asIntBuffer();
            PointerBuffer pointer = stack.callocPointer(1);
            checkSpvc(Spvc.spvc_context_create(pointer), "spvc_context_create(storage)");
            long context = pointer.get(0);
            try {
                checkSpvc(Spvc.spvc_context_parse_spirv(context, words, words.remaining(), pointer), "spvc_context_parse_spirv(storage)");
                checkSpvc(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_NONE, pointer.get(0), Spvc.SPVC_CAPTURE_MODE_COPY, pointer), "spvc_context_create_compiler(storage)");
                long compiler = pointer.get(0);
                checkSpvc(Spvc.spvc_compiler_create_shader_resources(compiler, pointer), "spvc_compiler_create_shader_resources(storage)");
                long resources = pointer.get(0);
                collectRasterStorageType(stack, compiler, resources, spirv, stageMask, Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER, RasterStorageKind.BUFFER, output);
                collectRasterStorageType(stack, compiler, resources, spirv, stageMask, Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE, RasterStorageKind.IMAGE, output);
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    private static void collectRasterStorageType(
            final MemoryStack stack, final long compiler, final long resources, final ByteBuffer spirv,
            final int stageMask, final int resourceType, final RasterStorageKind kind,
            final List<RasterStorageUse> output
    ) throws ShaderCompileException {
        PointerBuffer listPointer = stack.callocPointer(1);
        PointerBuffer countPointer = stack.callocPointer(1);
        checkSpvc(Spvc.spvc_resources_get_resource_list_for_type(resources, resourceType, listPointer, countPointer), "storage resource list");
        int count = Math.toIntExact(countPointer.get(0));
        if (count == 0) return;
        SpvcReflectedResource.Buffer reflected = SpvcReflectedResource.create(listPointer.get(0), count);
        IntBuffer offset = stack.callocInt(1);
        for (SpvcReflectedResource resource : reflected) {
            if (!Spvc.spvc_compiler_has_decoration(compiler, resource.id(), Spv.SpvDecorationBinding)
                    || !Spvc.spvc_compiler_get_binary_offset_for_decoration(compiler, resource.id(), Spv.SpvDecorationBinding, offset)) {
                throw new ShaderCompileException("Raster storage resource has no writable binding");
            }
            int logicalBinding = Spvc.spvc_compiler_get_decoration(compiler, resource.id(), Spv.SpvDecorationBinding);
            String resourceName = resource.nameString();
            if (resourceName == null || resourceName.isBlank()) resourceName = "binding" + logicalBinding;
            if (kind == RasterStorageKind.IMAGE) {
                long type = Spvc.spvc_compiler_get_type_handle(compiler, resource.type_id());
                if (Spvc.spvc_type_get_image_dimension(type) != Spv.SpvDim2D) throw new ShaderCompileException("Raster storage image is not 2D");
            }
            String descriptorName = kind == RasterStorageKind.BUFFER
                    ? storageBufferDescriptorName(logicalBinding, resourceName) : resourceName;
            output.add(new RasterStorageUse(kind, descriptorName, stageMask, spirv, offset.get(0)));
        }
    }

    static String storageBufferDescriptorName(final int logicalBinding, final String resourceName) {
        if (logicalBinding < 0) throw new IllegalArgumentException("SSBO binding must be non-negative");
        return "iris_ssbo/" + logicalBinding + '/' + resourceName;
    }

    static int storageBufferLogicalBinding(final String descriptorName) {
        if (!descriptorName.startsWith("iris_ssbo/")) return -1;
        int start = "iris_ssbo/".length();
        int end = descriptorName.indexOf('/', start);
        if (end < 0) return -1;
        try { return Integer.parseInt(descriptorName.substring(start, end)); }
        catch (NumberFormatException ignored) { return -1; }
    }

    static String applySampleLodBias(final String mslSource, final float lodBias) {
        if (lodBias == 0.0F || !Float.isFinite(lodBias)) return mslSource;
        String marker = ".sample(";
        StringBuilder patched = new StringBuilder(mslSource.length() + 256);
        String biasText = String.format(java.util.Locale.ROOT, ", bias(%sf)", lodBias);
        int cursor = 0;
        while (true) {
            int start = mslSource.indexOf(marker, cursor);
            if (start < 0) { patched.append(mslSource, cursor, mslSource.length()); break; }
            int argsStart = start + marker.length();
            int depth = 1, topLevelCommas = 0, index = argsStart;
            while (index < mslSource.length() && depth > 0) {
                char character = mslSource.charAt(index++);
                if (character == '(') depth++;
                else if (character == ')') depth--;
                else if (character == ',' && depth == 1) topLevelCommas++;
            }
            int close = index - 1;
            if (depth != 0) { patched.append(mslSource, cursor, mslSource.length()); break; }
            String args = mslSource.substring(argsStart, close);
            patched.append(mslSource, cursor, close);
            if (topLevelCommas == 1 && !args.contains("level(") && !args.contains("bias(")
                    && !args.contains("gradient2d(") && !args.contains("min_lod_clamp(")) patched.append(biasText);
            patched.append(')');
            cursor = close + 1;
        }
        return patched.toString();
    }

    private static String extractEntryPoint(final String source, final Pattern pattern, final String fallback) {
        Matcher matcher = pattern.matcher(source);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    static Map<String, GpuFormat> vertexAttributeFormats(final RenderPipeline pipeline) {
        Map<String, GpuFormat> result = new LinkedHashMap<>();
        for (VertexFormat binding : pipeline.getVertexFormatBindings()) {
            if (binding != null) binding.getElements().forEach(element -> result.putIfAbsent(element.name(), element.format()));
        }
        return result;
    }

    enum BaseType {
        FLOAT(0), INT(16), UINT(32);
        private final int defaultValueOffset;
        BaseType(final int defaultValueOffset) { this.defaultValueOffset = defaultValueOffset; }
        int defaultValueOffset() { return defaultValueOffset; }
    }

    record GenericVertexInput(int location, BaseType baseType, int components) {
        GenericVertexInput {
            if (location < 0 || components < 1 || components > 4) throw new IllegalArgumentException("Invalid generic vertex input");
            Objects.requireNonNull(baseType, "baseType");
        }
        MTLVertexFormat metalFormat() {
            return switch (baseType) {
                case FLOAT -> switch (components) {
                    case 1 -> MTLVertexFormat.Float; case 2 -> MTLVertexFormat.Float2;
                    case 3 -> MTLVertexFormat.Float3; case 4 -> MTLVertexFormat.Float4;
                    default -> throw new AssertionError(components);
                };
                case INT -> switch (components) {
                    case 1 -> MTLVertexFormat.Int; case 2 -> MTLVertexFormat.Int2;
                    case 3 -> MTLVertexFormat.Int3; case 4 -> MTLVertexFormat.Int4;
                    default -> throw new AssertionError(components);
                };
                case UINT -> switch (components) {
                    case 1 -> MTLVertexFormat.UInt; case 2 -> MTLVertexFormat.UInt2;
                    case 3 -> MTLVertexFormat.UInt3; case 4 -> MTLVertexFormat.UInt4;
                    default -> throw new AssertionError(components);
                };
            };
        }
        int defaultValueOffset() { return baseType.defaultValueOffset(); }
    }

    static final int GENERIC_VERTEX_DEFAULT_VALUES_SIZE = 48;

    static void writeGenericVertexDefaultValues(final ByteBuffer destination) {
        if (destination.remaining() < GENERIC_VERTEX_DEFAULT_VALUES_SIZE) throw new IllegalArgumentException("Generic vertex default buffer requires 48 bytes");
        ByteBuffer values = destination.duplicate().order(ByteOrder.nativeOrder());
        int start = values.position();
        for (int index = 0; index < GENERIC_VERTEX_DEFAULT_VALUES_SIZE; index++) values.put(start + index, (byte) 0);
        values.putFloat(start + BaseType.FLOAT.defaultValueOffset() + 12, 1.0F);
        values.putInt(start + BaseType.INT.defaultValueOffset() + 12, 1);
        values.putInt(start + BaseType.UINT.defaultValueOffset() + 12, 1);
    }

    private static void validateFragmentOutputSignature(
            final List<@Nullable ColorTargetState> targets, final Set<Integer> shaderLocations
    ) throws ShaderCompileException {
        // RenderPearl uses an empty color-target list for depth-only passes.  The
        // shared water-mask shader still declares a location-0 output because
        // the same shader is also used by the classic color-writing pipeline;
        // with no color attachment that output is intentionally discarded by
        // the render pass.  Do not turn this valid depth-only composition into
        // a false frontend/backend mismatch.
        if (targets.isEmpty()) return;
        Set<Integer> targetLocations = new HashSet<>();
        for (int index = 0; index < targets.size(); index++) if (targets.get(index) != null) targetLocations.add(index);
        if (!targetLocations.containsAll(shaderLocations)) {
            throw new ShaderCompileException(
                    "Fragment output location mismatch: shader locations=" + shaderLocations
                            + ", target locations=" + targetLocations
            );
        }
    }

    record MslShader(String source, boolean hasPushConstants, Set<String> activeResources,
                     Set<Integer> stageOutputLocations) {
    }

    static MslShader spirvToMsl(
            final ByteBuffer spirvBytes, final int pushConstantBinding,
            final Map<String, GpuFormat> attributeFormats,
            final Map<String, Integer> explicitFragmentOutputLocations
    ) throws ShaderCompileException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer spirvWords = spirvBytes.asIntBuffer();
            if (spirvWords.remaining() < 5) throw new ShaderCompileException("SPIR-V is too small");
            PointerBuffer pContext = stack.mallocPointer(1);
            checkSpvc(Spvc.spvc_context_create(pContext), "spvc_context_create");
            long context = pContext.get(0);
            try {
                PointerBuffer pIr = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_context_parse_spirv(context, spirvWords, spirvWords.remaining(), pIr), "spvc_context_parse_spirv");
                PointerBuffer pCompiler = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_MSL, pIr.get(0), Spvc.SPVC_CAPTURE_MODE_COPY, pCompiler), "spvc_context_create_compiler");
                long compiler = pCompiler.get(0);
                PointerBuffer pOptions = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_compiler_create_compiler_options(compiler, pOptions), "spvc_compiler_create_compiler_options");
                long options = pOptions.get(0);
                checkSpvc(Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS), "msl platform");
                checkSpvc(Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION_4_0), "msl version");
                checkSpvc(Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING, true), "msl binding decorations");
                checkSpvc(Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true), "msl texture buffers");
                checkSpvc(Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true), "msl flip y");
                checkSpvc(Spvc.spvc_compiler_install_compiler_options(compiler, options), "install msl options");
                registerIntegerInputConversions(stack, compiler, attributeFormats);
                Set<Integer> outputLocations = applyExplicitFragmentOutputLocations(stack, compiler, explicitFragmentOutputLocations);
                PointerBuffer pActive = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_compiler_get_active_interface_variables(compiler, pActive), "active interface variables");
                checkSpvc(Spvc.spvc_compiler_set_enabled_interface_variables(compiler, pActive.get(0)), "enabled interface variables");
                Set<String> activeResources = collectActiveResourceNames(stack, compiler, pActive.get(0));
                PointerBuffer pResources = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_compiler_create_shader_resources(compiler, pResources), "shader resources");
                long resources = pResources.get(0);
                PointerBuffer pList = stack.mallocPointer(1);
                PointerBuffer pCount = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_resources_get_resource_list_for_type(resources, Spvc.SPVC_RESOURCE_TYPE_PUSH_CONSTANT, pList, pCount), "push constants");
                boolean hasPushConstants = pCount.get(0) > 0;
                if (hasPushConstants) {
                    SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(pList.get(0), 1);
                    Spvc.spvc_compiler_set_decoration(compiler, list.get(0).id(), Spv.SpvDecorationBinding, pushConstantBinding);
                }
                PointerBuffer pSource = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_compiler_compile(compiler, pSource), "spvc_compiler_compile");
                String source = MemoryUtil.memUTF8(pSource.get(0));
                if (source.contains("unknown_texture_type")) {
                    source = source.replace("unknown_texture_type<", "texture2d<");
                    if (source.contains("unknown_texture_type")) throw new ShaderCompileException("Unlowered rectangle texture type");
                }
                return new MslShader(source, hasPushConstants, activeResources, outputLocations);
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    private static Set<String> collectActiveResourceNames(
            final MemoryStack stack, final long compiler, final long activeSet
    ) throws ShaderCompileException {
        PointerBuffer pResources = stack.mallocPointer(1);
        checkSpvc(Spvc.spvc_compiler_create_shader_resources_for_active_variables(compiler, pResources, activeSet), "active resources");
        long resources = pResources.get(0);
        Set<String> names = new HashSet<>();
        int[] types = {Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE,
                Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS,
                Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER, Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE};
        for (int type : types) collectResourceNames(stack, resources, type, names);
        return names;
    }

    private static void collectResourceNames(
            final MemoryStack stack, final long resources, final int resourceType, final Set<String> names
    ) throws ShaderCompileException {
        PointerBuffer list = stack.mallocPointer(1), count = stack.mallocPointer(1);
        checkSpvc(Spvc.spvc_resources_get_resource_list_for_type(resources, resourceType, list, count), "resource list");
        int size = Math.toIntExact(count.get(0));
        if (size == 0) return;
        SpvcReflectedResource.Buffer reflected = SpvcReflectedResource.create(list.get(0), size);
        for (SpvcReflectedResource resource : reflected) names.add(resource.nameString());
    }

    private static Set<Integer> applyExplicitFragmentOutputLocations(
            final MemoryStack stack, final long compiler, final Map<String, Integer> explicitLocations
    ) throws ShaderCompileException {
        PointerBuffer resources = stack.mallocPointer(1);
        checkSpvc(Spvc.spvc_compiler_create_shader_resources(compiler, resources), "fragment outputs");
        PointerBuffer list = stack.mallocPointer(1), count = stack.mallocPointer(1);
        checkSpvc(Spvc.spvc_resources_get_resource_list_for_type(resources.get(0), Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT, list, count), "fragment output list");
        int size = Math.toIntExact(count.get(0));
        Set<Integer> locations = new HashSet<>();
        if (size == 0) return locations;
        SpvcReflectedResource.Buffer outputs = SpvcReflectedResource.create(list.get(0), size);
        for (SpvcReflectedResource output : outputs) {
            Integer location = explicitLocations.get(output.nameString());
            if (location != null) Spvc.spvc_compiler_set_decoration(compiler, output.id(), Spv.SpvDecorationLocation, location);
            if (!Spvc.spvc_compiler_has_decoration(compiler, output.id(), Spv.SpvDecorationBuiltIn)) locations.add(Spvc.spvc_compiler_get_decoration(compiler, output.id(), Spv.SpvDecorationLocation));
        }
        return locations;
    }

    private static void registerIntegerInputConversions(
            final MemoryStack stack, final long compiler, final Map<String, GpuFormat> attributeFormats
    ) throws ShaderCompileException {
        if (attributeFormats.isEmpty()) return;
        PointerBuffer resources = stack.mallocPointer(1);
        checkSpvc(Spvc.spvc_compiler_create_shader_resources(compiler, resources), "integer input resources");
        PointerBuffer list = stack.mallocPointer(1), count = stack.mallocPointer(1);
        checkSpvc(Spvc.spvc_resources_get_resource_list_for_type(resources.get(0), Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT, list, count), "integer input list");
        int size = Math.toIntExact(count.get(0));
        if (size == 0) return;
        SpvcReflectedResource.Buffer inputs = SpvcReflectedResource.create(list.get(0), size);
        for (SpvcReflectedResource input : inputs) {
            GpuFormat format = attributeFormats.get(input.nameString());
            if (format == null || !format.name().endsWith("_UINT")) continue;
            int width = format.name().contains("8") ? Spvc.SPVC_MSL_SHADER_VARIABLE_FORMAT_UINT8
                    : format.name().contains("16") ? Spvc.SPVC_MSL_SHADER_VARIABLE_FORMAT_UINT16
                    : Spvc.SPVC_MSL_SHADER_VARIABLE_FORMAT_OTHER;
            if (width == Spvc.SPVC_MSL_SHADER_VARIABLE_FORMAT_OTHER) continue;
            long type = Spvc.spvc_compiler_get_type_handle(compiler, input.type_id());
            int baseType = Spvc.spvc_type_get_basetype(type);
            if (baseType != Spvc.SPVC_BASETYPE_INT8 && baseType != Spvc.SPVC_BASETYPE_INT16
                    && baseType != Spvc.SPVC_BASETYPE_INT32 && baseType != Spvc.SPVC_BASETYPE_INT64) continue;
            SpvcMslShaderInterfaceVar2 variable = SpvcMslShaderInterfaceVar2.malloc(stack);
            Spvc.spvc_msl_shader_interface_var_init_2(variable);
            variable.location(Spvc.spvc_compiler_get_decoration(compiler, input.id(), Spv.SpvDecorationLocation));
            variable.vecsize(Spvc.spvc_type_get_vector_size(type));
            variable.format(width);
            variable.rate(Spvc.SPVC_MSL_SHADER_VARIABLE_RATE_PER_VERTEX);
            checkSpvc(Spvc.spvc_compiler_msl_add_shader_input_2(compiler, variable), "integer input conversion");
        }
    }

    private static void checkSpvc(final int result, final String stage) throws ShaderCompileException {
        if (result != Spvc.SPVC_SUCCESS) throw new ShaderCompileException("SPIRV-Cross error at " + stage + ": " + result);
    }
}