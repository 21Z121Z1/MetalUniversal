package com.metallum.client.metal.render;

import com.google.common.collect.ImmutableList;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pathways.colorspace.ColorSpace;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Content-level Iris execution-graph fixture on the real Metal device. */
@EnabledOnOs(OS.MAC)
final class IrisMetalComputeConformanceTest {
    private static final int WIDTH = 32;
    private static final int HEIGHT = 16;
    private static final int RESIZED_WIDTH = 64;
    private static final int RESIZED_HEIGHT = 32;

    @Test
    void setupAndCompositeComputePublishStorageImageWritesToRaster() throws Exception {
        Path shaders = fixturePath();
        Iris.testing = true;
        ShaderPack pack = new ShaderPack(shaders, environmentDefines(), false);
        ProgramSet programSet = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
        IrisMetalPackAdmission.requireSupported(programSet, ColorSpace.SRGB);
        GpuFormat[] formats = {GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM};

        MemorySegment nativeDevice = MetalNativeBridge.metallum_create_system_default_device();
        assertFalse(MetalNativeBridge.isNullHandle(nativeDevice));
        ShaderSource fallback = (identifier, type) -> null;
        MetalDevice device = new MetalDevice(
                fallback,
                new GpuDebugOptions(2, true, true, true),
                nativeDevice,
                MemorySegment.NULL,
                "Iris compute conformance device",
                MemorySegment.NULL
        );
        GpuDevice renderDevice = new GpuDevice(device, () -> { });
        RenderSystem.initRenderThread();
        RenderSystem.initRenderer(renderDevice);
        try {
            try (IrisMetalPostChain chain = IrisMetalPostChain.create(
                    1, programSet, formats.length, new BitSet()
            ); IrisMetalRenderTargets targets = new IrisMetalRenderTargets(
                    device,
                    formats,
                    WIDTH,
                    HEIGHT,
                    Map.of(),
                    chain.mipmappedTargets(),
                    chain.storageImageTargets()
            ); IrisMetalUniformValues values = new IrisMetalUniformValues(0.0F);
                 IrisMetalComputeResources computeResources = new IrisMetalComputeResources(
                         device, pack, WIDTH, HEIGHT
                 )) {
                assertEquals(java.util.Set.of(0, 1), chain.storageImageTargets());
                assertEquals(3, chain.passInfos(IrisMetalPostChain.Stage.COMPOSITE).size());
                assertEquals(64L, computeResources.storageBuffer(1).length());
                assertEquals((long) WIDTH * HEIGHT * Integer.BYTES,
                        computeResources.storageBuffer(2).length());
                chain.registerUniforms(values);
                values.prewarm(device);
                chain.prepare(device, targets, GpuFormat.RGBA8_UNORM, fallback);

                IrisMetalPostChain.ResourceProvider resources = resources(chain, values, computeResources);
                executeContract(device, chain, targets, computeResources, resources, WIDTH, HEIGHT);

                GpuTextureView oldImage = computeResources.storageImage("contractImage");
                targets.resize(RESIZED_WIDTH, RESIZED_HEIGHT);
                computeResources.resize(RESIZED_WIDTH, RESIZED_HEIGHT);
                assertTrue(oldImage.isClosed(), "resize must retire the previous custom image view");
                assertEquals((long) RESIZED_WIDTH * RESIZED_HEIGHT * Integer.BYTES,
                        computeResources.storageBuffer(2).length());
                assertEquals(RESIZED_WIDTH, computeResources.storageImage("contractImage").getWidth(0));
                assertEquals(RESIZED_HEIGHT, computeResources.storageImage("contractImage").getHeight(0));
                executeContract(
                        device, chain, targets, computeResources, resources, RESIZED_WIDTH, RESIZED_HEIGHT
                );
            }
        } finally {
            MetalFxManager.close();
            RenderSystem.shutdownRenderer();
        }
    }

    private static IrisMetalPostChain.ResourceProvider resources(
            final IrisMetalPostChain chain,
            final IrisMetalUniformValues values,
            final IrisMetalComputeResources computeResources
    ) {
        return new IrisMetalPostChain.ResourceProvider() {
            @Override
            public GpuBufferSlice uniform(
                    final IrisMetalPostChain.PassInfo pass,
                    final String blockName
            ) {
                return MetalIrisShaderCompiler.UNIFORM_BLOCK_NAME.equals(blockName)
                        ? chain.uniformSlice(values, pass)
                        : null;
            }

            @Override
            public GpuBufferSlice uniform(
                    final IrisMetalPostChain.PassInfo pass,
                    final String blockName,
                    final Object token
            ) {
                return MetalIrisShaderCompiler.UNIFORM_BLOCK_NAME.equals(blockName)
                        ? values.slice(token)
                        : null;
            }

            @Override
            public IrisMetalPostChain.TextureBinding texture(
                    final IrisMetalPostChain.PassInfo pass,
                    final String samplerName
            ) {
                return computeResources.sampledImage(samplerName);
            }

            @Override
            public GpuTextureView storageImage(
                    final IrisMetalPostChain.PassInfo pass,
                    final String imageName
            ) {
                return computeResources.storageImage(imageName);
            }

            @Override
            public GpuBufferSlice storageBuffer(final int binding) {
                return computeResources.storageBuffer(binding);
            }
        };
    }

    private static void executeContract(
            final MetalDevice device,
            final IrisMetalPostChain chain,
            final IrisMetalRenderTargets targets,
            final IrisMetalComputeResources computeResources,
            final IrisMetalPostChain.ResourceProvider resources,
            final int width,
            final int height
    ) {
        IrisMetalPostChain.ExecutionReceipt setup = chain.executeStage(
                IrisMetalPostChain.Stage.SETUP, device, targets, resources
        );
        assertEquals(java.util.List.of("setup"), setup.passes());
        assertRgba(device, targets.colorTargets().readTexture(0), 255, 0, 0, "setup compute");
        assertRgba(device, targets.colorTargets().readTexture(1), 0, 255, 0, "setup MRT compute");
        assertBufferWord(device, computeResources.storageBuffer(1), 3, 0x11223344, "absolute dispatch");

        IrisMetalPostChain.ExecutionReceipt begin = chain.executeStage(
                IrisMetalPostChain.Stage.BEGIN, device, targets, resources
        );
        assertEquals(java.util.List.of("begin"), begin.passes());
        assertRgba(device, targets.colorTargets().readTexture(1), 255, 0, 0, "begin raster");

        IrisMetalPostChain.ExecutionReceipt prepare = chain.executeStage(
                IrisMetalPostChain.Stage.PREPARE, device, targets, resources
        );
        assertEquals(java.util.List.of("prepare"), prepare.passes());
        assertRgba(device, targets.colorTargets().readTexture(1), 255, 255, 0, "prepare raster");

        IrisMetalPostChain.ExecutionReceipt deferred = chain.executeStage(
                IrisMetalPostChain.Stage.DEFERRED, device, targets, resources
        );
        assertEquals(java.util.List.of("deferred"), deferred.passes());
        assertRgba(device, targets.colorTargets().readTexture(1), 0, 255, 255, "deferred raster");

        IrisMetalPostChain.ExecutionReceipt composite = chain.executeStage(
                IrisMetalPostChain.Stage.COMPOSITE, device, targets, resources
        );
        assertEquals(
                java.util.List.of("composite", "composite_a", "composite", "composite1", "composite2"),
                composite.passes()
        );
        assertRgba(device, targets.colorTargets().readTexture(0), 128, 128, 128,
                "per-target alpha blend over compute and raster history");
        assertRgba(device, targets.colorTargets().readTexture(1), 0, 0, 255,
                "per-target blend disable overrides global additive blend");
        assertBufferWord(device, computeResources.storageBuffer(1), 4, 0x55667788, "indirect dispatch");
        assertBufferWord(device, computeResources.storageBuffer(1), 6, 0xcafebabe,
                "serial compute dispatch barrier");
        assertBufferWord(device, computeResources.storageBuffer(1), 5, 0x99aabbcc, "raster SSBO write");
        assertRgba(
                device,
                (MetalGpuTexture) computeResources.storageImage("contractImage").texture(),
                0, 0, 255,
                "raster storage image write"
        );
        assertBufferWord(
                device,
                computeResources.storageBuffer(2),
                width * height - 1,
                width * height,
                "relative dispatch and relative SSBO"
        );

        try (MetalGpuTexture mainTarget = (MetalGpuTexture) device.createTexture(
                "Iris conformance final target",
                com.mojang.blaze3d.textures.GpuTexture.USAGE_RENDER_ATTACHMENT
                        | com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING
                        | com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_SRC,
                GpuFormat.RGBA8_UNORM,
                width,
                height,
                1,
                1
        ); GpuTextureView mainView = device.createTextureView(mainTarget)) {
            IrisMetalPostChain.FinalReceipt finalReceipt = chain.executeFinal(
                    device, targets, mainView, resources
            );
            assertTrue(finalReceipt.shaderExecuted());
            assertTrue(finalReceipt.mainTargetResolved());
            assertRgba(device, mainTarget, 128, 128, 255, "final resolve");
            assertDisplayEncodedRamp(device, mainTarget, width, "final MainTarget");

            try (MetalGpuTexture presentCopy = (MetalGpuTexture) device.createTexture(
                    "Iris conformance present-copy target",
                    com.mojang.blaze3d.textures.GpuTexture.USAGE_RENDER_ATTACHMENT
                            | com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_SRC,
                    GpuFormat.RGBA8_UNORM,
                    width,
                    height,
                    1,
                    1
            )) {
                MetalCommandEncoder encoder = device.commandEncoder();
                assertTrue(encoder.encodeTextureCopy(mainTarget, presentCopy, true));
                encoder.submit();
                device.waitForSubmittedGpuWork();
                assertDisplayEncodedRamp(device, presentCopy, width, "present fragment output");
            }

            assertFalse(chain.executeColorSpace(device, targets, mainView, ColorSpace.SRGB));
            ByteBuffer srgb = readback(device, mainTarget);
            assertTrue(chain.executeColorSpace(device, targets, mainView, ColorSpace.DCI_P3));
            ByteBuffer dciP3 = readback(device, mainTarget);
            int midpoint = (width / 2) * 4;
            assertNotEquals(
                    srgb.getInt(midpoint),
                    dciP3.getInt(midpoint),
                    "DCI-P3 converter must replace the sRGB-encoded MainTarget values"
            );
            assertEquals(
                    Byte.toUnsignedInt(srgb.get(midpoint + 3)),
                    Byte.toUnsignedInt(dciP3.get(midpoint + 3)),
                    "color-space conversion must preserve alpha"
            );
        }
    }

    private static void assertBufferWord(
            final MetalDevice device,
            final GpuBufferSlice source,
            final int word,
            final int expected,
            final String label
    ) {
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "iris compute conformance SSBO readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                Integer.BYTES
        )) {
            MetalCommandEncoder encoder = device.commandEncoder();
            encoder.copyToBuffer(source.slice((long) word * Integer.BYTES, Integer.BYTES), buffer.slice());
            encoder.submit();
            device.waitForSubmittedGpuWork();
            assertEquals(expected, buffer.currentStorage().order(ByteOrder.nativeOrder()).getInt(0), label);
        }
    }

    private static void assertRgba(
            final MetalDevice device,
            final MetalGpuTexture texture,
            final int red,
            final int green,
            final int blue,
            final String label
    ) {
        int size = texture.getWidth(0) * texture.getHeight(0) * texture.pixelSize();
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "iris compute conformance readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                size
        )) {
            MetalCommandEncoder encoder = device.commandEncoder();
            encoder.copyTextureToBuffer(texture, buffer, 0L, () -> { }, 0);
            encoder.submit();
            device.waitForSubmittedGpuWork();
            ByteBuffer data = buffer.currentStorage();
            assertEquals(red, Byte.toUnsignedInt(data.get(0)), label + " red");
            assertEquals(green, Byte.toUnsignedInt(data.get(1)), label + " green");
            assertEquals(blue, Byte.toUnsignedInt(data.get(2)), label + " blue");
        }
    }

    private static void assertDisplayEncodedRamp(
            final MetalDevice device,
            final MetalGpuTexture texture,
            final int width,
            final String label
    ) {
        ByteBuffer data = readback(device, texture);
        assertGray(data, width / 4, 46, label + " 18% gray");
        assertGray(data, width / 2, 128, label + " 0.5 gray");
        assertGray(data, width * 3 / 4, 255, label + " >1 clamp");
    }

    private static ByteBuffer readback(final MetalDevice device, final MetalGpuTexture texture) {
        int size = texture.getWidth(0) * texture.getHeight(0) * texture.pixelSize();
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "iris color contract readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                size
        )) {
            MetalCommandEncoder encoder = device.commandEncoder();
            encoder.copyTextureToBuffer(texture, buffer, 0L, () -> { }, 0);
            encoder.submit();
            device.waitForSubmittedGpuWork();
            ByteBuffer copy = ByteBuffer.allocate(size);
            copy.put(buffer.currentStorage().duplicate().limit(size));
            copy.flip();
            return copy;
        }
    }

    private static void assertGray(
            final ByteBuffer data,
            final int x,
            final int expected,
            final String label
    ) {
        int offset = x * 4;
        assertEquals(expected, Byte.toUnsignedInt(data.get(offset)), label + " red");
        assertEquals(expected, Byte.toUnsignedInt(data.get(offset + 1)), label + " green");
        assertEquals(expected, Byte.toUnsignedInt(data.get(offset + 2)), label + " blue");
    }

    private static Path fixturePath() throws URISyntaxException {
        var resource = IrisMetalComputeConformanceTest.class.getResource("/iris-conformance-compute/shaders");
        assertNotNull(resource, "missing Iris compute conformance fixture");
        return Path.of(resource.toURI());
    }

    private static ImmutableList<StringPair> environmentDefines() {
        return StandardMacros.createStandardEnvironmentDefines();
    }
}
