package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.commands.GpuFence;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Metal 侧的 fence 实现。
 *
 * <p>{@code GpuFence#awaitCompletion(long)} 的参数单位是<b>纳秒</b>，
 * 其中 0（即 {@code GpuFence.NO_TIMEOUT}）的语义是「只轮询、不等待」。
 * 引擎的 {@code StagedVertexBuffer$GpuBufferPool} 每帧都用 {@code awaitCompletion(0)}
 * 判断顶点缓冲能否回收，所以这个 0 必须原样传到底层，
 * 由后端区分「轮询」与「定时等待」两条路径。
 *
 * <p>完成一次判定后即为终态（与引擎参考后端 {@code GlFence} 的
 * {@code closedOrCompleted} 行为一致），后续调用直接返回 true。
 */
@Environment(EnvType.CLIENT)
final class MetalFence implements GpuFence {
    private final MetalCommandEncoder encoder;
    private final long submitIndex;
    private boolean closedOrCompleted;

    MetalFence(final MetalCommandEncoder encoder, final long submitIndex) {
        this.encoder = encoder;
        this.submitIndex = submitIndex;
    }

    @Override
    public void close() {
        this.closedOrCompleted = true;
    }

    @Override
    public boolean awaitCompletion(final long timeoutNS) {
        if (this.closedOrCompleted) {
            return true;
        }
        this.closedOrCompleted = this.encoder.awaitSubmitCompletion(this.submitIndex, timeoutNS);
        return this.closedOrCompleted;
    }
}
