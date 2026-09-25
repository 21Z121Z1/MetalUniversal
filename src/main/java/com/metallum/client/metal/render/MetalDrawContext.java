package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.caffeinemc.mods.sodium.client.gpu.device.context.VKIndirectContext;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;


/**
 * Optional Sodium 26.3 adapter. Region state is submitted through the public
 * RenderPearl push-constant contract so Sodium never owns or unwraps the Metal
 * backend pass. Vanilla terrain and Sodium therefore share the same backend ABI.
 */
public final class MetalDrawContext extends VKIndirectContext {
    @Override
    public void setContext(final RenderPass pass, final RenderPipeline pipeline) {
        this.pass = pass;
    }
}
