package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.metallum.client.metal.render.mtl.MTLSamplerMipFilter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.PixelFormat;
import net.irisshaders.iris.gl.texture.PixelType;
import net.irisshaders.iris.gl.texture.ShaderDataType;
import net.irisshaders.iris.pbr.format.TextureFormat;
import net.irisshaders.iris.pbr.format.TextureFormatLoader;
import net.irisshaders.iris.pbr.texture.PBRTextureHolder;
import net.irisshaders.iris.pbr.texture.PBRTextureManager;
import net.irisshaders.iris.pbr.texture.PBRType;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.apache.commons.io.FilenameUtils;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/** Metal-owned implementation of Iris stage-local and global shader-pack custom textures. */
@Environment(EnvType.CLIENT)
final class IrisMetalCustomTextures implements AutoCloseable {
    private static final int USAGE = GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_COPY_DST
            | GpuTexture.USAGE_COPY_SRC;

    private final MetalDevice device;
    private final EnumMap<TextureStage, Map<String, CustomTextureData>> stageDefinitions;
    private final Map<String, CustomTextureData> globalDefinitions;
    private final LiveTextureResolver liveTextureResolver;
    private final Map<Key, OwnedTexture> loaded = new HashMap<>();
    private boolean closed;

    IrisMetalCustomTextures(final MetalDevice device, final ShaderPack pack) {
        this(
                device,
                Objects.requireNonNull(pack, "pack").getCustomTextureDataMap(),
                pack.getIrisCustomTextureDataMap()
        );
    }

    /** Package-private map seam keeps focused tests independent of a complete shader-pack parse. */
    IrisMetalCustomTextures(
            final MetalDevice device,
            final Map<TextureStage, ? extends Map<String, CustomTextureData>> definitions
    ) {
        this(device, definitions, Map.of());
    }

    IrisMetalCustomTextures(
            final MetalDevice device,
            final Map<TextureStage, ? extends Map<String, CustomTextureData>> definitions,
            final Map<String, CustomTextureData> globalDefinitions
    ) {
        this(device, definitions, globalDefinitions, (stage, samplerName, data) ->
                resolveMinecraftTexture(device, stage, samplerName, data));
    }

    IrisMetalCustomTextures(
            final MetalDevice device,
            final Map<TextureStage, ? extends Map<String, CustomTextureData>> definitions,
            final LiveTextureResolver liveTextureResolver
    ) {
        this(device, definitions, Map.of(), liveTextureResolver);
    }

    IrisMetalCustomTextures(
            final MetalDevice device,
            final Map<TextureStage, ? extends Map<String, CustomTextureData>> definitions,
            final Map<String, CustomTextureData> globalDefinitions,
            final LiveTextureResolver liveTextureResolver
    ) {
        this.device = Objects.requireNonNull(device, "device");
        this.stageDefinitions = copyDefinitions(Objects.requireNonNull(definitions, "definitions"));
        this.globalDefinitions = copyGlobalDefinitions(
                Objects.requireNonNull(globalDefinitions, "globalDefinitions")
        );
        this.liveTextureResolver = Objects.requireNonNull(liveTextureResolver, "liveTextureResolver");
    }

    /**
     * Resolves the first sampler alias exactly as Iris's custom-texture registration does. A
     * stage-local declaration overrides a same-name global declaration, and either kind overrides
     * standard samplers because callers ask this layer first.
     */
    synchronized MetalRenderPass.@Nullable TextureViewAndSampler resolve(
            final TextureStage stage,
            final String... samplerNames
    ) {
        ensureOpen();
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(samplerNames, "samplerNames");
        Map<String, CustomTextureData> stageDefinitions = this.stageDefinitions.get(stage);
        for (String samplerName : samplerNames) {
            Objects.requireNonNull(samplerName, "samplerName");
            boolean stageLocal = stageDefinitions != null && stageDefinitions.containsKey(samplerName);
            if (!stageLocal && !this.globalDefinitions.containsKey(samplerName)) {
                continue;
            }
            CustomTextureData data = stageLocal
                    ? stageDefinitions.get(samplerName)
                    : this.globalDefinitions.get(samplerName);
            if (data instanceof CustomTextureData.LightmapMarker
                    || data instanceof CustomTextureData.ResourceData) {
                MetalRenderPass.TextureViewAndSampler binding =
                        this.liveTextureResolver.resolve(stage, samplerName, data);
                if (binding == null) {
                    throw unsupported(stage, samplerName, data, "live texture resolver returned no binding");
                }
                return binding;
            }
            Key key = new Key(stageLocal ? stage : null, samplerName);
            OwnedTexture texture = this.loaded.get(key);
            if (texture == null) {
                texture = create(stage, samplerName, data);
                this.loaded.put(key, texture);
            }
            return texture.binding();
        }
        return null;
    }

    /** Returns the stage override when present, otherwise the caller's standard binding. */
    synchronized MetalRenderPass.@Nullable TextureViewAndSampler overrideOrDefault(
            final TextureStage stage,
            final MetalRenderPass.@Nullable TextureViewAndSampler standard,
            final String... samplerNames
    ) {
        MetalRenderPass.TextureViewAndSampler override = resolve(stage, samplerNames);
        return override == null ? standard : override;
    }

    synchronized boolean hasOverride(final TextureStage stage, final String samplerName) {
        ensureOpen();
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(samplerName, "samplerName");
        Map<String, CustomTextureData> stageDefinitions = this.stageDefinitions.get(stage);
        return (stageDefinitions != null && stageDefinitions.containsKey(samplerName))
                || this.globalDefinitions.containsKey(samplerName);
    }

    /** Materializes owned data and validates every live alias before any render encoder is active. */
    synchronized void prewarmAll() {
        ensureOpen();
        for (Map.Entry<TextureStage, Map<String, CustomTextureData>> stage : this.stageDefinitions.entrySet()) {
            for (String samplerName : stage.getValue().keySet()) {
                resolve(stage.getKey(), samplerName);
            }
        }
        for (String samplerName : this.globalDefinitions.keySet()) {
            resolve(TextureStage.SETUP, samplerName);
        }
    }

    /** Validates all declarations without allocating GPU resources or mutating Iris render state. */
    static void validatePack(final ShaderPack pack) {
        Objects.requireNonNull(pack, "pack").getCustomTextureDataMap().forEach((stage, entries) ->
                entries.forEach((name, data) -> validateDeclaration(stage, name, data))
        );
        pack.getIrisCustomTextureDataMap().forEach((name, data) ->
                validateDeclaration(TextureStage.SETUP, name, data)
        );
    }

    static void validateDeclaration(
            final TextureStage stage,
            final String samplerName,
            final @Nullable CustomTextureData data
    ) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(samplerName, "samplerName");
        if (data instanceof CustomTextureData.PngData png) {
            try (NativeImage image = NativeImage.read(png.getContent())) {
                if (image.getWidth() <= 0 || image.getHeight() <= 0) {
                    throw new IllegalArgumentException(
                            "Invalid Iris custom texture PNG extent: stage=" + stage
                                    + ", sampler=" + samplerName + ", extent="
                                    + image.getWidth() + 'x' + image.getHeight()
                    );
                }
            } catch (IOException exception) {
                throw new IllegalArgumentException(
                        "Failed to decode Iris custom texture PNG during Metal admission: stage=" + stage
                                + ", sampler=" + samplerName,
                        exception
                );
            }
            return;
        }
        if (data instanceof CustomTextureData.RawData raw) {
            RawExtent extent = rawExtent(stage, samplerName, raw);
            if (extent.rectangle() && !raw.getFilteringData().shouldClamp()) {
                throw unsupported(
                        stage, samplerName, raw,
                        "sampler2DRect with repeat addressing has no exact unnormalized Metal sampler"
                );
            }
            GpuFormat format = rawFormat(stage, samplerName, raw);
            convertRaw(stage, samplerName, raw, format, extent.texelCount());
            return;
        }
        if (data instanceof CustomTextureData.ResourceData resource) {
            resourceRequest(resource);
            return;
        }
        if (!(data instanceof CustomTextureData.LightmapMarker)) {
            throw unsupported(stage, samplerName, data, "no Metal resource alias exists for this data kind");
        }
    }

    private OwnedTexture create(
            final TextureStage stage,
            final String samplerName,
            final @Nullable CustomTextureData data
    ) {
        if (data instanceof CustomTextureData.PngData png) {
            return createPng(stage, samplerName, png);
        }
        if (data instanceof CustomTextureData.RawData raw) {
            return createRaw(stage, samplerName, raw);
        }
        throw unsupported(stage, samplerName, data, "no Metal resource alias exists for this data kind");
    }

    private static MetalRenderPass.TextureViewAndSampler resolveMinecraftTexture(
            final MetalDevice device,
            final TextureStage stage,
            final String samplerName,
            final CustomTextureData data
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gameRenderer == null) {
            throw unsupported(stage, samplerName, data, "Minecraft renderer is not available");
        }
        if (data instanceof CustomTextureData.LightmapMarker) {
            GpuTextureView view = minecraft.gameRenderer.levelLightmap();
            GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            return checkedLiveBinding(device, stage, samplerName, data, view, sampler);
        }
        if (!(data instanceof CustomTextureData.ResourceData resource)) {
            throw unsupported(stage, samplerName, data, "not a live Minecraft texture declaration");
        }

        ResourceRequest request = resourceRequest(resource);
        if (minecraft.getResourceManager().getResource(request.requested()).isEmpty()) {
            throw unsupported(
                    stage, samplerName, data,
                    "resource does not exist: " + request.requested()
            );
        }
        if (minecraft.getResourceManager().getResource(request.base()).isEmpty()) {
            throw unsupported(
                    stage, samplerName, data,
                    "PBR base resource does not exist: " + request.base()
            );
        }

        TextureManager textureManager = minecraft.getTextureManager();
        AbstractTexture texture = textureManager.getTexture(request.base());
        PBRType pbrType = request.pbrType();
        if (pbrType != null) {
            if (!(texture.getTexture() instanceof MetalGpuTexture baseTexture)
                    || baseTexture.isClosed()
                    || !baseTexture.isOwnedBy(device)) {
                throw unsupported(
                        stage, samplerName, data,
                        "PBR base texture is not a live texture on the current Metal device"
                );
            }
            PBRTextureHolder holder = PBRTextureManager.INSTANCE.getOrLoadHolder(baseTexture.iris$getGlId());
            texture = switch (pbrType) {
                case NORMAL -> holder.normalTexture();
                case SPECULAR -> holder.specularTexture();
            };
            TextureFormat format = TextureFormatLoader.getFormat();
            if (format != null) {
                format.setupTextureParameters(pbrType, texture);
            }
        }
        return checkedLiveBinding(
                device, stage, samplerName, data,
                texture.getTextureView(), texture.getSampler()
        );
    }

    static ResourceRequest resourceRequest(final CustomTextureData.ResourceData resource) {
        String location = resource.getLocation();
        int extension = FilenameUtils.indexOfExtension(location);
        String stem = extension < 0 ? location : location.substring(0, extension);
        PBRType pbrType = PBRType.fromFileLocation(stem);
        Identifier requested = Identifier.fromNamespaceAndPath(resource.getNamespace(), location);
        if (pbrType == null) {
            return new ResourceRequest(requested, requested, null);
        }
        if (extension < 0) {
            throw new IllegalArgumentException(
                    "Iris PBR ResourceData requires a file extension: " + requested
            );
        }
        String baseLocation = location.substring(0, extension - pbrType.getSuffix().length())
                + location.substring(extension);
        return new ResourceRequest(
                requested,
                Identifier.fromNamespaceAndPath(resource.getNamespace(), baseLocation),
                pbrType
        );
    }

    static MetalRenderPass.TextureViewAndSampler checkedExternalBinding(
            final MetalDevice device,
            final @Nullable GpuTextureView view,
            final @Nullable GpuSampler sampler,
            final String label
    ) {
        if (!(view instanceof MetalGpuTextureView metalView)
                || !(metalView.texture() instanceof MetalGpuTexture texture)
                || !(sampler instanceof MetalGpuSampler metalSampler)
                || metalView.isClosed()
                || texture.isClosed()
                || metalSampler.isClosed()
                || !texture.isOwnedBy(device)
                || !metalSampler.isOwnedBy(device)
                || (texture.usage() & GpuTexture.USAGE_TEXTURE_BINDING) == 0) {
            throw new IllegalStateException(
                    "Iris external texture '" + label
                            + "' is absent, stale, or owned by another backend/device"
            );
        }
        return new MetalRenderPass.TextureViewAndSampler(metalView, metalSampler);
    }

    private static MetalRenderPass.TextureViewAndSampler checkedLiveBinding(
            final MetalDevice device,
            final TextureStage stage,
            final String samplerName,
            final CustomTextureData data,
            final @Nullable GpuTextureView view,
            final @Nullable GpuSampler sampler
    ) {
        try {
            return checkedExternalBinding(device, view, sampler, stage + "/" + samplerName);
        } catch (IllegalStateException failure) {
            throw unsupported(stage, samplerName, data, failure.getMessage());
        }
    }

    private OwnedTexture createPng(
            final TextureStage stage,
            final String samplerName,
            final CustomTextureData.PngData png
    ) {
        NativeImage image;
        try {
            image = NativeImage.read(png.getContent());
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Failed to decode Iris custom texture PNG: stage=" + stage
                            + ", sampler=" + samplerName + ", type=PngData",
                    exception
            );
        }

        MetalGpuTexture texture = null;
        MetalGpuTextureView view = null;
        MetalGpuSampler sampler = null;
        try (image) {
            texture = (MetalGpuTexture) this.device.createTexture(
                    "metallum:iris_custom/" + stage.name().toLowerCase(Locale.ROOT) + "/" + samplerName,
                    USAGE,
                    GpuFormat.RGBA8_UNORM,
                    image.getWidth(),
                    image.getHeight(),
                    1,
                    1
            );
            texture.registerValidationIdentity();
            view = (MetalGpuTextureView) this.device.createTextureView(texture);
            boolean clamp = png.getFilteringData().shouldClamp();
            boolean blur = png.getFilteringData().shouldBlur();
            AddressMode addressMode = clamp ? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT;
            FilterMode filterMode = blur ? FilterMode.LINEAR : FilterMode.NEAREST;
            sampler = new MetalGpuSampler(
                    this.device,
                    addressMode,
                    addressMode,
                    filterMode,
                    filterMode,
                    1,
                    OptionalDouble.of(0.0)
            );

            ByteBuffer pixels = image.getPixelBytes().duplicate();
            pixels.position(0);
            this.device.commandEncoder().writeToTexture(
                    texture,
                    pixels,
                    0,
                    0,
                    0,
                    0,
                    image.getWidth(),
                    image.getHeight()
            );
            return new OwnedTexture(texture, view, sampler);
        } catch (RuntimeException | Error failure) {
            closePartial(texture, view, sampler);
            throw failure;
        }
    }

    private OwnedTexture createRaw(
            final TextureStage stage,
            final String samplerName,
            final CustomTextureData.RawData raw
    ) {
        RawExtent extent = rawExtent(stage, samplerName, raw);
        if (extent.rectangle() && !raw.getFilteringData().shouldClamp()) {
            throw unsupported(
                    stage, samplerName, raw,
                    "sampler2DRect with repeat addressing has no exact unnormalized Metal sampler"
            );
        }
        GpuFormat format = rawFormat(stage, samplerName, raw);
        ByteBuffer converted = convertRaw(stage, samplerName, raw, format, extent.texelCount());

        MetalGpuTexture texture = null;
        MetalGpuTextureView view = null;
        MetalGpuSampler sampler = null;
        try {
            String label = "metallum:iris_custom/" + stage.name().toLowerCase(Locale.ROOT) + '/' + samplerName;
            texture = new MetalGpuTexture(
                    this.device,
                    USAGE,
                    label,
                    format,
                    extent.width(),
                    extent.height(),
                    extent.depth(),
                    1,
                    extent.dimension()
            );
            texture.registerValidationIdentity();
            view = (MetalGpuTextureView) this.device.createTextureView(texture);
            boolean clamp = raw.getFilteringData().shouldClamp();
            boolean blur = raw.getFilteringData().shouldBlur();
            AddressMode addressMode = clamp ? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT;
            FilterMode filterMode = blur ? FilterMode.LINEAR : FilterMode.NEAREST;
            sampler = new MetalGpuSampler(
                    this.device,
                    addressMode,
                    addressMode,
                    filterMode,
                    filterMode,
                    1,
                    OptionalDouble.of(0.0),
                    null,
                    MTLSamplerMipFilter.NotMipmapped,
                    !extent.rectangle()
            );
            this.device.commandEncoder().writeToTextureVolume(
                    texture, converted, 0, 0, 0, 0,
                    extent.width(), extent.height(), extent.depth()
            );
            return new OwnedTexture(texture, view, sampler);
        } catch (RuntimeException | Error failure) {
            closePartial(texture, view, sampler);
            throw failure;
        }
    }

    private static RawExtent rawExtent(
            final TextureStage stage,
            final String samplerName,
            final CustomTextureData.RawData raw
    ) {
        RawExtent extent;
        if (raw instanceof CustomTextureData.RawData1D oneD) {
            extent = new RawExtent(oneD.getSizeX(), 1, 1, MetalTextureDimension.ONE_D, false);
        } else if (raw instanceof CustomTextureData.RawDataRect rectangle) {
            extent = new RawExtent(
                    rectangle.getSizeX(), rectangle.getSizeY(), 1,
                    MetalTextureDimension.TWO_D, true
            );
        } else if (raw instanceof CustomTextureData.RawData2D twoD) {
            extent = new RawExtent(twoD.getSizeX(), twoD.getSizeY(), 1, MetalTextureDimension.TWO_D, false);
        } else if (raw instanceof CustomTextureData.RawData3D threeD) {
            extent = new RawExtent(
                    threeD.getSizeX(), threeD.getSizeY(), threeD.getSizeZ(),
                    MetalTextureDimension.THREE_D, false
            );
        } else {
            throw unsupported(stage, samplerName, raw, "unknown RawData subclass");
        }
        if (extent.width() <= 0 || extent.height() <= 0 || extent.depth() <= 0) {
            throw new IllegalArgumentException(
                    "Invalid Iris raw custom texture extent: stage=" + stage + ", sampler=" + samplerName
                            + ", type=" + raw.getClass().getSimpleName() + ", extent="
                            + extent.width() + 'x' + extent.height() + 'x' + extent.depth()
            );
        }
        extent.texelCount();
        return extent;
    }

    private static GpuFormat rawFormat(
            final TextureStage stage,
            final String samplerName,
            final CustomTextureData.RawData raw
    ) {
        return switch (raw.getInternalFormat()) {
            case RGBA, RGBA8 -> GpuFormat.RGBA8_UNORM;
            case R8 -> GpuFormat.R8_UNORM;
            case RG8 -> GpuFormat.RG8_UNORM;
            case RGB8 -> GpuFormat.RGBA8_UNORM;
            case R8_SNORM -> GpuFormat.R8_SNORM;
            case RG8_SNORM -> GpuFormat.RG8_SNORM;
            case RGB8_SNORM -> GpuFormat.RGBA8_SNORM;
            case RGBA8_SNORM -> GpuFormat.RGBA8_SNORM;
            case R16 -> GpuFormat.R16_UNORM;
            case RG16 -> GpuFormat.RG16_UNORM;
            case RGB16 -> GpuFormat.RGBA16_UNORM;
            case RGBA16 -> GpuFormat.RGBA16_UNORM;
            case R16_SNORM -> GpuFormat.R16_SNORM;
            case RG16_SNORM -> GpuFormat.RG16_SNORM;
            case RGB16_SNORM -> GpuFormat.RGBA16_SNORM;
            case RGBA16_SNORM -> GpuFormat.RGBA16_SNORM;
            case R16F -> GpuFormat.R16_FLOAT;
            case RG16F -> GpuFormat.RG16_FLOAT;
            case RGB16F -> GpuFormat.RGBA16_FLOAT;
            case RGBA16F -> GpuFormat.RGBA16_FLOAT;
            case R32F -> GpuFormat.R32_FLOAT;
            case RG32F -> GpuFormat.RG32_FLOAT;
            case RGB32F -> GpuFormat.RGBA32_FLOAT;
            case RGBA32F -> GpuFormat.RGBA32_FLOAT;
            case R8I -> GpuFormat.R8_SINT;
            case RG8I -> GpuFormat.RG8_SINT;
            case RGB8I -> GpuFormat.RGBA8_SINT;
            case RGBA8I -> GpuFormat.RGBA8_SINT;
            case R8UI -> GpuFormat.R8_UINT;
            case RG8UI -> GpuFormat.RG8_UINT;
            case RGB8UI -> GpuFormat.RGBA8_UINT;
            case RGBA8UI -> GpuFormat.RGBA8_UINT;
            case R16I -> GpuFormat.R16_SINT;
            case RG16I -> GpuFormat.RG16_SINT;
            case RGB16I -> GpuFormat.RGBA16_SINT;
            case RGBA16I -> GpuFormat.RGBA16_SINT;
            case R16UI -> GpuFormat.R16_UINT;
            case RG16UI -> GpuFormat.RG16_UINT;
            case RGB16UI -> GpuFormat.RGBA16_UINT;
            case RGBA16UI -> GpuFormat.RGBA16_UINT;
            case R32I -> GpuFormat.R32_SINT;
            case RG32I -> GpuFormat.RG32_SINT;
            case RGB32I -> GpuFormat.RGBA32_SINT;
            case RGBA32I -> GpuFormat.RGBA32_SINT;
            case R32UI -> GpuFormat.R32_UINT;
            case RG32UI -> GpuFormat.RG32_UINT;
            case RGB32UI -> GpuFormat.RGBA32_UINT;
            case RGBA32UI -> GpuFormat.RGBA32_UINT;
            default -> throw unsupported(
                    stage, samplerName, raw,
                    "internal format " + raw.getInternalFormat() + " has no exact supported Metal upload format"
            );
        };
    }

    private static ByteBuffer convertRaw(
            final TextureStage stage,
            final String samplerName,
            final CustomTextureData.RawData raw,
            final GpuFormat destination,
            final int texelCount
    ) {
        PixelType sourceType = raw.getPixelType();
        if (!isScalar(sourceType)) {
            throw unsupported(
                    stage, samplerName, raw,
                    "packed source pixel type " + sourceType + " is not exactly lowered"
            );
        }
        boolean integerDestination = raw.getInternalFormat().getShaderDataType() != ShaderDataType.FLOAT;
        if (raw.getPixelFormat().isInteger() != integerDestination) {
            throw new IllegalArgumentException(
                    "Iris raw custom texture integer contract mismatch: stage=" + stage
                            + ", sampler=" + samplerName + ", internal=" + raw.getInternalFormat()
                            + ", pixelFormat=" + raw.getPixelFormat()
            );
        }
        int sourceStride = Math.multiplyExact(
                raw.getPixelFormat().getComponentCount(), sourceType.getByteSize()
        );
        int expectedBytes = Math.multiplyExact(texelCount, sourceStride);
        if (raw.getContent().length != expectedBytes) {
            throw new IllegalArgumentException(
                    "Iris raw custom texture byte count mismatch: stage=" + stage
                            + ", sampler=" + samplerName + ", expected=" + expectedBytes
                            + ", actual=" + raw.getContent().length
            );
        }

        ByteBuffer source = ByteBuffer.wrap(raw.getContent()).order(ByteOrder.nativeOrder());
        ByteBuffer output = ByteBuffer.allocateDirect(
                Math.multiplyExact(texelCount, destination.blockSize())
        ).order(ByteOrder.nativeOrder());
        for (int texel = 0; texel < texelCount; texel++) {
            double[] rgba = readSourceTexel(source, raw.getPixelFormat(), sourceType, integerDestination);
            for (int component = 0; component < destination.componentCount(); component++) {
                writeDestinationComponent(output, destination.componentType(), rgba[component]);
            }
        }
        output.flip();
        return output;
    }

    private static boolean isScalar(final PixelType type) {
        return switch (type) {
            case BYTE, SHORT, INT, HALF_FLOAT, FLOAT, UNSIGNED_BYTE, UNSIGNED_SHORT, UNSIGNED_INT -> true;
            default -> false;
        };
    }

    private static double[] readSourceTexel(
            final ByteBuffer source,
            final PixelFormat format,
            final PixelType type,
            final boolean integer
    ) {
        double[] declared = new double[format.getComponentCount()];
        for (int component = 0; component < declared.length; component++) {
            declared[component] = readSourceComponent(source, type, integer);
        }
        double[] rgba = {0.0, 0.0, 0.0, 1.0};
        switch (format) {
            case RED, RED_INTEGER -> rgba[0] = declared[0];
            case RG, RG_INTEGER -> {
                rgba[0] = declared[0];
                rgba[1] = declared[1];
            }
            case RGB, RGB_INTEGER -> {
                rgba[0] = declared[0];
                rgba[1] = declared[1];
                rgba[2] = declared[2];
            }
            case BGR, BGR_INTEGER -> {
                rgba[0] = declared[2];
                rgba[1] = declared[1];
                rgba[2] = declared[0];
            }
            case RGBA, RGBA_INTEGER -> System.arraycopy(declared, 0, rgba, 0, 4);
            case BGRA, BGRA_INTEGER -> {
                rgba[0] = declared[2];
                rgba[1] = declared[1];
                rgba[2] = declared[0];
                rgba[3] = declared[3];
            }
        }
        return rgba;
    }

    private static double readSourceComponent(
            final ByteBuffer source,
            final PixelType type,
            final boolean integer
    ) {
        return switch (type) {
            case BYTE -> integer ? source.get() : normalizeSigned(source.get(), 127.0);
            case SHORT -> integer ? source.getShort() : normalizeSigned(source.getShort(), 32767.0);
            case INT -> integer ? source.getInt() : normalizeSigned(source.getInt(), 2147483647.0);
            case UNSIGNED_BYTE -> integer ? Byte.toUnsignedInt(source.get()) : Byte.toUnsignedInt(source.get()) / 255.0;
            case UNSIGNED_SHORT -> integer ? Short.toUnsignedInt(source.getShort()) : Short.toUnsignedInt(source.getShort()) / 65535.0;
            case UNSIGNED_INT -> integer ? Integer.toUnsignedLong(source.getInt()) : Integer.toUnsignedLong(source.getInt()) / 4294967295.0;
            case HALF_FLOAT -> Float.float16ToFloat(source.getShort());
            case FLOAT -> source.getFloat();
            default -> throw new AssertionError("non-scalar pixel type " + type);
        };
    }

    private static double normalizeSigned(final long value, final double positiveMaximum) {
        return Math.max(-1.0, value / positiveMaximum);
    }

    private static void writeDestinationComponent(
            final ByteBuffer output,
            final GpuFormat.ComponentType type,
            final double value
    ) {
        switch (type) {
            case UNORM_8 -> output.put((byte) Math.round(clamp(value, 0.0, 1.0) * 255.0));
            case SNORM_8 -> output.put((byte) Math.round(clamp(value, -1.0, 1.0) * 127.0));
            case UINT_8 -> output.put((byte) clampInteger(value, 0L, 255L));
            case SINT_8 -> output.put((byte) clampInteger(value, Byte.MIN_VALUE, Byte.MAX_VALUE));
            case UNORM_16 -> output.putShort((short) Math.round(clamp(value, 0.0, 1.0) * 65535.0));
            case SNORM_16 -> output.putShort((short) Math.round(clamp(value, -1.0, 1.0) * 32767.0));
            case UINT_16 -> output.putShort((short) clampInteger(value, 0L, 65535L));
            case SINT_16 -> output.putShort((short) clampInteger(value, Short.MIN_VALUE, Short.MAX_VALUE));
            case FLOAT_16 -> output.putShort(Float.floatToFloat16((float) value));
            case UINT_32 -> output.putInt((int) clampInteger(value, 0L, 0xFFFF_FFFFL));
            case SINT_32 -> output.putInt((int) clampInteger(value, Integer.MIN_VALUE, Integer.MAX_VALUE));
            case FLOAT_32 -> output.putFloat((float) value);
            default -> throw new UnsupportedOperationException(
                    "Metal raw custom texture destination component type is not scalar: " + type
            );
        }
    }

    private static double clamp(final double value, final double minimum, final double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long clampInteger(final double value, final long minimum, final long maximum) {
        if (Double.isNaN(value)) {
            return 0L;
        }
        return Math.max(minimum, Math.min(maximum, Math.round(value)));
    }

    private static UnsupportedOperationException unsupported(
            final TextureStage stage,
            final String samplerName,
            final @Nullable CustomTextureData data,
            final String reason
    ) {
        String type = data == null ? "null" : data.getClass().getSimpleName();
        return new UnsupportedOperationException(
                "Unsupported Iris custom texture on Metal: stage=" + stage
                        + ", sampler=" + samplerName + ", type=" + type + ", reason=" + reason
        );
    }

    private static EnumMap<TextureStage, Map<String, CustomTextureData>> copyDefinitions(
            final Map<TextureStage, ? extends Map<String, CustomTextureData>> source
    ) {
        EnumMap<TextureStage, Map<String, CustomTextureData>> copy = new EnumMap<>(TextureStage.class);
        source.forEach((stage, entries) -> {
            Objects.requireNonNull(stage, "custom texture stage");
            Objects.requireNonNull(entries, "custom textures for stage " + stage);
            LinkedHashMap<String, CustomTextureData> stageCopy = new LinkedHashMap<>();
            entries.forEach((name, data) -> stageCopy.put(
                    Objects.requireNonNull(name, "custom texture sampler for stage " + stage),
                    data
            ));
            copy.put(stage, Collections.unmodifiableMap(stageCopy));
        });
        return copy;
    }

    private static Map<String, CustomTextureData> copyGlobalDefinitions(
            final Map<String, CustomTextureData> source
    ) {
        LinkedHashMap<String, CustomTextureData> copy = new LinkedHashMap<>();
        source.forEach((name, data) -> copy.put(
                Objects.requireNonNull(name, "global custom texture sampler"),
                data
        ));
        return Collections.unmodifiableMap(copy);
    }

    private static void closePartial(
            final @Nullable MetalGpuTexture texture,
            final @Nullable MetalGpuTextureView view,
            final @Nullable MetalGpuSampler sampler
    ) {
        if (view != null) {
            view.close();
        }
        if (texture != null) {
            texture.close();
        }
        if (sampler != null) {
            sampler.close();
        }
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Iris Metal custom textures are closed");
        }
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.loaded.values().forEach(OwnedTexture::close);
        this.loaded.clear();
    }

    /** A null stage denotes a generation-global custom texture. */
    private record Key(@Nullable TextureStage stage, String samplerName) {
    }

    record ResourceRequest(Identifier requested, Identifier base, @Nullable PBRType pbrType) {
    }

    @FunctionalInterface
    interface LiveTextureResolver {
        MetalRenderPass.@Nullable TextureViewAndSampler resolve(
                TextureStage stage,
                String samplerName,
                CustomTextureData data
        );
    }

    private record RawExtent(
            int width,
            int height,
            int depth,
            MetalTextureDimension dimension,
            boolean rectangle
    ) {
        private int texelCount() {
            return Math.multiplyExact(Math.multiplyExact(this.width, this.height), this.depth);
        }
    }

    private static final class OwnedTexture implements AutoCloseable {
        private final MetalGpuTexture texture;
        private final MetalGpuTextureView view;
        private final MetalGpuSampler sampler;

        private OwnedTexture(
                final MetalGpuTexture texture,
                final MetalGpuTextureView view,
                final MetalGpuSampler sampler
        ) {
            this.texture = texture;
            this.view = view;
            this.sampler = sampler;
        }

        private MetalRenderPass.TextureViewAndSampler binding() {
            return new MetalRenderPass.TextureViewAndSampler(this.view, this.sampler);
        }

        @Override
        public void close() {
            this.view.close();
            this.texture.close();
            this.sampler.close();
        }
    }
}
