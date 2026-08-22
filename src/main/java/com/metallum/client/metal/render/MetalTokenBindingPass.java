package com.metallum.client.metal.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;

/**
 * Metal-only binding surface for renderer-owned hot paths.
 *
 * <p>The public Blaze3D API deliberately remains name based. MetalUniversal's
 * private Iris/Sodium producers compile names into {@link MetalBindingToken}
 * objects before entering the draw loop and call this surface instead. The
 * compatibility name is retained only as the key for the existing resource
 * maps and diagnostics; it is not used to resolve the pipeline binding.</p>
 */
public interface MetalTokenBindingPass {
    void metallum$setUniform(
            MetalBindingToken token,
            String compatibilityName,
            GpuBufferSlice value
    );

    default void metallum$setUniform(
            final MetalBindingToken token,
            final String compatibilityName,
            final GpuBuffer value
    ) {
        metallum$setUniform(token, compatibilityName, value.slice());
    }

    void metallum$bindTexture(
            MetalBindingToken token,
            String compatibilityName,
            @Nullable GpuTextureView textureView,
            @Nullable GpuSampler sampler
    );

    void metallum$bindStorageImage(
            MetalBindingToken token,
            String compatibilityName,
            GpuTextureView textureView
    );
}
