package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.OptionalDouble;

/** Iris's explicit white level sampler for vertex formats without texture/light/overlay inputs. */
@Environment(EnvType.CLIENT)
final class IrisMetalWhitePixel implements AutoCloseable {
    private final GpuTexture texture;
    private final GpuTextureView view;
    private final MetalGpuSampler sampler;
    private boolean closed;

    IrisMetalWhitePixel(final MetalDevice device) {
        this.texture = device.createTexture(
                () -> "metallum:iris_white_pixel",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM,
                1,
                1,
                1,
                1
        );
        this.view = device.createTextureView(this.texture);
        this.sampler = new MetalGpuSampler(
                device,
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST,
                FilterMode.NEAREST,
                1,
                OptionalDouble.empty()
        );
        ByteBuffer white = ByteBuffer.allocateDirect(Integer.BYTES).order(ByteOrder.nativeOrder());
        white.putInt(0, 0xFFFFFFFF);
        device.createCommandEncoder().writeToTexture(this.texture, white, 0, 0, 0, 0, 1, 1);
    }

    MetalRenderPass.TextureViewAndSampler binding() {
        if (this.closed) {
            throw new IllegalStateException("Iris white pixel is closed");
        }
        return new MetalRenderPass.TextureViewAndSampler(this.view, this.sampler);
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.view.close();
        this.texture.close();
        this.sampler.close();
    }
}
