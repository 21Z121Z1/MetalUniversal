package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.vertices.sodium.terrain.FormatAnalyzer;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GPU-content checks for the shadow-only attachment and flip contract. */
@EnabledOnOs(OS.MAC)
final class IrisMetalShadowPipelineTest {
    private static final int RESOLUTION = 32;

    private final Map<String, String> fragments = new HashMap<>();
    private MetalDevice device;
    private MetalCommandEncoder encoder;

    void createDevice() {
        MemorySegment nativeDevice = MetalNativeBridge.metallum_create_system_default_device();
        assertFalse(MetalNativeBridge.isNullHandle(nativeDevice));
        ShaderSource source = (identifier, type) -> {
            String name = identifier.getPath().substring(identifier.getPath().lastIndexOf('/') + 1);
            return type == ShaderType.VERTEX ? FULLSCREEN_VERTEX : fragments.get(name);
        };
        device = new MetalDevice(
                source,
                new GpuDebugOptions(2, true, true, true),
                nativeDevice,
                MemorySegment.NULL,
                "Iris shadow pipeline test device",
                MemorySegment.NULL
        );
        encoder = device.commandEncoder();
    }

    @AfterEach
    void closeDevice() {
        if (device != null) {
            device.close();
        }
    }

    @Test
    void shadowGbufferAndCompositeUseIrisPhysicalSides() {
        createDevice();
        fragments.put("red", fragment("vec4(1.0, 0.0, 0.0, 1.0)"));
        fragments.put("blue", fragment("vec4(0.0, 0.0, 1.0, 1.0)"));
        fragments.put("green", fragment("vec4(0.0, 1.0, 0.0, 1.0)"));

        try (IrisMetalShadowTargets targets = new IrisMetalShadowTargets(
                device,
                new GpuFormat[]{GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM},
                RESOLUTION
        )) {
            BitSet main = new BitSet();
            BitSet alt = new BitSet();
            alt.set(0, 2);
            for (int index = 0; index < 2; index++) {
                encoder.clearColorTexture(targets.colorTexture(index, main), new Vector4f(0.0F));
                encoder.clearColorTexture(targets.colorTexture(index, alt), new Vector4f(0.0F));
            }

            runGbuffer(targets, "red", 0.3);
            targets.captureNoTranslucentsDepth(encoder);
            encoder.submit();
            device.waitForSubmittedGpuWork();

            assertRgba(targets.colorTexture(0, main), 255, 0, 0, "shadow gbuffer main");
            assertRgba(targets.colorTexture(0, alt), 0, 0, 0, "shadow gbuffer leaves alt untouched");
            assertDepth(targets.shadowDepthTexture(), 0.3F, "shadowtex0");
            assertDepth(targets.shadowDepthNoTranslucentsTexture(), 0.3F, "shadowtex1 opaque snapshot");

            runComposite(targets, "blue", main);
            assertRgba(targets.colorTexture(0, main), 255, 0, 0, "pass one preserves main history");
            assertRgba(targets.colorTexture(0, alt), 0, 0, 255, "pass one writes alt");

            BitSet readsAlt = new BitSet();
            readsAlt.set(0);
            runComposite(targets, "green", readsAlt);
            assertRgba(targets.colorTexture(0, main), 0, 255, 0, "pass two writes main");
            assertRgba(targets.colorTexture(0, alt), 0, 0, 255, "pass two preserves alt history");

            targets.publishFlipState(main);
            assertRgba(targets.colorTargets().readTexture(0), 0, 255, 0, "published final read side");
            assertFalse(MetalNativeBridge.isNullHandle(targets.depthSampler(0, true).nativeHandle()));
        }
    }

    @Test
    void explicitFlipsApplyAfterDefaultDrawBufferFlips() {
        BitSet flipped = new BitSet();
        BitSet ever = new BitSet();
        IrisMetalShadowPipeline.applyPassFlips(
                flipped,
                ever,
                new int[]{0, 1},
                Map.of(0, false, 2, true),
                4
        );
        assertEquals(bitSetOf(1, 2), flipped);
        assertEquals(bitSetOf(1, 2), ever);

        flipped.clear();
        ever.clear();
        IrisMetalShadowPipeline.applyPassFlips(flipped, ever, new int[]{0}, Map.of(0, true), 2);
        assertTrue(flipped.isEmpty(), "explicit true flips a written target a second time");
        assertEquals(bitSetOf(0), ever, "flipped-at-least-once remains monotonic");
    }

    @Test
    void shadowVertexFormatMatchesIrisRuntimeResolution() {
        assertSame(
                ShaderKey.SHADOW_ENTITIES_CUTOUT.getVertexFormat(),
                IrisMetalShadowPipeline.resolveVertexFormat(ShaderKey.SHADOW_ENTITIES_CUTOUT),
                "vanilla shadow keys must retain the Iris-declared extended entity layout"
        );

        var chunkType = FormatAnalyzer.createFormat(true, true, true, true);
        WorldRenderingSettings.INSTANCE.setVertexFormat(chunkType);
        assertSame(
                chunkType.getVertexFormat(),
                IrisMetalShadowPipeline.resolveVertexFormat(ShaderKey.SHADOW_SODIUM_TERRAIN_CUTOUT),
                "Sodium shadow keys must use the live extended chunk layout"
        );

        WorldRenderingSettings.INSTANCE.setVertexFormat(null);
        assertThrows(
                IllegalStateException.class,
                () -> IrisMetalShadowPipeline.resolveVertexFormat(ShaderKey.SHADOW_SODIUM_TERRAIN_CUTOUT),
                "missing Iris chunk layout must not degrade to an empty/default Metal vertex binding"
        );
    }

    @Test
    void sodiumTerrainKindsSelectTheMatchingIrisShadowFamilies() {
        assertSame(ShaderKey.SHADOW_SODIUM_TERRAIN_SOLID,
                IrisMetalPipelineOverrides.TerrainKind.SOLID.shadowKey);
        assertSame(ShaderKey.SHADOW_SODIUM_TERRAIN_CUTOUT,
                IrisMetalPipelineOverrides.TerrainKind.CUTOUT.shadowKey);
        assertSame(ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT,
                IrisMetalPipelineOverrides.TerrainKind.TRANSLUCENT.shadowKey);
    }

    @Test
    void vanillaShadowProgramsRestoreMojangUniformBlockNames() {
        String vertex = """
                #version 450
                layout(std140) uniform iris_DynamicTransforms {
                    mat4 ModelViewMat;
                } iris_transforms;
                layout(std140) uniform iris_Fog {
                    vec4 FogColor;
                } iris_fogP;
                void main() {
                    gl_Position = iris_transforms.ModelViewMat * vec4(0.0, 0.0, 0.0, 1.0);
                }
                """;
        String fragment = """
                #version 450
                layout(location=0) out vec4 color;
                void main() {
                    color = vec4(1.0);
                }
                """;

        MetalIrisShaderCompiler.GlslProgram vanilla =
                IrisMetalShadowPipeline.linkShadowPatchedPair(
                        ShaderKey.SHADOW_ENTITIES_CUTOUT,
                        "shadow-vanilla-block-remap",
                        vertex,
                        fragment,
                        new int[]{0}
                );
        assertTrue(vanilla.uniformBlockNames().contains("DynamicTransforms"));
        assertTrue(vanilla.uniformBlockNames().contains("Fog"));
        assertFalse(vanilla.uniformBlockNames().contains("iris_DynamicTransforms"));
        assertFalse(vanilla.uniformBlockNames().contains("iris_Fog"));

        MetalIrisShaderCompiler.GlslProgram sodium =
                IrisMetalShadowPipeline.linkShadowPatchedPair(
                        ShaderKey.SHADOW_SODIUM_TERRAIN_SOLID,
                        "shadow-sodium-block-names",
                        vertex,
                        fragment,
                        new int[]{0}
                );
        assertTrue(sodium.uniformBlockNames().contains("iris_DynamicTransforms"));
        assertTrue(sodium.uniformBlockNames().contains("iris_Fog"));
    }

    @Test
    void shadowFeatureExtractionMatchesIrisEntityAndLightFilters() {
        assertTrue(MetalWorldRenderingPipeline.shouldExtractGeneralShadowEntity(false));
        assertFalse(MetalWorldRenderingPipeline.shouldExtractGeneralShadowEntity(true));

        assertTrue(MetalWorldRenderingPipeline.shouldExtractShadowPlayer(false, false));
        assertFalse(MetalWorldRenderingPipeline.shouldExtractShadowPlayer(true, false));
        assertFalse(MetalWorldRenderingPipeline.shouldExtractShadowPlayer(false, true));

        assertTrue(MetalWorldRenderingPipeline.shouldRenderLightBlockEntity(1));
        assertFalse(MetalWorldRenderingPipeline.shouldRenderLightBlockEntity(0));
    }

    @Test
    void shadowRasterStateMatchesIrisReverseZContract() {
        Map.ofEntries(
                Map.entry(CompareOp.ALWAYS_PASS, CompareOp.ALWAYS_PASS),
                Map.entry(CompareOp.LESS_THAN, CompareOp.GREATER_THAN),
                Map.entry(CompareOp.LESS_THAN_OR_EQUAL, CompareOp.GREATER_THAN_OR_EQUAL),
                Map.entry(CompareOp.EQUAL, CompareOp.EQUAL),
                Map.entry(CompareOp.NOT_EQUAL, CompareOp.NOT_EQUAL),
                Map.entry(CompareOp.GREATER_THAN_OR_EQUAL, CompareOp.LESS_THAN_OR_EQUAL),
                Map.entry(CompareOp.GREATER_THAN, CompareOp.LESS_THAN),
                Map.entry(CompareOp.NEVER_PASS, CompareOp.NEVER_PASS)
        ).forEach((sourceCompare, expectedCompare) -> {
            DepthStencilState source = new DepthStencilState(sourceCompare, true, 1.25F, -0.5F);
            IrisMetalShadowPipeline.ShadowRasterState physical =
                    IrisMetalShadowPipeline.adaptRasterState(source);

            assertFalse(physical.cull(), "Iris shadow draws must ignore source-pipeline culling");
            assertEquals(expectedCompare, physical.depthStencil().depthTest());
            assertTrue(physical.depthStencil().writeDepth());
            assertEquals(-1.25F, physical.depthStencil().depthBiasScaleFactor());
            assertEquals(0.5F, physical.depthStencil().depthBiasConstant());
        });

        IrisMetalShadowPipeline.ShadowRasterState withoutDepth =
                IrisMetalShadowPipeline.adaptRasterState(null);
        assertFalse(withoutDepth.cull());
        assertNull(withoutDepth.depthStencil());
    }

    private void runGbuffer(final IrisMetalShadowTargets targets, final String fragment, final double depth) {
        RenderPipeline pipeline = pipeline(fragment, true);
        try (IrisMetalRenderTargets.RenderPassDescriptorWithViews descriptor =
                     targets.createShadowGbufferDescriptor(
                             "shadow gbuffer", new int[]{0}, null, IrisMetalShadowPipeline.SHADOW_DEPTH_CLEAR)) {
            MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor.descriptor());
            pass.setPipeline(pipeline);
            pass.draw(3, 1, 0, 0);
            encoder.submitRenderPass();
        }
        encoder.submit();
        device.waitForSubmittedGpuWork();
    }

    private void runComposite(
            final IrisMetalShadowTargets targets,
            final String fragment,
            final BitSet readsFromAlt
    ) {
        RenderPipeline pipeline = pipeline(fragment, false);
        try (IrisMetalRenderTargets.RenderPassDescriptorWithViews descriptor =
                     targets.createShadowCompositeDescriptor(
                             "shadow composite " + fragment,
                             new int[]{0},
                             readsFromAlt,
                             0,
                             0,
                             RESOLUTION,
                             RESOLUTION
                     )) {
            MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor.descriptor());
            pass.setPipeline(pipeline);
            pass.draw(3, 1, 0, 0);
            encoder.submitRenderPass();
        }
        encoder.submit();
        device.waitForSubmittedGpuWork();
    }

    private RenderPipeline pipeline(final String fragment, final boolean depth) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation("metallum_iris/shadow_test_" + fragment + (depth ? "_depth" : ""))
                .withVertexShader("metallum_iris/fullscreen")
                .withFragmentShader("metallum_iris/" + fragment)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withColorTargetState(0, new ColorTargetState(
                        Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL));
        if (depth) {
            builder.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true));
        }
        return builder.build();
    }

    private static String fragment(final String color) {
        return """
                #version 450
                layout(location=0) out vec4 fragColor;
                void main() { fragColor = %s; }
                """.formatted(color);
    }

    private static BitSet bitSetOf(final int... indexes) {
        BitSet result = new BitSet();
        for (int index : indexes) {
            result.set(index);
        }
        return result;
    }

    private void assertRgba(
            final MetalGpuTexture texture,
            final int red,
            final int green,
            final int blue,
            final String label
    ) {
        ByteBuffer data = readback(texture);
        assertByteNear(data.get(0), red, label + " red");
        assertByteNear(data.get(1), green, label + " green");
        assertByteNear(data.get(2), blue, label + " blue");
    }

    private void assertDepth(final MetalGpuTexture texture, final float expected, final String label) {
        ByteBuffer data = readback(texture);
        assertEquals(expected, data.order(ByteOrder.nativeOrder()).getFloat(0), 0.001F, label);
    }

    private ByteBuffer readback(final MetalGpuTexture texture) {
        int size = texture.getWidth(0) * texture.getHeight(0) * texture.pixelSize();
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "iris shadow readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                size
        )) {
            encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
            }, 0);
            encoder.submit();
            device.waitForSubmittedGpuWork();
            ByteBuffer source = buffer.currentStorage().limit(size).slice().order(ByteOrder.nativeOrder());
            ByteBuffer copy = ByteBuffer.allocate(size).order(ByteOrder.nativeOrder());
            copy.put(source);
            copy.flip();
            return copy;
        }
    }

    private static void assertByteNear(final byte value, final int expected, final String label) {
        int actual = Byte.toUnsignedInt(value);
        assertTrue(Math.abs(actual - expected) <= 2, label + ": expected " + expected + ", got " + actual);
    }

    private static final String FULLSCREEN_VERTEX = """
            #version 450
            void main() {
                vec2 positions[3] = vec2[](
                    vec2(-1.0, -1.0),
                    vec2( 3.0, -1.0),
                    vec2(-1.0,  3.0)
                );
                gl_Position = vec4(positions[gl_VertexIndex], 0.3, 1.0);
            }
            """;
}
