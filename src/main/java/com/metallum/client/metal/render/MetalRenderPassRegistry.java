package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.commands.RenderPass;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 前端 {@link RenderPass} 到 Metallum 后端 {@link MetalRenderPass} 的查找表。
 *
 * <p>26.3 把渲染通道拆成前端 / 后端两层：后端对象被 {@code FrontendRenderPass} 私有持有，
 * 既没有 accessor，Sodium 也不再提供 {@code RenderPassAccessor} 反射入口。Metallum 需要
 * 在通道创建时按创建顺序把两者关联起来，供 draw context 之类需要后端能力的调用方查回。
 *
 * <p>这里用 {@link WeakHashMap} 以键的弱引用持有映射，通道被回收后条目会自动消失，
 * 不会随渲染帧数增长而泄漏。
 */
@Environment(EnvType.CLIENT)
final class MetalRenderPassRegistry {
    private static final Map<RenderPass, MetalRenderPass> BY_FRONTEND = new WeakHashMap<>();

    private MetalRenderPassRegistry() {
    }

    /**
     * 登记一条映射。
     *
     * <p>由于设备创建 {@code RenderPass} 的顺序与前端包装它们的顺序一一对应，
     * 创建后端通道时先把实例放入待配对队列，在 draw context 收到前端通道时按序认领。
     */
    private static final java.util.ArrayDeque<MetalRenderPass> PENDING = new java.util.ArrayDeque<>();

    static void offer(final MetalRenderPass renderPass) {
        synchronized (PENDING) {
            PENDING.addLast(renderPass);
        }
    }

    /**
     * 尝试为给定的前端通道找到对应的后端实现。
     *
     * <p>命中后会把该条目从待配对队列中移除，避免同一实例被重复认领。
     */
    @Nullable
    static MetalRenderPass lookup(final RenderPass frontend) {
        synchronized (PENDING) {
            MetalRenderPass cached = BY_FRONTEND.get(frontend);
            if (cached != null) {
                return cached;
            }
            MetalRenderPass candidate = PENDING.pollFirst();
            if (candidate != null) {
                BY_FRONTEND.put(frontend, candidate);
            }
            return candidate;
        }
    }

    /** 通道关闭时清理登记，避免陈旧条目影响后续配对。 */
    static void forget(final MetalRenderPass renderPass) {
        synchronized (PENDING) {
            BY_FRONTEND.values().removeIf(value -> value == renderPass);
            PENDING.removeIf(value -> value == renderPass);
        }
    }

    /** 便于诊断：当前仍在等待配对的后端通道数量。 */
    static int pendingCount() {
        synchronized (PENDING) {
            return PENDING.size();
        }
    }
}
