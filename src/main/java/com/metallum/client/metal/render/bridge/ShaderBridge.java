package com.metallum.client.metal.render.bridge;

import com.mojang.blaze3d.shaders.ShaderType;

/**
 * JNI 桥接层，封装 glslang（GLSL→SPIR-V）和 SPIRV-Cross（SPIR-V→MSL）的 C API 调用。
 * 替代 Mojang GlslCompiler 和 LWJGL spvc 绑定。
 *
 * <p>所有方法都是 native 调用，通过 MetallumShaderBridge.c 实现。
 * native 库（libglslang.dylib + libspvc.dylib）由 MetalNativeBridge 加载。
 *
 * <p>native 方法在 JVM 端通过名称绑定查找符号 ——
 * {@code glslangCompile} 对应 C 函数
 * {@code Java_com_metallum_client_metal_render_bridge_ShaderBridge_glslangCompile}，
 * {@code spvcCompileToMsl} 对应
 * {@code Java_com_metallum_client_metal_render_bridge_ShaderBridge_spvcCompileToMsl}。
 * 前提是 libmetallum.dylib 已通过 {@code System.load} 加载（见
 * {@link MetalNativeBridge#ensureShaderLibrariesLoaded()}），且其依赖
 * libglslang.dylib / libspvc.dylib 先于它加载。
 */
public final class ShaderBridge {
    /**
     * SPIR-V target 环境：Vulkan 1.0（默认，对应 {@code GLSLANG_TARGET_VULKAN_1_0}）。
     */
    public static final int SPV_ENV_VULKAN_1_0 = 0;
    /**
     * SPIR-V target 环境：Vulkan 1.1（对应 {@code GLSLANG_TARGET_VULKAN_1_1}）。
     */
    public static final int SPV_ENV_VULKAN_1_1 = 1;
    /**
     * SPIR-V target 环境：Vulkan 1.2（对应 {@code GLSLANG_TARGET_VULKAN_1_2}）。
     */
    public static final int SPV_ENV_VULKAN_1_2 = 2;

    /**
     * MSL 目标平台：macOS（对应 {@code SPVC_MSL_PLATFORM_MACOS}）。
     */
    public static final int MSL_PLATFORM_MACOS = 0;
    /**
     * MSL 目标平台：iOS（对应 {@code SPVC_MSL_PLATFORM_IOS}）。
     */
    public static final int MSL_PLATFORM_IOS = 1;

    private ShaderBridge() {
    }

    /**
     * 编译 GLSL 源码到 SPIR-V 二进制。
     *
     * <p>调用 glslang 的 C API（{@code glslang_shader_create} →
     * {@code glslang_shader_preprocess} → {@code glslang_shader_parse} →
     * {@code glslang_program_link} → {@code glslang_program_SPIRV_generate}），
     * 输入 GLSL 必须已预处理为 {@code #version 450} 并符合 Vulkan SPIR-V 规则。
     *
     * @param source GLSL 源码（已预处理，{@code #version 450}）
     * @param stage  shader stage：{@link #SPV_ENV_VULKAN_1_0} 等无关，此处为
     *               0=vertex, 1=fragment, 2=geometry, 3=compute
     *               （见 {@link #stageFromShaderType(ShaderType)}）
     * @param target SPIR-V target 环境（{@code SPV_ENV_*} 常量）
     * @return SPIR-V 二进制字节数组（little-endian uint32 字序列）
     * @throws RuntimeException 编译失败（含 glslang info log）
     */
    public static native byte[] glslangCompile(String source, int stage, int target);

    /**
     * 将 SPIR-V 二进制编译为 MSL 源码。
     *
     * <p>调用 SPIRV-Cross 的 C API（{@code spvc_context_create} →
     * {@code spvc_context_parse_spirv} →
     * {@code spvc_context_create_compiler(SPVC_BACKEND_MSL)} →
     * 设置 MSL 选项 → {@code spvc_compiler_compile}），返回的 MSL 字符串
     * 由 SPIRV-Cross 在 context 的 arena 中分配，本方法返回前会复制到
     * Java 字符串中。
     *
     * <p>选项与 {@code MetalCrossShaderCompiler.spirvToMsl} 保持一致：
     * <ul>
     *   <li>{@code SPVC_COMPILER_OPTION_MSL_PLATFORM} = mslPlatform</li>
     *   <li>{@code SPVC_COMPILER_OPTION_MSL_VERSION} = mslVersion（31000 = MSL 3.1）</li>
     *   <li>{@code SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING} = enableDecorationBinding</li>
     *   <li>{@code SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE} = textureBufferNative</li>
     *   <li>{@code SPVC_COMPILER_OPTION_FLIP_VERTEX_Y} = flipVertexY</li>
     *   <li>{@code SPVC_MSL_PUSH_CONSTANT_DESC_SET/BINDING} -> msl_buffer = pushConstantBinding
     *       （通过 {@code spvc_compiler_msl_add_resource_binding_2} 注册，覆盖 vertex 和 fragment 两个 stage）</li>
     * </ul>
     *
     * @param spirv SPIR-V 字节数组（长度必须是 4 的倍数，含 5-word 头）
     * @param mslPlatform {@link #MSL_PLATFORM_MACOS} 或 {@link #MSL_PLATFORM_IOS}
     * @param mslVersion MSL 版本编码（31000 = MSL 3.1）
     * @param enableDecorationBinding 启用 {@code [[buffer(N)]]} 等 decoration binding
     * @param textureBufferNative 启用原生 texture buffer
     * @param flipVertexY 翻转顶点 Y 坐标（与 OpenGL clip-space 约定一致）
     * @param pushConstantBinding {@code >= 0} 为 push constant 重映射到的 MSL buffer 索引
     *                            （通过 {@code spvc_compiler_msl_add_resource_binding_2}）；
     *                            {@code < 0} 为不重映射，使用 SPIRV-Cross 默认 fallback 分配
     * @return MSL 源码字符串
     * @throws RuntimeException 编译失败（含 SPIRV-Cross 错误信息）
     */
    public static native String spvcCompileToMsl(
            byte[] spirv, int mslPlatform, int mslVersion,
            boolean enableDecorationBinding, boolean textureBufferNative, boolean flipVertexY,
            int pushConstantBinding);

    /**
     * 将 Mojang {@link ShaderType} 映射为 native 层使用的 stage 整数。
     *
     * <p>映射表（与 {@code MetallumShaderBridge.c} 中 {@code map_stage()} 一致）：
     * <ul>
     *   <li>VERTEX   → 0（{@code GLSLANG_STAGE_VERTEX}）</li>
     *   <li>FRAGMENT → 1（{@code GLSLANG_STAGE_FRAGMENT}）</li>
     *   <li>GEOMETRY → 2（{@code GLSLANG_STAGE_GEOMETRY}）</li>
     *   <li>COMPUTE  → 3（{@code GLSLANG_STAGE_COMPUTE}）</li>
     * </ul>
     *
     * <p>使用 {@code type.name()} 而非直接引用枚举常量，以避免在 Mojang
     * {@code ShaderType} 枚举不包含 GEOMETRY/COMPUTE 时编译失败。
     *
     * @param type Mojang shader 类型
     * @return stage 整数（传给 {@link #glslangCompile}）
     * @throws IllegalArgumentException 如果 stage 不支持
     */
    public static int stageFromShaderType(ShaderType type) {
        return switch (type.name()) {
            case "VERTEX" -> 0;
            case "FRAGMENT" -> 1;
            case "GEOMETRY" -> 2;
            case "COMPUTE" -> 3;
            default -> throw new IllegalArgumentException("Unsupported shader type: " + type);
        };
    }
}
