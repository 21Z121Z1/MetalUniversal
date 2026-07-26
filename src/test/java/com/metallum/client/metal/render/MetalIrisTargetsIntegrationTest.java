package com.metallum.client.metal.render;

import com.metallum.client.metal.render.IrisMetalRenderTargets.RenderPassDescriptorWithViews;
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
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Content-level (no screen observation) validation of the Iris target
 * framework: colortex main/alt ping-pong flipping, snapshot/restore,
 * feedback-loop guard, depthtex0/1/2 copy semantics, shadow targets with
 * state isolation, and resize resets — all through the production backend
 * with GPU readback of every asserted texture.
 */
@EnabledOnOs(OS.MAC)
final class MetalIrisTargetsIntegrationTest {
    private static final int WIDTH = 32;
    private static final int HEIGHT = 8;

    private final Map<String, String> fragmentShaders = new HashMap<>();
    private final Map<String, String> vertexShaders = new HashMap<>();
    private MetalDevice device;
    private MetalCommandEncoder encoder;

    @BeforeEach
    void createDevice() {
        MemorySegment nativeDevice = MetalNativeBridge.metallum_create_system_default_device();
        assertFalse(MetalNativeBridge.isNullHandle(nativeDevice), "MTLCreateSystemDefaultDevice returned null");
        ShaderSource source = (identifier, type) -> {
            String name = identifier.getPath().substring(identifier.getPath().lastIndexOf('/') + 1);
            return type == ShaderType.VERTEX
                    ? vertexShaders.getOrDefault(name, FULLSCREEN_VERTEX)
                    : fragmentShaders.get(name);
        };
        device = new MetalDevice(
                source,
                new GpuDebugOptions(2, true, true, true),
                nativeDevice,
                MemorySegment.NULL,
                "Iris targets integration device",
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
    void pingPongThreePassChainKeepsBothSidesCorrect() {
        registerConstantFragment("iris_red", "vec4(1.0, 0.0, 0.0, 1.0)");
        registerConstantFragment("iris_green", "vec4(0.0, 1.0, 0.0, 1.0)");
        registerConstantFragment("iris_blue", "vec4(0.0, 0.0, 1.0, 1.0)");
        try (IrisMetalRenderTargets targets = new IrisMetalRenderTargets(
                device, new GpuFormat[]{GpuFormat.RGBA8_UNORM, GpuFormat.RG16_FLOAT}, WIDTH, HEIGHT)) {
            IrisMetalPingPongTargets color = targets.colorTargets();
            assertFalse(color.isFlipped(0));
            assertFalse(color.flippedAtLeastOnce(0));

            // Pass 1: write red to the write side (alt), then flip -> reads see red.
            runColorPass(targets, "iris_red", new int[]{0});
            color.flip(0);
            assertTrue(color.isFlipped(0));
            assertTrue(color.flippedAtLeastOnce(0));
            assertRgba(color.readTexture(0), 255, 0, 0, "read side after pass1+flip");

            // Pass 2: write green (lands on the former main), flip again.
            runColorPass(targets, "iris_green", new int[]{0});
            color.flip(0);
            assertFalse(color.isFlipped(0));
            assertRgba(color.readTexture(0), 0, 255, 0, "read side after pass2+flip");
            // History side must still hold pass1's red until overwritten.
            assertRgba(color.writeTexture(0), 255, 0, 0, "write side keeps previous history");

            // Pass 3 without flip: write blue; the read side must stay green.
            runColorPass(targets, "iris_blue", new int[]{0});
            assertRgba(color.readTexture(0), 0, 255, 0, "read side unchanged without flip");
            assertRgba(color.writeTexture(0), 0, 0, 255, "write side holds pass3 output");
        }
    }

    @Test
    void snapshotAndRestoreRewindFlipState() {
        try (IrisMetalRenderTargets targets = new IrisMetalRenderTargets(
                device, new GpuFormat[]{GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM},
                WIDTH, HEIGHT)) {
            IrisMetalPingPongTargets color = targets.colorTargets();
            color.flip(0);
            color.flip(2);
            BitSet snapshot = color.snapshot();
            color.flip(1);
            color.flip(2);
            assertTrue(color.isFlipped(1));
            assertFalse(color.isFlipped(2));
            color.restore(snapshot);
            assertTrue(color.isFlipped(0), "restore must rewind target 0");
            assertFalse(color.isFlipped(1), "restore must rewind target 1");
            assertTrue(color.isFlipped(2), "restore must rewind target 2");
            assertTrue(color.flippedAtLeastOnce(1), "history flag is monotonic across restore");
        }
    }

    @Test
    void feedbackLoopGuardRejectsSameTargetReadWrite() {
        try (IrisMetalRenderTargets targets = new IrisMetalRenderTargets(
                device, new GpuFormat[]{GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM}, WIDTH, HEIGHT)) {
            IllegalStateException loop = assertThrows(
                    IllegalStateException.class,
                    () -> targets.createWriteDescriptor(
                            "feedback", new int[]{0, 1}, null, false, null, new int[]{1})
            );
            assertTrue(loop.getMessage().contains("feedback loop"));
            // Disjoint read/write sets must pass.
            try (RenderPassDescriptorWithViews ok = targets.createWriteDescriptor(
                    "no-feedback", new int[]{0}, null, false, null, new int[]{1})) {
                assertNotNull(ok.descriptor());
            }
        }
    }

    @Test
    void depthCaptureSemanticsProduceThreeDistinctDepthTextures() {
        registerConstantFragment("iris_depth_pass", "vec4(1.0)");
        registerDepthVertex("iris_depth_025", "0.25");
        registerDepthVertex("iris_depth_050", "0.5");
        registerDepthVertex("iris_depth_075", "0.75");
        try (IrisMetalRenderTargets targets = new IrisMetalRenderTargets(
                device, new GpuFormat[]{GpuFormat.RGBA8_UNORM}, WIDTH, HEIGHT)) {
            // Opaque geometry at z=0.25.
            runDepthPass(targets, "iris_depth_025", 1.0);
            targets.captureNoTranslucentsDepth(encoder);
            // Translucent geometry at z=0.5 (always-pass overwrite for the test).
            runDepthPass(targets, "iris_depth_050", null);
            targets.captureNoHandDepth(encoder);
            // Hand at z=0.75.
            runDepthPass(targets, "iris_depth_075", null);

            assertDepth(targets.noTranslucentsDepthTexture(), 0.25F, "depthtex1 (no translucents)");
            assertDepth(targets.noHandDepthTexture(), 0.5F, "depthtex2 (no hand)");
            assertDepth(targets.mainDepthTexture(), 0.75F, "depthtex0 (main)");
        }
    }

    @Test
    void shadowTargetsHoldDepthAndColorWithIsolationAndResize() {
        registerConstantFragment("iris_shadow_white", "vec4(1.0, 1.0, 1.0, 1.0)");
        registerDepthVertex("iris_shadow_030", "0.3");
        registerDepthVertex("iris_shadow_010", "0.1");
        try (IrisMetalShadowTargets shadow = new IrisMetalShadowTargets(
                device, new GpuFormat[]{GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM}, 128);
             IrisMetalRenderTargets main = new IrisMetalRenderTargets(
                     device, new GpuFormat[]{GpuFormat.RGBA8_UNORM}, WIDTH, HEIGHT)) {
            registerConstantFragment("iris_main_red", "vec4(1.0, 0.0, 0.0, 1.0)");
            runColorPass(main, "iris_main_red", new int[]{0});

            // Opaque shadow casters at z=0.3 writing shadowcolor0.
            runShadowPass(shadow, "iris_shadow_030", "iris_shadow_white", 1.0);
            shadow.captureNoTranslucentsDepth(encoder);
            // Translucent casters at z=0.1 afterwards.
            runShadowPass(shadow, "iris_shadow_010", "iris_shadow_white", null);

            assertDepth(shadow.shadowDepthTexture(), 0.1F, "shadowtex0 after translucents");
            assertDepth(shadow.shadowDepthNoTranslucentsTexture(), 0.3F, "shadowtex1 (no translucents)");
            assertRgba(shadow.colorTargets().writeTexture(0), 255, 255, 255, "shadowcolor0 write side");

            // Main targets must be untouched by shadow encoding (state isolation).
            assertRgba(main.colorTargets().writeTexture(0), 255, 0, 0, "main colortex isolated from shadow pass");

            // Pack-config resize rebuilds shadow textures at the new square size.
            shadow.resize(64);
            assertEquals(64, shadow.resolution());
            assertEquals(64, shadow.shadowDepthTexture().getWidth(0));
            runShadowPass(shadow, "iris_shadow_030", "iris_shadow_white", 1.0);
            assertDepth(shadow.shadowDepthTexture(), 0.3F, "shadowtex0 after resize re-render");
        }
    }

    @Test
    void resizeResetsFlipStateAndUsesNewExtent() {
        registerConstantFragment("iris_resize_red", "vec4(1.0, 0.0, 0.0, 1.0)");
        try (IrisMetalRenderTargets targets = new IrisMetalRenderTargets(
                device, new GpuFormat[]{GpuFormat.RGBA8_UNORM}, WIDTH, HEIGHT)) {
            IrisMetalPingPongTargets color = targets.colorTargets();
            runColorPass(targets, "iris_resize_red", new int[]{0});
            color.flip(0);
            assertTrue(color.isFlipped(0));

            targets.resize(WIDTH * 2, HEIGHT * 2);
            assertFalse(color.isFlipped(0), "resize must reset flip state");
            assertFalse(color.flippedAtLeastOnce(0), "resize must reset flip history");
            assertEquals(WIDTH * 2, targets.width());
            assertEquals(WIDTH * 2, color.readTexture(0).getWidth(0), "textures must be rebuilt at the new extent");

            runColorPass(targets, "iris_resize_red", new int[]{0});
            color.flip(0);
            assertRgba(color.readTexture(0), 255, 0, 0, "post-resize render lands in fresh textures");
        }
    }

    private static final String FULLSCREEN_VERTEX = """
            #version 450
            void main() {
                vec2 positions[3] = vec2[](
                    vec2(-1.0, -1.0),
                    vec2( 3.0, -1.0),
                    vec2(-1.0,  3.0)
                );
                gl_Position = vec4(positions[gl_VertexIndex], 0.0, 1.0);
            }
            """;

    private void registerConstantFragment(final String name, final String color) {
        fragmentShaders.put(name, """
                #version 450
                layout(location=0) out vec4 fragColor;
                void main() { fragColor = %s; }
                """.formatted(color));
    }

    private void registerDepthVertex(final String name, final String z) {
        vertexShaders.put(name, """
                #version 450
                void main() {
                    vec2 positions[3] = vec2[](
                        vec2(-1.0, -1.0),
                        vec2( 3.0, -1.0),
                        vec2(-1.0,  3.0)
                    );
                    gl_Position = vec4(positions[gl_VertexIndex], %s, 1.0);
                }
                """.formatted(z));
    }

    private void runColorPass(final IrisMetalRenderTargets targets, final String fragment, final int[] drawBuffers) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation("metallum_iris/" + fragment)
                .withVertexShader("metallum_iris/fullscreen")
                .withFragmentShader("metallum_iris/" + fragment)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false);
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            builder.withColorTargetState(slot, new ColorTargetState(
                    Optional.empty(),
                    targets.colorTargets().format(drawBuffers[slot]),
                    ColorTargetState.WRITE_ALL));
        }
        RenderPipeline pipeline = builder.build();
        Vector4fc[] clears = new Vector4fc[drawBuffers.length];
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            clears[slot] = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F);
        }
        try (RenderPassDescriptorWithViews pass = targets.createWriteDescriptor(
                "iris pass " + fragment, drawBuffers, clears, false, null, null)) {
            MetalRenderPass renderPass = (MetalRenderPass) encoder.createRenderPass(pass.descriptor());
            renderPass.setPipeline(pipeline);
            renderPass.draw(3, 1, 0, 0);
            encoder.submitRenderPass();
        }
        encoder.submit();
        device.waitForSubmittedGpuWork();
    }

    private void runDepthPass(final IrisMetalRenderTargets targets, final String vertexName, final Double clearDepth) {
        registerConstantFragment("iris_depth_fill", "vec4(1.0)");
        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation("metallum_iris/depth_" + vertexName)
                .withVertexShader("metallum_iris/" + vertexName)
                .withFragmentShader("metallum_iris/iris_depth_fill")
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
                .withColorTargetState(0, new ColorTargetState(
                        Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                .build();
        try (RenderPassDescriptorWithViews pass = targets.createWriteDescriptor(
                "iris depth pass " + vertexName,
                new int[]{0},
                new Vector4fc[]{new Vector4f(0.0F, 0.0F, 0.0F, 1.0F)},
                true,
                clearDepth,
                null)) {
            MetalRenderPass renderPass = (MetalRenderPass) encoder.createRenderPass(pass.descriptor());
            renderPass.setPipeline(pipeline);
            renderPass.draw(3, 1, 0, 0);
            encoder.submitRenderPass();
        }
        encoder.submit();
        device.waitForSubmittedGpuWork();
    }

    private void runShadowPass(
            final IrisMetalShadowTargets shadow,
            final String vertexName,
            final String fragmentName,
            final Double clearDepth
    ) {
        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation("metallum_iris/shadow_" + vertexName)
                .withVertexShader("metallum_iris/" + vertexName)
                .withFragmentShader("metallum_iris/" + fragmentName)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
                .withColorTargetState(0, new ColorTargetState(
                        Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                .build();
        try (IrisMetalRenderTargets.RenderPassDescriptorWithViews pass = shadow.createShadowWriteDescriptor(
                "iris shadow pass " + vertexName,
                new int[]{0},
                new Vector4fc[]{new Vector4f(0.0F, 0.0F, 0.0F, 0.0F)},
                clearDepth)) {
            MetalRenderPass renderPass = (MetalRenderPass) encoder.createRenderPass(pass.descriptor());
            renderPass.setPipeline(pipeline);
            renderPass.draw(3, 1, 0, 0);
            encoder.submitRenderPass();
        }
        encoder.submit();
        device.waitForSubmittedGpuWork();
    }

    private void assertRgba(final MetalGpuTexture texture, final int red, final int green, final int blue, final String label) {
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
                () -> "iris targets readback",
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

    private static void assertByteNear(final byte actualByte, final int expected, final String label) {
        int actual = Byte.toUnsignedInt(actualByte);
        assertTrue(Math.abs(actual - expected) <= 2, label + ": expected " + expected + ", got " + actual);
    }
}
