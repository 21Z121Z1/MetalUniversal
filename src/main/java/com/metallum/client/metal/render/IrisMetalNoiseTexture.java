package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.OptionalDouble;
import java.util.Random;

/** Metal-owned implementation of Iris's {@code noisetex} resource. */
@Environment(EnvType.CLIENT)
final class IrisMetalNoiseTexture implements AutoCloseable {
    private static final int USAGE = GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_COPY_DST
            | GpuTexture.USAGE_COPY_SRC;

    private final MetalGpuTexture texture;
    private final MetalGpuTextureView view;
    private final MetalGpuSampler sampler;
    private final String source;
    private boolean closed;

    IrisMetalNoiseTexture(
            final MetalDevice device,
            final int defaultResolution,
            final @Nullable CustomTextureData customTexture
    ) {
        NativeImage image;
        boolean blur;
        boolean clamp;
        if (customTexture == null) {
            image = createDefaultNoise(defaultResolution);
            blur = true;
            clamp = false;
            this.source = "iris-default-noise";
        } else if (customTexture instanceof CustomTextureData.PngData png) {
            try {
                image = NativeImage.read(png.getContent());
            } catch (IOException exception) {
                throw new IllegalArgumentException("Failed to decode Iris custom noise PNG", exception);
            }
            blur = png.getFilteringData().shouldBlur();
            clamp = png.getFilteringData().shouldClamp();
            this.source = "pack-noise-png";
        } else {
            throw new UnsupportedOperationException(
                    "Iris custom noise texture kind is not implemented on Metal: "
                            + customTexture.getClass().getSimpleName()
            );
        }

        try (image) {
            this.texture = (MetalGpuTexture) device.createTexture(
                    "metallum:iris_noisetex",
                    USAGE,
                    GpuFormat.RGBA8_UNORM,
                    image.getWidth(),
                    image.getHeight(),
                    1,
                    1
            );
            this.texture.registerValidationIdentity();
            this.view = (MetalGpuTextureView) device.createTextureView(this.texture);
            AddressMode addressMode = clamp ? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT;
            FilterMode filterMode = blur ? FilterMode.LINEAR : FilterMode.NEAREST;
            this.sampler = new MetalGpuSampler(
                    device,
                    addressMode,
                    addressMode,
                    filterMode,
                    filterMode,
                    1,
                    OptionalDouble.of(0.0)
            );

            ByteBuffer pixels = image.getPixelBytes().duplicate();
            pixels.position(0);
            device.commandEncoder().writeToTexture(
                    this.texture,
                    pixels,
                    0,
                    0,
                    0,
                    0,
                    image.getWidth(),
                    image.getHeight()
            );
        }
    }

    private static NativeImage createDefaultNoise(final int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Iris noise texture resolution must be positive: " + size);
        }
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, size, size, false);
        Random random = new Random(0);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                image.setPixel(x, y, random.nextInt() | 0xFF000000);
            }
        }
        return image;
    }

    MetalRenderPass.TextureViewAndSampler binding() {
        ensureOpen();
        return new MetalRenderPass.TextureViewAndSampler(this.view, this.sampler);
    }

    MetalGpuTexture texture() {
        ensureOpen();
        return this.texture;
    }

    String source() {
        return this.source;
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Iris noise texture is closed");
        }
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
