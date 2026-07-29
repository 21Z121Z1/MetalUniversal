package com.metallum.mixin.iris;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.programs.ShaderMap;
import net.irisshaders.iris.pipeline.programs.ShaderSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Layer 1：Iris {@link ShaderMap} 链接校验短路。
 *
 * <p>拦截 {@code ShaderMap.checkLinkingState(ShaderKey, ShaderSupplier)}。
 * 该方法体调用 {@code GlStateManager.glGetProgrami(program, GL_LINK_STATUS)}
 * 取链接状态，并当返回 0（{@code GL_FALSE}）时抛 {@link
 * net.irisshaders.iris.gl.shader.ShaderCompileException}。在 Metal 后端下，
 * {@code ShaderCreatorMixin} 返回的哨兵 {@code PartialShader(0,...)} 使
 * {@code program == 0}，而 Metal 无 GL 上下文，{@code glGetProgrami} 会崩；
 * 即便不崩，返回的 0 也会被误判为链接失败而抛异常，从而中断整个 shaderpack
 * 加载。
 *
 * <p><b>拦截什么。</b>当 Metal 后端激活且 {@code shader.id().program() == 0}
 * （哨兵 program 句柄）时，在方法 {@code HEAD} 处 {@code cancel}，跳过整个
 * 方法体——既不调 {@code glGetProgrami} 也不取 info log、不抛异常。
 * {@code ShaderSupplier} 是 public record、{@code PartialShader.program()}
 * 是 public，故 {@code shader.id().program()} 可直接调用，无需 {@code @Shadow}。
 *
 * <p><b>Metal 激活条件。</b>由 {@link MetalActive#isMetalActive()} 守卫。
 * <b>非 Metal 路径完全 no-op</b>：当 Metal 未激活时立即 return，Iris 的 GL
 * 链接校验逻辑原样执行。{@code program == 0} 在合法 GL 路径下永远不会出现
 * （0 是"无 program"哨兵），因此即便去掉 Metal 守卫本拦截对非 Metal 也无副作用，
 * 但仍加双重保险。
 *
 * <p>注意：本 mixin 仅阻止 {@code checkLinkingState} 内的 GL 调用与异常抛出；
 * 随后的 {@code ExtendedShader} 构造体本身仍会发起更多 GL 调用，由 Layer 2-4
 * 的 {@code IrisRenderSystemMixin}/{@code GlStateManagerMixin}/{@code GLDebugMixin}
 * 分别守卫。
 */
@Environment(EnvType.CLIENT)
@Mixin(ShaderMap.class)
public class ShaderMapMixin {
    @Inject(method = "checkLinkingState", at = @At("HEAD"), cancellable = true, remap = false)
    private void metallum$skipLinkingStateCheckOnMetal(
            ShaderKey key,
            ShaderSupplier shader,
            CallbackInfo cir
    ) {
        if (!MetalActive.isMetalActive()) {
            return;
        }
        if (shader != null && shader.id() != null && shader.id().program() == 0) {
            cir.cancel();
        }
    }
}
