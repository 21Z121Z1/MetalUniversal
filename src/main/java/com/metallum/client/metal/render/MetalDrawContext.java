package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.caffeinemc.mods.sodium.client.gpu.device.context.VKIndirectContext;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Optional Sodium 26.3 adapter. Region state is submitted through the public
 * RenderPearl push-constant contract so Sodium never owns or unwraps the Metal
 * backend pass. Vanilla terrain and Sodium therefore share the same backend ABI.
 */
public final class MetalDrawContext extends VKIndirectContext {
    private static final int TERRAIN_PUSH_CONSTANT_BYTES = 20;

    private final ByteBuffer pushConstants = ByteBuffer
            .allocateDirect(TERRAIN_PUSH_CONSTANT_BYTES)
            .order(ByteOrder.nativeOrder());

    @Override
    public void setContext(final RenderPass pass, final RenderPipeline pipeline) {
        this.pass = pass;
    }

    @Override
    public void updateData(final RenderRegion region, final CameraTransform camera) {
        float x = getCameraTranslation(region.getOriginX(), camera.intX, camera.fracX);
        float y = getCameraTranslation(region.getOriginY(), camera.intY, camera.fracY);
        float z = getCameraTranslation(region.getOriginZ(), camera.intZ, camera.fracZ);

        this.pushConstants.clear();
        this.pushConstants.putFloat(x);
        this.pushConstants.putFloat(y);
        this.pushConstants.putFloat(z);
        this.pushConstants.putInt(Math.toIntExact(System.currentTimeMillis() - region.getCreationTime()));
        this.pushConstants.putInt(region.getId());
        this.pushConstants.flip();
        this.pass.pushConstants(this.pushConstants);
    }
}
