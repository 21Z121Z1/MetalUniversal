package com.metallum.mixin.iris;

import com.metallum.client.metal.render.MetalActive;
import com.mojang.blaze3d.opengl.GlStateManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Layer 3：blaze3d {@link GlStateManager} GL 入口守卫（构造期）。
 *
 * <p>覆盖 {@code ShaderMap.checkLinkingState}、{@code ExtendedShader} 构造体、
 * {@code ProgramUniforms/ProgramSamplers/ProgramImages.Builder} 与
 * {@code ProgramUniforms.buildUniforms} 期间经 {@link GlStateManager} 发起的、
 * 以哨兵 program 句柄 {@code 0} 为实参的 GL 查询。具体调用点（iris-ref）：
 * <ul>
 *   <li>{@code _glGetUniformLocation(int, String)} → 返回 {@code -1}：
 *       {@code ExtendedShader:156,174,175}、{@code ProgramUniforms:294}、
 *       {@code ProgramSamplers:136,151,190}、{@code ProgramImages:63,68}。
 *       {@code -1} 表示"该 uniform 不存在"，Iris 各 Builder 一致按缺失处理。</li>
 *   <li>{@code glGetProgrami(int, int)} → 返回 {@code 0}：
 *       {@code ShaderMap:43}（{@code GL_LINK_STATUS}，Layer 1 已 cancel 整个方法，
 *       此处为兜底）、{@code ProgramUniforms:315}（{@code GL_ACTIVE_UNIFORMS}，
 *       返回 0 使遍历循环不执行）。</li>
 *   <li>{@code glGetProgramInfoLog(int, int)} → 返回 {@code ""}（防御性，
 *       {@code ShaderMap:45}）。</li>
 *   <li>{@code glDeleteShader(int)} → no-op：{@code PartialShader.detachIfValid:45}
 *       在 {@code getFinally()} 求值（作为 {@code new ExtendedShader(id.getFinally(),...)}
 *       的实参）时对 {@code vertexS==0}/{@code fragS==0} 调用
 *       {@code glDeleteShader(0)}。任务前提"Metal 无 GL 上下文，任何 GL 调用都崩"，
 *       故此入口也在构造期路径上，须一并守卫；{@code glDeleteShader(0)} 在 GL
 *       规范下本就是 silent no-op，守卫对非 Metal 零副作用。</li>
 * </ul>
 *
 * <p><b>守卫。</b>当 {@link MetalActive#isMetalActive()} 且 {@code program/shader <= 0}
 * 时短路。{@code <= 0} 而非 {@code == 0}：Iris 哨兵用 {@code 0}，但 {@code -1}
 * 也可能从 {@code PartialShader} 的 geometry/tess 句柄传来（防御性）。
 *
 * <p><b>Metal 激活条件。</b>{@link MetalActive#isMetalActive()}。
 * <b>非 Metal 路径完全 no-op</b>：Metal 未激活时立即 return，blaze3d 原始 GL
 * 行为不变。{@code program/shader == 0} 在合法 GL 路径下永远不会被合法查询
 * （0 是"无对象"哨兵），守卫对非 Metal 零副作用，仍加 Metal 守卫作双重保险。
 *
 * <p><b>签名。</b>经 javap 验证 blaze3d 实际描述符：
 * {@code _glGetUniformLocation(int,CharSequence)→int}、
 * {@code glGetProgrami(int,int)→int}、
 * {@code glGetProgramInfoLog(int,int)→String}、
 * {@code glDeleteShader(int)→void}。Mixin 运行时校验要求精确匹配，
 * 不接受协变（{@code String} 是 {@code CharSequence} 子类但编译通过不代表运行时通过），
 * 故 {@code _glGetUniformLocation} 用 {@code CharSequence} 而非 {@code String}。
 */
@Environment(EnvType.CLIENT)
@Mixin(GlStateManager.class)
public class GlStateManagerMixin {
    private static final int GL_VENDOR = 7936;
    private static final int GL_RENDERER = 7937;
    private static final int GL_VERSION = 7938;
    private static final int GL_SHADING_LANGUAGE_VERSION = 35724;
    private static final int GL_NUM_EXTENSIONS = 33309;
    private static final int GL_MAX_TEXTURE_IMAGE_UNITS = 34930;
    private static final int GL_MAX_DRAW_BUFFERS = 34852;

    @Inject(method = "_getInteger", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$metalCapabilityLimit(
            final int pname, final CallbackInfoReturnable<Integer> cir
    ) {
        if (!MetalActive.isMetalActive()) {
            return;
        }
        cir.setReturnValue(switch (pname) {
            case GL_MAX_TEXTURE_IMAGE_UNITS -> 16;
            case GL_MAX_DRAW_BUFFERS -> 8;
            case GL_NUM_EXTENSIONS -> 0;
            default -> 8;
        });
    }

    @Inject(method = "_getString", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$metalCapabilityString(
            final int pname, final CallbackInfoReturnable<String> cir
    ) {
        if (!MetalActive.isMetalActive()) {
            return;
        }
        cir.setReturnValue(switch (pname) {
            case GL_VENDOR -> "MetalUniversal";
            case GL_RENDERER -> "MetalUniversal Metal";
            case GL_VERSION, GL_SHADING_LANGUAGE_VERSION -> "4.6.0";
            default -> "";
        });
    }

    @Inject(
            method = {"_enableBlend", "_enableDepthTest", "_disableBlend", "_disableDepthTest"},
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private static void metallum$skipGlobalGlState(final CallbackInfo ci) {
        if (MetalActive.isMetalActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "_glGetUniformLocation", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$noUniformLocationOnMetal(
            int program,
            CharSequence name,
            CallbackInfoReturnable<Integer> cir
    ) {
        if (!MetalActive.isMetalActive()) {
            return;
        }
        if (program <= 0) {
            cir.setReturnValue(-1);
        }
    }

    @Inject(method = "glGetProgrami", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$zeroProgramQueryOnMetal(
            int program,
            int pname,
            CallbackInfoReturnable<Integer> cir
    ) {
        if (!MetalActive.isMetalActive()) {
            return;
        }
        if (program <= 0) {
            cir.setReturnValue(0);
        }
    }

    @Inject(method = "glGetProgramInfoLog", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$emptyProgramInfoLogOnMetal(
            int program,
            int maxLength,
            CallbackInfoReturnable<String> cir
    ) {
        if (!MetalActive.isMetalActive()) {
            return;
        }
        if (program <= 0) {
            cir.setReturnValue("");
        }
    }

    @Inject(method = "glDeleteShader", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$skipDeleteShaderOnMetal(
            int shader,
            CallbackInfo cir
    ) {
        if (!MetalActive.isMetalActive()) {
            return;
        }
        if (shader <= 0) {
            cir.cancel();
        }
    }
}
