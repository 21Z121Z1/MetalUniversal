package com.metallum.mixin.iris;

import com.metallum.client.metal.render.MetalActive;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gl.GLDebug;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Layer 4：Iris {@link GLDebug} 对象命名守卫。
 *
 * <p>覆盖 {@code ExtendedShader} 构造体首行的
 * {@code GLDebug.nameObject(GL43C.GL_PROGRAM, programId, string)}（iris-ref
 * {@code ExtendedShader:104}）。该方法转发到 {@code debugState.nameObject}，
 * {@code KHRDebugState} 实现走 {@code KHRDebug.glObjectLabel(id, object, name)}。
 * Metal 后端无 GL/KHR_debug 上下文，对哨兵 {@code programId == 0} 调用会崩
 * （即便不崩，给"无对象"打标签也无意义）。
 *
 * <p><b>守卫。</b>当 {@link MetalActive#isMetalActive()} 且 {@code object <= 0}
 * （哨兵 program 句柄）时在 {@code HEAD} 处 {@code cancel}，跳过整个方法体。
 *
 * <p><b>Metal 激活条件。</b>{@link MetalActive#isMetalActive()}。
 * <b>非 Metal 路径完全 no-op</b>：Metal 未激活时立即 return，Iris 原始
 * {@code nameObject} 行为不变。{@code object == 0} 在合法 GL 路径下永远不会
 * 被合法命名（0 是"无对象"哨兵），守卫对非 Metal 零副作用。
 *
 * <p>注：{@code GLDebug.nameObject(int,int,String)} 是 static，故本注入器亦为
 * static。{@code GLDebug} 内部 {@code KHRDebugState}/{@code UnsupportedDebugState}
 * 的同名实例方法在独立内部类中，本 mixin 仅 target {@code GLDebug} 外层类，
 * 不会误伤它们。
 */
@Environment(EnvType.CLIENT)
@Mixin(GLDebug.class)
public class GLDebugMixin {
    @Inject(method = "nameObject", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$skipNameObjectOnMetal(
            int id,
            int object,
            String name,
            CallbackInfo cir
    ) {
        if (!MetalActive.isMetalActive()) {
            return;
        }
        if (object <= 0) {
            cir.cancel();
        }
    }
}
