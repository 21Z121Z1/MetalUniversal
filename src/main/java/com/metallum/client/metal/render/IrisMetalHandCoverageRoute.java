package com.metallum.client.metal.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import org.jspecify.annotations.Nullable;

/** Keeps the hand coverage descriptor in lockstep with the ShaderKey that owns the PSO target. */
public final class IrisMetalHandCoverageRoute {
    private IrisMetalHandCoverageRoute() {
    }

    public static RenderPassDescriptor appendIfHand(
            final RenderPipeline source,
            final @Nullable WorldRenderingPipeline worldPipeline,
            final RenderPassDescriptor descriptor
    ) {
        ShaderKey key = IrisMetalCoreGbufferPipelines.resolve(source, worldPipeline);
        return key != null && IrisMetalHandCoverageRuntime.isHandKey(key)
                ? IrisMetalHandCoverageRuntime.appendToHandDescriptor(descriptor)
                : descriptor;
    }
}
