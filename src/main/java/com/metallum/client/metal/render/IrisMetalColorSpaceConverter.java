package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.pathways.colorspace.ColorSpace;
import net.irisshaders.iris.pathways.colorspace.ColorSpaceFragmentConverter;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/** Fixed-Iris final-output color conversion owned by one execution-graph generation. */
@Environment(EnvType.CLIENT)
final class IrisMetalColorSpaceConverter implements AutoCloseable {
    private static final String VERTEX_SOURCE = """
            #version 450 core
            in vec3 iris_Position;
            in vec2 iris_UV0;
            layout(location = 0) out vec2 uv;

            void main() {
                // FullScreenQuadRenderer already supplies clip-space positions.
                // The fixed Iris source's projection uniform is therefore not
                // needed and would violate the shared Vulkan uniform ABI.
                gl_Position = vec4(iris_Position, 1.0);
                uv = iris_UV0;
            }
            """;
    private static final String FRAGMENT_TEMPLATE = readResource("/colorSpace.csh");

    private final int generation;
    private final EnumMap<ColorSpace, MetalCompiledRenderPipeline> pipelines =
            new EnumMap<>(ColorSpace.class);
    private @Nullable MetalDevice device;
    private @Nullable MetalGpuTexture swap;
    private @Nullable MetalGpuTextureView swapView;
    private @Nullable MetalGpuSampler sampler;
    private @Nullable MetalGpuBuffer quadVertices;
    private @Nullable MetalGpuBuffer quadIndices;
    private boolean packOwnsColorCorrection;
    private boolean prepared;
    private boolean closed;

    IrisMetalColorSpaceConverter(final int generation) {
        if (generation <= 0) {
            throw new IllegalArgumentException("Iris color-space converter generation must be positive");
        }
        this.generation = generation;
    }

    void prepare(
            final MetalDevice device,
            final GpuFormat finalColorFormat,
            final boolean packOwnsColorCorrection
    ) {
        ensureOpen();
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(finalColorFormat, "finalColorFormat");
        if (prepared) {
            if (this.device != device) {
                throw new IllegalStateException("Iris color-space converter crossed Metal device ownership");
            }
            if (this.packOwnsColorCorrection != packOwnsColorCorrection) {
                throw new IllegalStateException("Iris color-correction ownership changed within a generation");
            }
            return;
        }
        this.device = device;
        this.packOwnsColorCorrection = packOwnsColorCorrection;
        if (!packOwnsColorCorrection) {
            if (finalColorFormat != GpuFormat.RGBA8_UNORM) {
                throw new UnsupportedOperationException(
                        "Iris color-space conversion requires RGBA8_UNORM MainTarget, got " + finalColorFormat
                );
            }
            try {
                for (ColorSpace colorSpace : ColorSpace.values()) {
                    if (colorSpace == ColorSpace.SRGB) {
                        continue;
                    }
                    pipelines.put(colorSpace, compile(device, colorSpace, finalColorFormat));
                }
                this.quadVertices = createQuadVertices(device);
                this.quadIndices = createQuadIndices(device);
            } catch (RuntimeException | Error failure) {
                closePipelines();
                if (this.quadVertices != null) {
                    this.quadVertices.close();
                    this.quadVertices = null;
                }
                if (this.quadIndices != null) {
                    this.quadIndices.close();
                    this.quadIndices = null;
                }
                this.device = null;
                throw failure;
            }
        }
        prepared = true;
    }

    boolean execute(final GpuTextureView mainColor, final ColorSpace colorSpace) {
        ensureOpen();
        Objects.requireNonNull(mainColor, "mainColor");
        Objects.requireNonNull(colorSpace, "colorSpace");
        if (mainColor.isClosed()) {
            throw new IllegalStateException("Iris color-space conversion received a closed main-color view");
        }
        if (!prepared || device == null) {
            throw new IllegalStateException("Iris color-space converter is not prepared");
        }
        if (!(mainColor.texture() instanceof MetalGpuTexture texture)) {
            throw new IllegalStateException(
                    "Iris color-space conversion received a non-Metal main-color texture"
            );
        }
        if (texture.isClosed()) {
            throw new IllegalStateException("Iris color-space conversion received a closed main-color texture");
        }
        if (!texture.isOwnedBy(device)) {
            throw new IllegalStateException(
                    "Iris color-space conversion crossed Metal device ownership"
            );
        }
        if (packOwnsColorCorrection || colorSpace == ColorSpace.SRGB) {
            return false;
        }
        if (mainColor.texture().getFormat() != GpuFormat.RGBA8_UNORM) {
            throw new IllegalStateException(
                    "Iris color-space conversion requires RGBA8 MainTarget, got "
                            + mainColor.texture().getFormat()
            );
        }
        MetalCompiledRenderPipeline pipeline = pipelines.get(colorSpace);
        if (pipeline == null) {
            throw new IllegalStateException(
                    "Iris generation " + generation + " has no color-space pipeline for " + colorSpace
            );
        }
        ensureSwap(mainColor.getWidth(0), mainColor.getHeight(0));
        MetalGpuTexture swapTexture = Objects.requireNonNull(swap, "color-space swap texture");
        MetalGpuTextureView output = Objects.requireNonNull(swapView, "color-space swap view");
        MetalGpuSampler inputSampler = Objects.requireNonNull(sampler, "color-space sampler");

        RenderPassDescriptor descriptor = RenderPassDescriptor.create(
                () -> "Iris color space: " + colorSpace
        ).withColorAttachment(output, Optional.empty())
                .withRenderArea(new RenderPass.RenderArea(
                        0, 0, mainColor.getWidth(0), mainColor.getHeight(0)
                ));
        MetalCommandEncoder encoder = device.createCommandEncoder();
        MetalGpuBuffer vertices = Objects.requireNonNull(quadVertices, "color-space quad vertex buffer");
        MetalGpuBuffer indices = Objects.requireNonNull(quadIndices, "color-space quad index buffer");
        try {
            MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor);
            pass.setCompiledPipeline(pipeline);
            pass.bindTexture("readImage", mainColor, inputSampler);
            pass.setIndexBuffer(indices, IndexType.SHORT);
            pass.setVertexBuffer(0, vertices.slice());
            pass.drawIndexed(6, 1, 0, 0, 0);
        } finally {
            encoder.submitRenderPass();
        }
        encoder.copyTextureToTexture(
                swapTexture,
                mainColor.texture(),
                0,
                0,
                0,
                0,
                0,
                mainColor.getWidth(0),
                mainColor.getHeight(0)
        );
        // The converter owns a blit after the render pass. Submit the complete
        // command buffer before returning so the final target contains the
        // converted pixels before the surface presents or a receipt reads it.
        encoder.submit();
        return true;
    }

    private MetalCompiledRenderPipeline compile(
            final MetalDevice device,
            final ColorSpace colorSpace,
            final GpuFormat format
    ) {
        try {
            return MetalCrossShaderCompiler.compileShaderpack(
                    device,
                    "iris/gen" + generation + "/presentation/"
                            + colorSpace.name().toLowerCase(java.util.Locale.ROOT),
                    VERTEX_SOURCE,
                    fragmentSource(colorSpace),
                    null,
                    vertexAttributeFormats(),
                    false,
                    false,
                    PolygonMode.FILL,
                    PrimitiveTopology.QUADS,
                    new VertexFormat[]{DefaultVertexFormat.POSITION_TEX},
                    null,
                    new ColorTargetState[]{new ColorTargetState(
                            Optional.empty(), format, ColorTargetState.WRITE_ALL
                    )}
            );
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Failed to compile Iris " + colorSpace + " color-space pipeline", failure
            );
        }
    }

    private static String fragmentSource(final ColorSpace colorSpace) {
        List<StringPair> defines = new ArrayList<>();
        defines.add(new StringPair("CURRENT_COLOR_SPACE", Integer.toString(colorSpace.ordinal())));
        for (ColorSpace value : ColorSpace.values()) {
            defines.add(new StringPair(value.name(), Integer.toString(value.ordinal())));
        }
        return JcppProcessor.glslPreprocessSource(FRAGMENT_TEMPLATE, defines)
                .replaceFirst("(?m)^\\s*#version\\s+330(?:\\s+core)?", "#version 450 core")
                .replace("in vec2 uv;", "layout(location = 0) in vec2 uv;")
                .replace("out vec4 outColor;", "layout(location = 0) out vec4 outColor;");
    }

    private void ensureSwap(final int width, final int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid Iris color-space extent " + width + "x" + height);
        }
        if (swap != null && swap.getWidth(0) == width && swap.getHeight(0) == height) {
            return;
        }
        closeSwap();
        MetalDevice owner = Objects.requireNonNull(device, "device");
        swap = (MetalGpuTexture) owner.createTexture(
                "metallum:iris_color_space_swap",
                com.mojang.blaze3d.textures.GpuTexture.USAGE_RENDER_ATTACHMENT
                        | com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING
                        | com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_SRC
                        | com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM,
                width,
                height,
                1,
                1
        );
        swapView = new MetalGpuTextureView(swap, 0, 1);
        if (sampler == null) {
            sampler = new MetalGpuSampler(
                    owner,
                    AddressMode.CLAMP_TO_EDGE,
                    AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST,
                    FilterMode.NEAREST,
                    1,
                    OptionalDouble.empty()
            );
        }
    }

    private static Map<String, GpuFormat> vertexAttributeFormats() {
        Map<String, GpuFormat> formats = new LinkedHashMap<>();
        DefaultVertexFormat.POSITION_TEX.getElements().forEach(element ->
                formats.put(element.name(), element.format())
        );
        return formats;
    }

    private void closeSwap() {
        if (swapView != null) {
            swapView.close();
            swapView = null;
        }
        if (swap != null) {
            swap.close();
            swap = null;
        }
    }

    private void closePipelines() {
        pipelines.values().forEach(MetalCompiledRenderPipeline::close);
        pipelines.clear();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Iris color-space converter is closed");
        }
    }

    private static String readResource(final String resource) {
        try (InputStream stream = ColorSpaceFragmentConverter.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing fixed-Iris color-space resource " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to read fixed-Iris color-space resource " + resource, failure);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closePipelines();
        closeSwap();
        if (quadVertices != null) {
            quadVertices.close();
            quadVertices = null;
        }
        if (quadIndices != null) {
            quadIndices.close();
            quadIndices = null;
        }
        if (sampler != null) {
            sampler.close();
            sampler = null;
        }
        device = null;
    }

    private static MetalGpuBuffer createQuadIndices(final MetalDevice device) {
        java.nio.ByteBuffer indices = java.nio.ByteBuffer.allocateDirect(6 * Short.BYTES)
                .order(java.nio.ByteOrder.nativeOrder());
        for (short index : new short[]{0, 1, 2, 2, 3, 0}) {
            indices.putShort(index);
        }
        indices.flip();
        return (MetalGpuBuffer) device.createBuffer(
                () -> "metallum:iris_color_space_quad_indices",
                GpuBuffer.USAGE_INDEX,
                indices
        );
    }

    private static MetalGpuBuffer createQuadVertices(final MetalDevice device) {
        VertexFormatElement position = DefaultVertexFormat.POSITION_TEX.getElements().stream()
                .filter(element -> element.name().equals("Position"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("POSITION_TEX has no Position element"));
        VertexFormatElement uv = DefaultVertexFormat.POSITION_TEX.getElements().stream()
                .filter(element -> element.name().equals("UV0"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("POSITION_TEX has no UV0 element"));
        int stride = DefaultVertexFormat.POSITION_TEX.getVertexSize();
        java.nio.ByteBuffer vertices = java.nio.ByteBuffer.allocateDirect(4 * stride)
                .order(java.nio.ByteOrder.nativeOrder());
        float[][] positions = {
                {-1.0F, -1.0F, 0.0F, 0.0F, 0.0F},
                {1.0F, -1.0F, 0.0F, 1.0F, 0.0F},
                {1.0F, 1.0F, 0.0F, 1.0F, 1.0F},
                {-1.0F, 1.0F, 0.0F, 0.0F, 1.0F}
        };
        for (int index = 0; index < positions.length; index++) {
            int base = index * stride;
            float[] vertex = positions[index];
            vertices.putFloat(base + position.offset(), vertex[0]);
            vertices.putFloat(base + position.offset() + Float.BYTES, vertex[1]);
            vertices.putFloat(base + position.offset() + 2 * Float.BYTES, vertex[2]);
            vertices.putFloat(base + uv.offset(), vertex[3]);
            vertices.putFloat(base + uv.offset() + Float.BYTES, vertex[4]);
        }
        return (MetalGpuBuffer) device.createBuffer(
                () -> "metallum:iris_color_space_quad_vertices",
                GpuBuffer.USAGE_VERTEX,
                vertices
        );
    }
}
