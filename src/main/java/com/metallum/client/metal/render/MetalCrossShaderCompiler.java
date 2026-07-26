package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.bridge.ShaderBridge;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BindGroupLayout.UniformDescription;
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

import java.nio.ByteBuffer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Environment(EnvType.CLIENT)
public final class MetalCrossShaderCompiler {
    private static final Set<String> BUILT_IN_UNIFORMS = Set.of("Projection", "Lighting", "Fog", "Globals");
    // MSL 3.1（macOS 14+ / Metal 3.0+）原生支持图像原子操作（imageAtomicAdd/Min/Max/Exchange 等），
    // 通过 metal::atomic_fetch_add_explicit 等 API 实现。MSL 3.0 下 SPIRV-Cross 会生成回退代码或失败。
    private static final int MSL_VERSION_3_1 = 31000;
    private static final Pattern VERTEX_ENTRY_PATTERN = Pattern.compile("\\bvertex\\s+\\w+\\s+(\\w+)\\s*\\(");
    private static final Pattern FRAGMENT_ENTRY_PATTERN = Pattern.compile("\\bfragment\\s+\\w+\\s+(\\w+)\\s*\\(");

    // SPIR-V Dim 枚举值（来自 SPIR-V 规范，与 org.lwjgl.util.spvc.Spv 常量一致）。
    // 用于 addToBindGroup 中校验 sampler 维度，替代原先对 LWJGL Spv 类的依赖。
    private static final int SPV_DIM_2D = 1;
    private static final int SPV_DIM_CUBE = 3;
    private static final int SPV_DIM_BUFFER = 5;

    static {
        // native 库（libglslang + libspvc + libmetallum）由 MetalNativeBridge 静态块加载。
        // 不再需要单独配置 LWJGL spvc —— MetalUniversal 已改用自建 ShaderBridge JNI，
        // 不再依赖 LWJGL 的 Spvc / SpvcCompiler 绑定，也无 iOS MoltenVK 符号抢占问题。
    }

    private MetalCrossShaderCompiler() {
    }

    /**
     * Compiles a raw GLSL source string to Metal Shading Language (MSL) via
     * SPIR-V, using the self-built JNI bridge ({@link ShaderBridge}):
     * GLSL → SPIR-V via glslang, then SPIR-V → MSL via SPIRV-Cross. This is
     * the public entry point used by the Iris integration bridge to compile
     * shaderpack GLSL to Metal.
     *
     * <p>Unlike {@link #compile}, this method does not require a
     * {@link RenderPipeline} — it accepts arbitrary GLSL source, making it
     * suitable for Iris's composite/shadow/gbuffer programs that live outside
     * Minecraft's standard pipeline system.
     *
     * @param name        debug name for the shader (used in error messages)
     * @param glslSource  the GLSL source code (already preprocessed & patched)
     * @param type        the shader stage type (VERTEX, FRAGMENT, etc.)
     * @return the compiled MSL shader source plus reflection metadata
     * @throws ShaderCompileException if GLSL→SPIR-V or SPIR-V→MSL fails
     */
    public static MslShader compileGlslToMsl(final String name, final String glslSource, final ShaderType type) throws ShaderCompileException {
        // 使用自建 glslang 编译 GLSL→SPIR-V
        int stage = ShaderBridge.stageFromShaderType(type);
        byte[] spirvBytes;
        try {
            spirvBytes = ShaderBridge.glslangCompile(glslSource, stage, ShaderBridge.SPV_ENV_VULKAN_1_0);
        } catch (RuntimeException e) {
            throw new ShaderCompileException("Failed to compile GLSL to SPIR-V for shader: " + name + ": " + e.getMessage());
        }
        if (spirvBytes == null || spirvBytes.length < 20) {
            throw new ShaderCompileException("glslang produced empty/invalid SPIR-V for shader: " + name);
        }
        return spirvToMsl(spirvBytes, 0, Map.of());
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
            MslShader vertexMsl = spirvToMsl(toByteArray(vertexSpirv.spirv()), layoutEntries.size(), vertexAttributeFormats(pipeline));

            fragmentSpirv.rebind(tolerateUnprovidedInputs(vertexOutputs, fragmentSpirv.inputs()), layoutEntries);
            MslShader fragmentMsl = spirvToMsl(toByteArray(fragmentSpirv.spirv()), layoutEntries.size(), Map.of());

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
                if (dimensions != SPV_DIM_BUFFER) {
                    throw new ShaderCompileException("UTB (" + name + ") must have type of SpvDimBuffer");
                }
                addBindingIfAbsent(entries, VulkanBindGroupEntryType.TEXEL_BUFFER, name, uniform.gpuFormat());
            } else {
                if (!samplers.contains(name)) {
                    throw new ShaderCompileException("Unable to find shader defined uniform (" + name + ")");
                }
                if (dimensions != SPV_DIM_2D && dimensions != SPV_DIM_CUBE) {
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

    /**
     * 将 {@link IntermediaryShaderModule#spirv()} 返回的 {@link ByteBuffer}
     * 转换为 byte[]，供 {@link #spirvToMsl(byte[], int, Map)} 使用。
     *
     * <p>使用 {@code duplicate()} 避免移动原 buffer 的 position。
     */
    private static byte[] toByteArray(final ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return bytes;
    }

    /**
     * 将 SPIR-V 二进制交叉编译为 MSL，使用自建 JNI 桥接层
     * {@link ShaderBridge#spvcCompileToMsl}（封装 SPIRV-Cross C API）。
     *
     * <p>取代原先直接调用 LWJGL {@code Spvc} / {@code SpvcCompiler} 句柄管理的实现。
     * 编译选项与原实现保持一致：MSL_PLATFORM_MACOS、MSL 3.1、
     * enableDecorationBinding、textureBufferNative、flipVertexY。
     *
     * <p><b>已知限制</b>（因 ShaderBridge 单次调用 API 不暴露反射回调）：
     * <ul>
     *   <li>不再执行 {@code registerIntegerInputConversions} —— _UINT 顶点属性的
     *       uint8/uint16 位宽转换不生效。如需恢复需扩展 ShaderBridge API 增加
     *       per-attribute format 参数。</li>
     *   <li>不再主动设置 push constant 资源的 binding decoration 为
     *       {@code pushConstantBinding}。SPIRV-Cross 按 SPIR-V 中的 binding 转为
     *       MSL 的 {@code [[buffer(N)]]}，但无法重定向到指定 bind index。</li>
     *   <li>{@code activeResources} 返回空集合，导致 {@link #stageMask} 回退到
     *       {@code STAGE_ALL}（功能降级但非致命）。</li>
     * </ul>
     *
     * @param spirvBytes            SPIR-V 二进制（字节数组，长度 ≥ 20）
     * @param pushConstantBinding   预留的 push constant bind index（当前未使用，
     *                              保留参数以维持调用方兼容性）
     * @param attributeFormats      顶点属性格式映射（当前未使用，保留参数以维持
     *                              调用方兼容性）
     * @return 编译后的 MSL 源码及反射元数据
     * @throws ShaderCompileException 如果 SPIR-V 无效或 SPIRV-Cross 编译失败
     */
    private static MslShader spirvToMsl(final byte[] spirvBytes, final int pushConstantBinding, final Map<String, GpuFormat> attributeFormats) throws ShaderCompileException {
        // SPIR-V 二进制必须至少包含 5 个字（头部：magic、version、generator、bound、schema）。
        // 空或过短的 SPIR-V 会导致 spvc_context_parse_spirv 行为不确定。
        if (spirvBytes == null || spirvBytes.length < 20) {
            throw new ShaderCompileException(
                    "SPIR-V is too small: " + (spirvBytes == null ? 0 : spirvBytes.length) + " bytes (minimum 20 required)"
            );
        }

        String mslSource;
        try {
            mslSource = ShaderBridge.spvcCompileToMsl(
                    spirvBytes,
                    ShaderBridge.MSL_PLATFORM_MACOS,
                    MSL_VERSION_3_1,
                    true,   // enableDecorationBinding
                    true,   // textureBufferNative
                    true    // flipVertexY
            );
        } catch (RuntimeException e) {
            throw new ShaderCompileException("SPIRV-Cross failed to compile SPIR-V to MSL: " + e.getMessage());
        }
        if (mslSource == null || mslSource.isBlank()) {
            throw new ShaderCompileException("SPIRV-Cross produced empty MSL source");
        }

        // 启发式检测：SPIRV-Cross 默认将 push constant 块命名为 "push_constants"。
        // 仅用于让 buildResourceBindings 知道是否需要为 push_constants 预留 binding 槽位。
        // TODO(ShaderBridge): 若扩展 ShaderBridge API 暴露反射，可改用准确的资源查询替代启发式。
        boolean hasPushConstants = mslSource.contains("push_constants");

        return new MslShader(mslSource, hasPushConstants, Set.of());
    }

    public record MslShader(String source, boolean hasPushConstants, Set<String> activeResources) {
    }
}
