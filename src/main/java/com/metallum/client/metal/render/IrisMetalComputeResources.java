package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gl.buffer.BuiltShaderStorageInfo;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.shaderpack.ImageInformation;
import net.irisshaders.iris.shaderpack.ShaderPack;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/** Generation-owned SSBO and custom-image resources shared by every Iris compute stage. */
@Environment(EnvType.CLIENT)
final class IrisMetalComputeResources implements AutoCloseable {
    private static final int ZERO_CHUNK_BYTES = 1024 * 1024;
    private static final int BUFFER_USAGE = GpuBuffer.USAGE_COPY_SRC
            | GpuBuffer.USAGE_COPY_DST
            | GpuBuffer.USAGE_INDIRECT_PARAMETERS;
    private static final int IMAGE_USAGE = GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_RENDER_ATTACHMENT
            | GpuTexture.USAGE_COPY_SRC
            | GpuTexture.USAGE_COPY_DST
            | MetalGpuTexture.USAGE_SHADER_WRITE;

    private final MetalDevice device;
    private final Map<Integer, BuiltShaderStorageInfo> bufferDefinitions = new LinkedHashMap<>();
    private final Map<Integer, MetalGpuBuffer> buffers = new LinkedHashMap<>();
    private final Map<String, OwnedImage> imagesByName = new LinkedHashMap<>();
    private final Map<String, OwnedImage> imagesBySampler = new LinkedHashMap<>();
    private int width;
    private int height;
    private boolean closed;

    IrisMetalComputeResources(
            final MetalDevice device,
            final ShaderPack pack,
            final int width,
            final int height
    ) {
        this.device = Objects.requireNonNull(device, "device");
        Objects.requireNonNull(pack, "pack");
        validateExtent(width, height);
        validatePack(pack);
        this.width = width;
        this.height = height;
        pack.getBufferObjects().forEach((int binding, BuiltShaderStorageInfo info) -> {
            if (binding < 0) {
                throw new IllegalArgumentException("Iris SSBO binding must be non-negative: " + binding);
            }
            this.bufferDefinitions.put(binding, Objects.requireNonNull(info, "SSBO " + binding));
        });
        try {
            this.bufferDefinitions.forEach((binding, info) ->
                    this.buffers.put(binding, createBuffer(binding, info, width, height)));
            for (ImageInformation image : pack.getIrisCustomImages()) {
                addImage(image, width, height);
            }
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    @Nullable GpuBufferSlice storageBuffer(final int binding) {
        ensureOpen();
        MetalGpuBuffer buffer = this.buffers.get(binding);
        return buffer == null ? null : buffer.slice();
    }

    IrisMetalPostChain.@Nullable TextureBinding sampledImage(final String samplerName) {
        ensureOpen();
        OwnedImage image = this.imagesBySampler.get(samplerName);
        return image == null ? null : image.sampledBinding();
    }

    @Nullable MetalGpuTextureView storageImage(final String imageName) {
        ensureOpen();
        OwnedImage image = this.imagesByName.get(imageName);
        return image == null ? null : image.view;
    }

    void clearForFrame(final MetalCommandEncoder encoder) {
        ensureOpen();
        for (OwnedImage image : this.imagesByName.values()) {
            if (image.definition.clear()) {
                encoder.clearColorTexture(image.texture, new Vector4f());
            }
        }
    }

    void resize(final int newWidth, final int newHeight) {
        ensureOpen();
        validateExtent(newWidth, newHeight);
        if (newWidth == this.width && newHeight == this.height) {
            return;
        }
        this.width = newWidth;
        this.height = newHeight;
        for (Map.Entry<Integer, BuiltShaderStorageInfo> entry : this.bufferDefinitions.entrySet()) {
            if (!entry.getValue().relative()) {
                continue;
            }
            MetalGpuBuffer old = this.buffers.put(
                    entry.getKey(), createBuffer(entry.getKey(), entry.getValue(), newWidth, newHeight)
            );
            old.close();
        }
        for (OwnedImage image : this.imagesByName.values().toArray(OwnedImage[]::new)) {
            if (!image.definition.isRelative()) {
                continue;
            }
            replaceImage(image.definition, newWidth, newHeight);
        }
    }

    private MetalGpuBuffer createBuffer(
            final int binding,
            final BuiltShaderStorageInfo info,
            final int width,
            final int height
    ) {
        long size = bufferSize(info, width, height);
        if (size <= 0L) {
            throw new IllegalArgumentException(
                    "Iris SSBO " + binding + " resolved to non-positive size " + size
            );
        }
        MetalGpuBuffer buffer = (MetalGpuBuffer) this.device.createBuffer(
                () -> "metallum:iris_ssbo/" + binding,
                BUFFER_USAGE,
                size
        );
        try {
            zero(buffer);
            byte[] content = info.content();
            if (!info.relative() && content != null) {
                if (content.length > size) {
                    throw new IllegalArgumentException(
                            "Iris SSBO " + binding + " initial content is " + content.length
                                    + " bytes but allocation is " + size
                    );
                }
                ByteBuffer initial = ByteBuffer.allocateDirect(content.length);
                initial.put(content).flip();
                this.device.commandEncoder().writeToBuffer(buffer.slice(0L, content.length), initial);
            }
            return buffer;
        } catch (RuntimeException | Error failure) {
            buffer.close();
            throw failure;
        }
    }

    private void zero(final MetalGpuBuffer buffer) {
        int chunkSize = Math.toIntExact(Math.min(buffer.size(), ZERO_CHUNK_BYTES));
        ByteBuffer zeroes = ByteBuffer.allocateDirect(chunkSize);
        for (long offset = 0L; offset < buffer.size(); offset += chunkSize) {
            int length = Math.toIntExact(Math.min(chunkSize, buffer.size() - offset));
            ByteBuffer chunk = zeroes.duplicate();
            chunk.limit(length);
            this.device.commandEncoder().writeToBuffer(buffer.slice(offset, length), chunk);
        }
    }

    static long bufferSize(final BuiltShaderStorageInfo info, final int width, final int height) {
        if (!info.relative()) {
            return info.size();
        }
        long scaledWidth = (long) (width * info.scaleX());
        long scaledHeight = (long) (height * info.scaleY());
        return Math.multiplyExact(Math.multiplyExact(scaledWidth, scaledHeight), info.size());
    }

    private void addImage(final ImageInformation definition, final int width, final int height) {
        Objects.requireNonNull(definition, "custom image");
        if (definition.target() != TextureType.TEXTURE_2D) {
            throw new UnsupportedOperationException(
                    "Iris custom image '" + definition.name() + "' uses " + definition.target()
                            + "; Metal admission currently supports exact 2D images only"
            );
        }
        if (this.imagesByName.containsKey(definition.name())) {
            throw new IllegalArgumentException("Duplicate Iris custom image name '" + definition.name() + "'");
        }
        if (definition.samplerName() != null && this.imagesBySampler.containsKey(definition.samplerName())) {
            throw new IllegalArgumentException(
                    "Duplicate Iris custom image sampler '" + definition.samplerName() + "'"
            );
        }
        OwnedImage image = createImage(definition, width, height);
        this.imagesByName.put(definition.name(), image);
        if (definition.samplerName() != null) {
            this.imagesBySampler.put(definition.samplerName(), image);
        }
    }

    private void replaceImage(final ImageInformation definition, final int width, final int height) {
        OwnedImage replacement = createImage(definition, width, height);
        OwnedImage old = this.imagesByName.put(definition.name(), replacement);
        if (definition.samplerName() != null) {
            this.imagesBySampler.put(definition.samplerName(), replacement);
        }
        old.close();
    }

    /** Validates declarations without allocating buffers or textures. */
    static void validatePack(final ShaderPack pack) {
        Objects.requireNonNull(pack, "pack");
        pack.getBufferObjects().forEach((int binding, BuiltShaderStorageInfo info) -> {
            if (binding < 0) {
                throw new IllegalArgumentException("Iris SSBO binding must be non-negative: " + binding);
            }
            Objects.requireNonNull(info, "SSBO " + binding);
            if (info.size() <= 0L) {
                throw new IllegalArgumentException("Iris SSBO " + binding + " size must be positive");
            }
            if (info.relative()) {
                if (!Float.isFinite(info.scaleX()) || info.scaleX() <= 0.0F
                        || !Float.isFinite(info.scaleY()) || info.scaleY() <= 0.0F) {
                    throw new IllegalArgumentException(
                            "Iris relative SSBO " + binding + " has invalid scale "
                                    + info.scaleX() + 'x' + info.scaleY()
                    );
                }
            } else if (info.content() != null && info.content().length > info.size()) {
                throw new IllegalArgumentException(
                        "Iris SSBO " + binding + " initial content is " + info.content().length
                                + " bytes but allocation is " + info.size()
                );
            }
        });

        Map<String, ImageInformation> names = new LinkedHashMap<>();
        Map<String, ImageInformation> samplers = new LinkedHashMap<>();
        for (ImageInformation image : pack.getIrisCustomImages()) {
            Objects.requireNonNull(image, "custom image");
            if (image.target() != TextureType.TEXTURE_2D) {
                throw new UnsupportedOperationException(
                        "Iris custom image '" + image.name() + "' uses " + image.target()
                                + "; Metal admission currently supports exact 2D images only"
                );
            }
            if (image.depth() > 1) {
                throw new IllegalArgumentException(
                        "Iris custom image '" + image.name() + "' has unsupported depth " + image.depth()
                );
            }
            if (image.isRelative()) {
                if (!Float.isFinite(image.relativeWidth()) || image.relativeWidth() <= 0.0F
                        || !Float.isFinite(image.relativeHeight()) || image.relativeHeight() <= 0.0F) {
                    throw new IllegalArgumentException(
                            "Iris relative custom image '" + image.name() + "' has invalid scale "
                                    + image.relativeWidth() + 'x' + image.relativeHeight()
                    );
                }
            } else if (image.width() <= 0 || image.height() <= 0) {
                throw new IllegalArgumentException(
                        "Iris custom image '" + image.name() + "' has invalid extent "
                                + image.width() + 'x' + image.height()
                );
            }
            imageFormat(image.internalTextureFormat());
            if (names.putIfAbsent(Objects.requireNonNull(image.name(), "custom image name"), image) != null) {
                throw new IllegalArgumentException("Duplicate Iris custom image name '" + image.name() + "'");
            }
            if (image.samplerName() != null && samplers.putIfAbsent(image.samplerName(), image) != null) {
                throw new IllegalArgumentException(
                        "Duplicate Iris custom image sampler '" + image.samplerName() + "'"
                );
            }
        }
    }

    private OwnedImage createImage(
            final ImageInformation definition,
            final int width,
            final int height
    ) {
        int imageWidth = definition.isRelative()
                ? (int) (width * definition.relativeWidth())
                : definition.width();
        int imageHeight = definition.isRelative()
                ? (int) (height * definition.relativeHeight())
                : definition.height();
        if (imageWidth <= 0 || imageHeight <= 0 || definition.depth() > 1) {
            throw new IllegalArgumentException(
                    "Iris custom image '" + definition.name() + "' has unsupported extent "
                            + imageWidth + "x" + imageHeight + "x" + definition.depth()
            );
        }
        GpuFormat format = imageFormat(definition.internalTextureFormat());
        MetalGpuTexture texture = null;
        MetalGpuTextureView view = null;
        MetalGpuSampler sampler = null;
        try {
            texture = (MetalGpuTexture) this.device.createTexture(
                    "metallum:iris_image/" + definition.name(),
                    IMAGE_USAGE,
                    format,
                    imageWidth,
                    imageHeight,
                    1,
                    1
            );
            texture.registerValidationIdentity();
            view = (MetalGpuTextureView) this.device.createTextureView(texture);
            boolean integer = format.componentType().name().startsWith("UINT")
                    || format.componentType().name().startsWith("SINT");
            FilterMode filter = integer ? FilterMode.NEAREST : FilterMode.LINEAR;
            sampler = new MetalGpuSampler(
                    this.device,
                    AddressMode.CLAMP_TO_EDGE,
                    AddressMode.CLAMP_TO_EDGE,
                    filter,
                    filter,
                    1,
                    OptionalDouble.of(0.0)
            );
            this.device.commandEncoder().clearColorTexture(texture, new Vector4f());
            return new OwnedImage(definition, texture, view, sampler);
        } catch (RuntimeException | Error failure) {
            if (view != null) {
                view.close();
            }
            if (texture != null) {
                texture.close();
            }
            if (sampler != null) {
                sampler.close();
            }
            throw failure;
        }
    }

    static GpuFormat imageFormat(final InternalTextureFormat format) {
        return switch (format) {
            case RGBA, RGBA8 -> GpuFormat.RGBA8_UNORM;
            case R8 -> GpuFormat.R8_UNORM;
            case RG8 -> GpuFormat.RG8_UNORM;
            case R8_SNORM -> GpuFormat.R8_SNORM;
            case RG8_SNORM -> GpuFormat.RG8_SNORM;
            case RGBA8_SNORM -> GpuFormat.RGBA8_SNORM;
            case R16 -> GpuFormat.R16_UNORM;
            case RG16 -> GpuFormat.RG16_UNORM;
            case RGBA16 -> GpuFormat.RGBA16_UNORM;
            case R16_SNORM -> GpuFormat.R16_SNORM;
            case RG16_SNORM -> GpuFormat.RG16_SNORM;
            case RGBA16_SNORM -> GpuFormat.RGBA16_SNORM;
            case R16F -> GpuFormat.R16_FLOAT;
            case RG16F -> GpuFormat.RG16_FLOAT;
            case RGBA16F -> GpuFormat.RGBA16_FLOAT;
            case R32F -> GpuFormat.R32_FLOAT;
            case RG32F -> GpuFormat.RG32_FLOAT;
            case RGBA32F -> GpuFormat.RGBA32_FLOAT;
            case R8I -> GpuFormat.R8_SINT;
            case RG8I -> GpuFormat.RG8_SINT;
            case RGBA8I -> GpuFormat.RGBA8_SINT;
            case R8UI -> GpuFormat.R8_UINT;
            case RG8UI -> GpuFormat.RG8_UINT;
            case RGBA8UI -> GpuFormat.RGBA8_UINT;
            case R16I -> GpuFormat.R16_SINT;
            case RG16I -> GpuFormat.RG16_SINT;
            case RGBA16I -> GpuFormat.RGBA16_SINT;
            case R16UI -> GpuFormat.R16_UINT;
            case RG16UI -> GpuFormat.RG16_UINT;
            case RGBA16UI -> GpuFormat.RGBA16_UINT;
            case R32I -> GpuFormat.R32_SINT;
            case RG32I -> GpuFormat.RG32_SINT;
            case RGBA32I -> GpuFormat.RGBA32_SINT;
            case R32UI -> GpuFormat.R32_UINT;
            case RG32UI -> GpuFormat.RG32_UINT;
            case RGBA32UI -> GpuFormat.RGBA32_UINT;
            case RGB10_A2 -> GpuFormat.RGB10A2_UNORM;
            case RGB10_A2UI -> GpuFormat.RGB10A2_UINT;
            case R11F_G11F_B10F -> GpuFormat.RG11B10_FLOAT;
            default -> throw new UnsupportedOperationException(
                    "Iris custom image format " + format
                            + " has no exact Metal/GpuFormat storage-image representation"
            );
        };
    }

    private static void validateExtent(final int width, final int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Iris compute resource extent must be positive: " + width + "x" + height);
        }
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Iris Metal compute resources are closed");
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.buffers.values().forEach(MetalGpuBuffer::close);
        this.buffers.clear();
        this.imagesByName.values().forEach(OwnedImage::close);
        this.imagesByName.clear();
        this.imagesBySampler.clear();
    }

    private static final class OwnedImage implements AutoCloseable {
        private final ImageInformation definition;
        private final MetalGpuTexture texture;
        private final MetalGpuTextureView view;
        private final MetalGpuSampler sampler;

        private OwnedImage(
                final ImageInformation definition,
                final MetalGpuTexture texture,
                final MetalGpuTextureView view,
                final MetalGpuSampler sampler
        ) {
            this.definition = definition;
            this.texture = texture;
            this.view = view;
            this.sampler = sampler;
        }

        private IrisMetalPostChain.TextureBinding sampledBinding() {
            return new IrisMetalPostChain.TextureBinding(this.view, this.sampler);
        }

        @Override
        public void close() {
            this.view.close();
            this.texture.close();
            this.sampler.close();
        }
    }
}
