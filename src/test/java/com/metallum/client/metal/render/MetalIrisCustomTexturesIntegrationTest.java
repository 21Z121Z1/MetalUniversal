package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.PixelFormat;
import net.irisshaders.iris.gl.texture.PixelType;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureFilteringData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** GPU and lifecycle coverage for stage-scoped Iris custom texture overrides. */
@EnabledOnOs(OS.MAC)
final class MetalIrisCustomTexturesIntegrationTest {
    private static final String VERTEX_SHADER = """
            #version 450
            void main() {
                vec2 positions[3] = vec2[](vec2(-1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
                gl_Position = vec4(positions[gl_VertexIndex], 0.0, 1.0);
            }
            """;

    private final Map<String, String> fragmentShaders = new HashMap<>();
    private MetalDevice device;
    private MetalCommandEncoder encoder;

    @BeforeEach
    void createDevice() {
        MemorySegment nativeDevice = MetalNativeBridge.metallum_create_system_default_device();
        assertFalse(MetalNativeBridge.isNullHandle(nativeDevice));
        ShaderSource source = (identifier, type) -> {
            String path = identifier.getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            return type == ShaderType.VERTEX ? VERTEX_SHADER : fragmentShaders.get(name);
        };
        device = new MetalDevice(
                source,
                new GpuDebugOptions(2, true, true, true),
                nativeDevice,
                MemorySegment.NULL,
                "Iris custom textures integration device",
                MemorySegment.NULL
        );
        encoder = device.commandEncoder();
    }

    @AfterEach
    void closeDevice() {
        MetalFxManager.close();
        if (device != null) {
            device.close();
        }
    }

    @Test
    void pngOverridePreservesPixelsAndFiltering() throws IOException {
        EnumMap<TextureStage, Object2ObjectOpenHashMap<String, CustomTextureData>> definitions = definitions(
                TextureStage.COMPOSITE_AND_FINAL,
                "colortex7",
                png(false, true, 0xFFFF0000, 0x400080FF)
        );
        try (IrisMetalCustomTextures textures = new IrisMetalCustomTextures(device, definitions)) {
            MetalRenderPass.TextureViewAndSampler binding =
                    textures.resolve(TextureStage.COMPOSITE_AND_FINAL, "colortex7");
            assertNotNull(binding);
            ByteBuffer pixels = readback((MetalGpuTexture) binding.textureView().texture());
            assertPixel(pixels, 0, 255, 0, 0, 255);
            assertPixel(pixels, 1, 0, 128, 255, 64);
            assertEquals(AddressMode.CLAMP_TO_EDGE, binding.sampler().getAddressModeU());
            assertEquals(AddressMode.CLAMP_TO_EDGE, binding.sampler().getAddressModeV());
            assertEquals(FilterMode.NEAREST, binding.sampler().getMinFilter());
            assertEquals(FilterMode.NEAREST, binding.sampler().getMagFilter());
        }
    }

    @Test
    void stageIsolationAndAliasOrderPreserveOverridePrecedence() throws IOException {
        EnumMap<TextureStage, Object2ObjectOpenHashMap<String, CustomTextureData>> definitions = definitions(
                TextureStage.COMPOSITE_AND_FINAL,
                "colortex7",
                png(true, true, 0xFF00FF00)
        );
        try (IrisMetalCustomTextures textures = new IrisMetalCustomTextures(device, definitions);
             IrisMetalCustomTextures standards = new IrisMetalCustomTextures(
                     device,
                     definitions(TextureStage.BEGIN, "standardSampler", png(false, false, 0xFFFFFFFF))
             )) {
            MetalRenderPass.TextureViewAndSampler standardBinding =
                    standards.resolve(TextureStage.BEGIN, "standardSampler");
            assertNotNull(standardBinding);

            assertSame(
                    standardBinding,
                    textures.overrideOrDefault(TextureStage.DEFERRED, standardBinding, "colortex7"),
                    "an override from another stage must not leak"
            );
            assertSame(
                    standardBinding,
                    textures.overrideOrDefault(
                            TextureStage.COMPOSITE_AND_FINAL,
                            standardBinding,
                            "missingAlias",
                            "alsoMissing"
                    )
            );

            MetalRenderPass.TextureViewAndSampler override = textures.overrideOrDefault(
                    TextureStage.COMPOSITE_AND_FINAL,
                    standardBinding,
                    "missingAlias",
                    "colortex7"
            );
            assertNotNull(override);
            assertNotSame(standardBinding, override, "same-stage custom sampler must override the standard binding");
            assertEquals(FilterMode.LINEAR, override.sampler().getMinFilter());
            assertTrue(textures.hasOverride(TextureStage.COMPOSITE_AND_FINAL, "colortex7"));
            assertFalse(textures.hasOverride(TextureStage.DEFERRED, "colortex7"));
        }
    }

    @Test
    void globalTexturesAreSharedAcrossStagesAndStageLocalDefinitionsWin() throws IOException {
        Map<String, CustomTextureData> globals = Map.of(
                "sharedSampler", png(false, false, 0xFFFF0000),
                "globalOnly", png(false, false, 0xFF0000FF)
        );
        try (IrisMetalCustomTextures textures = new IrisMetalCustomTextures(
                device,
                definitions(TextureStage.BEGIN, "sharedSampler", png(false, false, 0xFF00FF00)),
                globals
        )) {
            MetalRenderPass.TextureViewAndSampler local =
                    textures.resolve(TextureStage.BEGIN, "sharedSampler");
            MetalRenderPass.TextureViewAndSampler global =
                    textures.resolve(TextureStage.DEFERRED, "sharedSampler");
            assertNotNull(local);
            assertNotNull(global);
            assertNotSame(local, global);
            assertPixel(readback((MetalGpuTexture) local.textureView().texture()), 0, 0, 255, 0, 255);
            assertPixel(readback((MetalGpuTexture) global.textureView().texture()), 0, 255, 0, 0, 255);

            MetalRenderPass.TextureViewAndSampler beginGlobal =
                    textures.resolve(TextureStage.BEGIN, "globalOnly");
            MetalRenderPass.TextureViewAndSampler finalGlobal =
                    textures.resolve(TextureStage.COMPOSITE_AND_FINAL, "globalOnly");
            assertNotNull(beginGlobal);
            assertNotNull(finalGlobal);
            assertSame(
                    beginGlobal.textureView(), finalGlobal.textureView(),
                    "global owned texture views must be generation-shared"
            );
            assertSame(
                    beginGlobal.sampler(), finalGlobal.sampler(),
                    "global owned samplers must be generation-shared"
            );
            assertTrue(textures.hasOverride(TextureStage.SHADOWCOMP, "globalOnly"));
        }
    }

    @Test
    void globalLiveAliasesRefreshAcrossStagesAndRemainExternallyOwned() {
        CustomTextureData.ResourceData declaration =
                new CustomTextureData.ResourceData("minecraft", "textures/block/dirt.png");
        MetalGpuTexture texture = (MetalGpuTexture) device.createTexture(
                "global live Iris custom texture fixture",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC,
                GpuFormat.RGBA8_UNORM,
                1, 1, 1, 1
        );
        MetalGpuTextureView view = (MetalGpuTextureView) device.createTextureView(texture);
        MetalGpuSampler sampler = new MetalGpuSampler(
                device,
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR,
                FilterMode.LINEAR,
                1,
                OptionalDouble.of(0.0)
        );
        MetalRenderPass.TextureViewAndSampler external =
                new MetalRenderPass.TextureViewAndSampler(view, sampler);
        AtomicInteger resolutions = new AtomicInteger();

        try (IrisMetalCustomTextures textures = new IrisMetalCustomTextures(
                device,
                Map.of(),
                Map.of("globalLive", declaration),
                (stage, name, data) -> {
                    assertEquals("globalLive", name);
                    assertSame(declaration, data);
                    resolutions.incrementAndGet();
                    return external;
                }
        )) {
            textures.prewarmAll();
            assertSame(external, textures.resolve(TextureStage.BEGIN, "globalLive"));
            assertSame(external, textures.resolve(TextureStage.DEFERRED, "globalLive"));
            assertEquals(3, resolutions.get(), "global live aliases must resolve on every use");
        }

        assertFalse(view.isClosed());
        assertFalse(texture.isClosed());
        assertFalse(sampler.isClosed());
        view.close();
        texture.close();
        sampler.close();
    }

    @Test
    void closeReleasesEveryMaterializedResourceAndIsIdempotent() throws IOException {
        IrisMetalCustomTextures textures = new IrisMetalCustomTextures(
                device,
                definitions(TextureStage.BEGIN, "customSampler", png(false, false, 0xFFFFFFFF))
        );
        MetalRenderPass.TextureViewAndSampler binding = textures.resolve(TextureStage.BEGIN, "customSampler");
        assertNotNull(binding);
        MetalGpuTexture texture = (MetalGpuTexture) binding.textureView().texture();
        MetalGpuSampler sampler = (MetalGpuSampler) binding.sampler();

        textures.close();
        textures.close();

        assertTrue(binding.textureView().isClosed());
        assertTrue(texture.isClosed());
        assertTrue(sampler.isClosed());
        assertThrows(
                IllegalStateException.class,
                () -> textures.resolve(TextureStage.BEGIN, "customSampler")
        );
    }

    @Test
    void rawDimensionsAndScalarConversionsPreserveGpuContent() {
        CustomTextureData.RawData1D oneD = new CustomTextureData.RawData1D(
                new byte[]{(byte) 255, 0, 0, 0, (byte) 128, (byte) 255},
                filtering(), InternalTextureFormat.RGB8,
                PixelFormat.RGB, PixelType.UNSIGNED_BYTE, 2
        );
        try (IrisMetalCustomTextures textures = new IrisMetalCustomTextures(
                device, definitions(TextureStage.BEGIN, "oneD", oneD)
        )) {
            MetalRenderPass.TextureViewAndSampler binding = textures.resolve(TextureStage.BEGIN, "oneD");
            assertNotNull(binding);
            ByteBuffer pixels = readback((MetalGpuTexture) binding.textureView().texture());
            assertPixel(pixels, 0, 255, 0, 0, 255);
            assertPixel(pixels, 1, 0, 128, 255, 255);
        }

        CustomTextureData.RawData2D twoD = new CustomTextureData.RawData2D(
                new byte[]{30, 20, 10, 40},
                filtering(), InternalTextureFormat.RGBA8,
                PixelFormat.BGRA, PixelType.UNSIGNED_BYTE, 1, 1
        );
        try (IrisMetalCustomTextures textures = new IrisMetalCustomTextures(
                device, definitions(TextureStage.DEFERRED, "twoD", twoD)
        )) {
            MetalRenderPass.TextureViewAndSampler binding = textures.resolve(TextureStage.DEFERRED, "twoD");
            assertNotNull(binding);
            assertPixel(readback((MetalGpuTexture) binding.textureView().texture()), 0, 10, 20, 30, 40);
        }

        CustomTextureData.RawDataRect rectangle = new CustomTextureData.RawDataRect(
                new byte[]{1, 2, 3, 4},
                new TextureFilteringData(false, true), InternalTextureFormat.RGBA8,
                PixelFormat.RGBA, PixelType.UNSIGNED_BYTE, 1, 1
        );
        try (IrisMetalCustomTextures textures = new IrisMetalCustomTextures(
                device, definitions(TextureStage.COMPOSITE_AND_FINAL, "rectangle", rectangle)
        )) {
            MetalRenderPass.TextureViewAndSampler binding =
                    textures.resolve(TextureStage.COMPOSITE_AND_FINAL, "rectangle");
            assertNotNull(binding);
            assertFalse(((MetalGpuSampler) binding.sampler()).usesNormalizedCoordinates());
            assertPixel(readback((MetalGpuTexture) binding.textureView().texture()), 0, 1, 2, 3, 4);
        }

        ByteBuffer volumeSource = ByteBuffer.allocate(2 * Float.BYTES).order(ByteOrder.nativeOrder());
        volumeSource.putFloat(0.25F).putFloat(0.75F);
        CustomTextureData.RawData3D threeD = new CustomTextureData.RawData3D(
                volumeSource.array(), filtering(), InternalTextureFormat.R16F,
                PixelFormat.RED, PixelType.FLOAT, 1, 1, 2
        );
        try (IrisMetalCustomTextures textures = new IrisMetalCustomTextures(
                device, definitions(TextureStage.SHADOWCOMP, "threeD", threeD)
        )) {
            MetalRenderPass.TextureViewAndSampler binding = textures.resolve(TextureStage.SHADOWCOMP, "threeD");
            assertNotNull(binding);
            ByteBuffer pixels = readback((MetalGpuTexture) binding.textureView().texture())
                    .order(ByteOrder.nativeOrder());
            assertEquals(0.25F, Float.float16ToFloat(pixels.getShort(0)), 0.0005F);
            assertEquals(0.75F, Float.float16ToFloat(pixels.getShort(2)), 0.0005F);
        }

        ByteBuffer integerSource = ByteBuffer.allocate(2 * Short.BYTES).order(ByteOrder.nativeOrder());
        integerSource.putShort((short) 0xFFFF).putShort((short) 42);
        CustomTextureData.RawData2D integer = new CustomTextureData.RawData2D(
                integerSource.array(), filtering(), InternalTextureFormat.R16UI,
                PixelFormat.RED_INTEGER, PixelType.UNSIGNED_SHORT, 2, 1
        );
        try (IrisMetalCustomTextures textures = new IrisMetalCustomTextures(
                device, definitions(TextureStage.PREPARE, "integer", integer)
        )) {
            MetalRenderPass.TextureViewAndSampler binding = textures.resolve(TextureStage.PREPARE, "integer");
            assertNotNull(binding);
            ByteBuffer pixels = readback((MetalGpuTexture) binding.textureView().texture())
                    .order(ByteOrder.nativeOrder());
            assertEquals(65535, Short.toUnsignedInt(pixels.getShort(0)));
            assertEquals(42, Short.toUnsignedInt(pixels.getShort(2)));
        }
    }

    @Test
    void externalKindsResolveEveryUseAndRemainExternallyOwned() {
        List<CustomTextureData> externalKinds = List.of(
                new CustomTextureData.LightmapMarker(),
                new CustomTextureData.ResourceData("minecraft", "textures/block/dirt.png")
        );

        for (CustomTextureData data : externalKinds) {
            MetalGpuTexture texture = (MetalGpuTexture) device.createTexture(
                    "live Iris custom texture fixture",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC,
                    GpuFormat.RGBA8_UNORM,
                    1, 1, 1, 1
            );
            MetalGpuTextureView view = (MetalGpuTextureView) device.createTextureView(texture);
            MetalGpuSampler sampler = new MetalGpuSampler(
                    device,
                    AddressMode.CLAMP_TO_EDGE,
                    AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR,
                    FilterMode.LINEAR,
                    1,
                    OptionalDouble.of(0.0)
            );
            MetalRenderPass.TextureViewAndSampler external =
                    new MetalRenderPass.TextureViewAndSampler(view, sampler);
            AtomicInteger resolutions = new AtomicInteger();
            try (IrisMetalCustomTextures textures = new IrisMetalCustomTextures(
                    device,
                    definitions(TextureStage.SHADOWCOMP, "requiredInput", data),
                    (stage, name, declaration) -> {
                        assertEquals(TextureStage.SHADOWCOMP, stage);
                        assertEquals("requiredInput", name);
                        assertSame(data, declaration);
                        resolutions.incrementAndGet();
                        return external;
                    }
            )) {
                assertNull(
                        textures.resolve(TextureStage.DEFERRED, "requiredInput"),
                        "a live alias from another stage must not leak"
                );
                textures.prewarmAll();
                assertSame(external, textures.resolve(TextureStage.SHADOWCOMP, "requiredInput"));
                assertSame(external, textures.resolve(TextureStage.SHADOWCOMP, "requiredInput"));
                assertEquals(3, resolutions.get(), "live aliases must be refreshed rather than cached");
            }
            assertFalse(view.isClosed());
            assertFalse(texture.isClosed());
            assertFalse(sampler.isClosed());
            view.close();
            texture.close();
            sampler.close();
        }
    }

    @Test
    void resourcePathsPreserveIrisPbrSuffixResolution() {
        IrisMetalCustomTextures.ResourceRequest ordinary = IrisMetalCustomTextures.resourceRequest(
                new CustomTextureData.ResourceData("minecraft", "textures/block/dirt.png")
        );
        assertEquals("minecraft:textures/block/dirt.png", ordinary.requested().toString());
        assertEquals(ordinary.requested(), ordinary.base());
        assertNull(ordinary.pbrType());

        IrisMetalCustomTextures.ResourceRequest normal = IrisMetalCustomTextures.resourceRequest(
                new CustomTextureData.ResourceData("fixture", "textures/block/stone_n.png")
        );
        assertEquals("fixture:textures/block/stone_n.png", normal.requested().toString());
        assertEquals("fixture:textures/block/stone.png", normal.base().toString());
        assertEquals(net.irisshaders.iris.pbr.texture.PBRType.NORMAL, normal.pbrType());

        IrisMetalCustomTextures.ResourceRequest specular = IrisMetalCustomTextures.resourceRequest(
                new CustomTextureData.ResourceData("fixture", "textures/block/stone_s.png")
        );
        assertEquals("fixture:textures/block/stone_s.png", specular.requested().toString());
        assertEquals("fixture:textures/block/stone.png", specular.base().toString());
        assertEquals(net.irisshaders.iris.pbr.texture.PBRType.SPECULAR, specular.pbrType());

        assertThrows(
                IllegalArgumentException.class,
                () -> IrisMetalCustomTextures.resourceRequest(
                        new CustomTextureData.ResourceData("fixture", "textures/block/stone_s")
                )
        );
    }

    @Test
    void rawAdmissionRejectsUnloweredOrInvalidDeclarations() {
        List<CustomTextureData.RawData> unsupported = List.of(
                new CustomTextureData.RawData2D(
                        new byte[6], filtering(), InternalTextureFormat.RGB8,
                        PixelFormat.RGB, PixelType.UNSIGNED_SHORT_5_6_5, 1, 1
                ),
                new CustomTextureData.RawData2D(
                        new byte[4], filtering(), InternalTextureFormat.RGBA4,
                        PixelFormat.RGBA, PixelType.UNSIGNED_BYTE, 1, 1
                ),
                new CustomTextureData.RawDataRect(
                        new byte[4], filtering(), InternalTextureFormat.RGBA8,
                        PixelFormat.RGBA, PixelType.UNSIGNED_BYTE, 1, 1
                )
        );
        for (CustomTextureData.RawData data : unsupported) {
            try (IrisMetalCustomTextures textures = new IrisMetalCustomTextures(
                    device, definitions(TextureStage.SHADOWCOMP, "requiredInput", data)
            )) {
                UnsupportedOperationException failure = assertThrows(
                        UnsupportedOperationException.class,
                        textures::prewarmAll
                );
                assertTrue(failure.getMessage().contains("stage=SHADOWCOMP"));
                assertTrue(failure.getMessage().contains("sampler=requiredInput"));
            }
        }
    }

    @Test
    void oneDimensionalRectangleAndThreeDimensionalSamplersCompileOnDevice() {
        String shader = "raw_dimensions";
        fragmentShaders.put(shader, """
                #version 450
                layout(binding=0) uniform sampler1D oneD;
                layout(binding=1) uniform sampler2DRect rectangle;
                layout(binding=2) uniform sampler3D threeD;
                layout(location=0) out vec4 fragColor;
                void main() {
                    fragColor = texture(oneD, 0.5)
                            + texture(rectangle, vec2(0.5))
                            + texture(threeD, vec3(0.5));
                }
                """);
        BindGroupLayout layout = BindGroupLayout.builder()
                .withSampler("oneD")
                .withSampler("rectangle")
                .withSampler("threeD")
                .build();
        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation("metallum_test/raw_dimensions")
                .withVertexShader("metallum_test/raw_dimensions")
                .withFragmentShader("metallum_test/raw_dimensions")
                .withBindGroupLayout(layout)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withColorTargetState(0, new ColorTargetState(
                        Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL
                ))
                .build();

        MetalCompiledRenderPipeline compiled = (MetalCompiledRenderPipeline) device.precompilePipeline(pipeline, null);
        assertTrue(compiled.isValid());
        assertFalse(MetalNativeBridge.isNullHandle(compiled.getNativePipeline(
                MTLPixelFormat.Invalid, MTLPixelFormat.Invalid
        )));
    }

    private ByteBuffer readback(final MetalGpuTexture texture) {
        int depth = texture.getDepthOrLayers();
        int size = texture.getWidth(0) * texture.getHeight(0) * depth * texture.pixelSize();
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "iris custom texture readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                size
        )) {
            encoder.copyTextureVolumeToBuffer(
                    texture, buffer, 0L, 0,
                    0, 0, 0, texture.getWidth(0), texture.getHeight(0), depth, () -> {
                    }
            );
            encoder.submit();
            device.waitForSubmittedGpuWork();
            ByteBuffer source = buffer.currentStorage().limit(size).slice().order(ByteOrder.nativeOrder());
            ByteBuffer copy = ByteBuffer.allocate(size);
            copy.put(source);
            copy.flip();
            return copy;
        }
    }

    private static EnumMap<TextureStage, Object2ObjectOpenHashMap<String, CustomTextureData>> definitions(
            final TextureStage stage,
            final String sampler,
            final CustomTextureData data
    ) {
        EnumMap<TextureStage, Object2ObjectOpenHashMap<String, CustomTextureData>> definitions =
                new EnumMap<>(TextureStage.class);
        Object2ObjectOpenHashMap<String, CustomTextureData> stageDefinitions = new Object2ObjectOpenHashMap<>();
        stageDefinitions.put(sampler, data);
        definitions.put(stage, stageDefinitions);
        return definitions;
    }

    private static CustomTextureData.PngData png(
            final boolean blur,
            final boolean clamp,
            final int... argb
    ) throws IOException {
        BufferedImage image = new BufferedImage(argb.length, 1, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < argb.length; x++) {
            image.setRGB(x, 0, argb[x]);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return new CustomTextureData.PngData(new TextureFilteringData(blur, clamp), output.toByteArray());
    }

    private static TextureFilteringData filtering() {
        return new TextureFilteringData(false, false);
    }

    private static void assertPixel(
            final ByteBuffer pixels,
            final int index,
            final int red,
            final int green,
            final int blue,
            final int alpha
    ) {
        int offset = index * 4;
        assertEquals(red, Byte.toUnsignedInt(pixels.get(offset)), "red at pixel " + index);
        assertEquals(green, Byte.toUnsignedInt(pixels.get(offset + 1)), "green at pixel " + index);
        assertEquals(blue, Byte.toUnsignedInt(pixels.get(offset + 2)), "blue at pixel " + index);
        assertEquals(alpha, Byte.toUnsignedInt(pixels.get(offset + 3)), "alpha at pixel " + index);
    }
}
