package com.metallum.client.metal.render;

import com.google.common.collect.ImmutableList;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.pathways.colorspace.ColorSpace;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-device conformance for a shader-pack-owned shadowcomp storage image. */
@EnabledOnOs(OS.MAC)
final class IrisMetalShadowComputeConformanceTest {
    private static final int RESOLUTION = 8;

    @Test
    void shadowCompositeComputePublishesShadowcolorImage() throws Exception {
        Iris.testing = true;
        ShaderPack pack = new ShaderPack(fixturePath(), environmentDefines(), false);
        ProgramSet programSet = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
        assertEquals(RESOLUTION, programSet.getPackDirectives().getShadowDirectives().getResolution());
        IrisMetalPackAdmission.requireSupported(programSet, ColorSpace.SRGB);
        assertTrue(programSet.get(ProgramId.ShadowSolid).isPresent());
        assertTrue(programSet.getCompute(ProgramArrayId.ShadowComposite).length > 0);
        assertTrue(
                java.util.Arrays.stream(programSet.getCompute(ProgramArrayId.ShadowComposite)[0])
                        .filter(java.util.Objects::nonNull)
                        .count() >= 2,
                "shadowcomp slot 0 must contain the producer and consumer computes"
        );

        MemorySegment nativeDevice = MetalNativeBridge.metallum_create_system_default_device();
        assertFalse(MetalNativeBridge.isNullHandle(nativeDevice));
        ShaderSource fallback = (identifier, type) -> null;
        MetalDevice device = new MetalDevice(
                fallback,
                new GpuDebugOptions(2, true, true, true),
                nativeDevice,
                MemorySegment.NULL,
                "Iris shadow compute conformance device",
                MemorySegment.NULL
        );
        IrisMetalShadowPipeline pipeline = new IrisMetalShadowPipeline(device, programSet, 1);
        IrisMetalUniformValues values = new IrisMetalUniformValues(0.0F);
        try {
            pipeline.registerUniforms(values);
            ComputeSource source = null;
            for (ComputeSource[] group : programSet.getCompute(ProgramArrayId.ShadowComposite)) {
                if (group != null) {
                    for (ComputeSource candidate : group) {
                        if (candidate != null && candidate.isValid()) {
                            source = candidate;
                            break;
                        }
                    }
                }
                if (source != null) {
                    break;
                }
            }
            assertNotNull(source);
            var translated = MetalIrisShaderCompiler.translateCompute(
                    source.getName(), source.getSource().orElseThrow(), TextureStage.SHADOWCOMP
            );
            var reflection = translated.compute().orElseThrow().computeReflection();
            assertNotNull(reflection);
            assertEquals(8, reflection.localSizeX());
            assertEquals(8, reflection.localSizeY());
            assertEquals(1, reflection.localSizeZ());
            values.prewarm(device);
            pipeline.prepare(device, fallback);
            pipeline.executeFrame(
                    device,
                    new IrisMetalShadowPipeline.LevelRendererAdapter() {
                        @Override
                        public void renderOpaqueShadows() {
                        }

                        @Override
                        public void renderTranslucentShadows() {
                        }
                    },
                    resources(values)
            );
            assertEquals(IrisMetalShadowPipeline.Phase.COMPLETE, pipeline.phase());
            BitSet finalReads = pipeline.finalReadsFromAlt();
            assertFalse(finalReads.get(0), "compute-only shadowcomp must retain its write side");
            assertEquals(RESOLUTION, pipeline.targets().colorTexture(0, finalReads).getWidth(0));
            assertEquals(RESOLUTION, pipeline.targets().colorTexture(0, finalReads).getHeight(0));
            assertRgba(device, pipeline.targets().colorTexture(0, finalReads), "shadowcolor producer");
            assertRgba(device, pipeline.targets().colorTexture(1, finalReads), "shadowcolor consumer");
        } finally {
            pipeline.close();
            values.close();
            MetalFxManager.close();
            device.close();
        }
    }

    private static IrisMetalPostChain.ResourceProvider resources(final IrisMetalUniformValues values) {
        return new IrisMetalPostChain.ResourceProvider() {
            @Override
            public IrisMetalPostChain.@Nullable TextureBinding texture(
                    IrisMetalPostChain.PassInfo pass,
                    String samplerName
            ) {
                return null;
            }

            @Override
            public @Nullable GpuBufferSlice uniform(
                    IrisMetalPostChain.PassInfo pass,
                    String blockName
            ) {
                return null;
            }

            @Override
            public @Nullable GpuBufferSlice uniform(
                    IrisMetalPostChain.PassInfo pass,
                    String blockName,
                    Object token
            ) {
                return MetalIrisShaderCompiler.UNIFORM_BLOCK_NAME.equals(blockName)
                        ? values.slice(token)
                        : null;
            }
        };
    }

    private static void assertRgba(
            final MetalDevice device,
            final MetalGpuTexture texture,
            final String label
    ) {
        int size = texture.getWidth(0) * texture.getHeight(0) * texture.pixelSize();
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "iris shadow compute readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                size
        )) {
            MetalCommandEncoder encoder = device.commandEncoder();
            encoder.copyTextureToBuffer(texture, buffer, 0L, () -> { }, 0);
            encoder.submit();
            device.waitForSubmittedGpuWork();
            ByteBuffer data = buffer.currentStorage();
            int pixels = texture.getWidth(0) * texture.getHeight(0);
            StringBuilder actual = new StringBuilder();
            for (int pixel = 0; pixel < pixels; pixel++) {
                int offset = pixel * texture.pixelSize();
                actual.append('(')
                        .append(Byte.toUnsignedInt(data.get(offset))).append(',')
                        .append(Byte.toUnsignedInt(data.get(offset + 1))).append(',')
                        .append(Byte.toUnsignedInt(data.get(offset + 2))).append(')');
            }
            System.out.println("[shadow-compute-readback] " + label + " " + actual);
            for (int pixel = 0; pixel < pixels; pixel++) {
                int offset = pixel * texture.pixelSize();
                assertEquals(0, Byte.toUnsignedInt(data.get(offset)), label + " red pixel " + pixel);
                assertEquals(128, Byte.toUnsignedInt(data.get(offset + 1)), label + " green pixel " + pixel);
                assertEquals(255, Byte.toUnsignedInt(data.get(offset + 2)), label + " blue pixel " + pixel);
                assertEquals(255, Byte.toUnsignedInt(data.get(offset + 3)), label + " alpha pixel " + pixel);
            }
        }
    }

    private static Path fixturePath() throws URISyntaxException {
        var resource = IrisMetalShadowComputeConformanceTest.class
                .getResource("/iris-conformance-shadow-compute/shaders");
        assertNotNull(resource, "missing Iris shadow compute conformance fixture");
        return Path.of(resource.toURI());
    }

    private static ImmutableList<StringPair> environmentDefines() {
        return StandardMacros.createStandardEnvironmentDefines();
    }
}
