package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.caffeinemc.mods.sodium.client.gpu.device.context.VKIndirectContext;

/**
 * Metal 的间接绘制上下文。
 *
 * <p>26.3 的 Sodium 重构了 draw context 契约：{@code updateData(RenderRegion, CameraTransform)}
 * 已从上下文层移除，per-region 的 push constant 注入改由
 * {@code VKIndirectDrawBatch#draw(DrawContext)} 承担；上下文自身只需要在
 * {@link #setContext} 里记录当前渲染通道和管线。
 *
 * <p>由于 {@code VKIndirectContext} 已提供 {@code addCommand} / {@code rotate} /
 * {@code delete} / {@code endDraw} 的完整间接绘制实现，这里不再覆写任何行为，
 * 仅保留 {@link MetalRenderPass} 的关联，供需要后端能力的调用方查询。
 */
public final class MetalDrawContext extends VKIndirectContext {
    private MetalRenderPass metalPass;

    @Override
    public void setContext(final RenderPass pass, final RenderPipeline pipeline) {
        this.pass = pass;
        this.metalPass = MetalRenderPassRegistry.lookup(pass);
    }

    /** 当前通道对应的 Metal 后端实现；非 Metal 路径下为 {@code null}。 */
    public MetalRenderPass metalPass() {
        return this.metalPass;
    }
}
