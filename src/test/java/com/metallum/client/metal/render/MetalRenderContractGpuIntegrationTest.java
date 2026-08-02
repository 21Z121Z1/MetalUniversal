package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.validation.contract.AttachmentSemantic;
import com.metallum.client.validation.contract.CapturePoint;
import com.metallum.client.validation.contract.CapturePointKind;
import com.metallum.client.validation.contract.RenderContractRuntime;
import com.metallum.client.validation.expectation.ExactExpectation;
import com.metallum.client.validation.expectation.ExpectationSpec;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real Metal texture capture through the production render-contract boundary. */
@EnabledOnOs(OS.MAC)
final class MetalRenderContractGpuIntegrationTest {
    private static final int WIDTH = 8;
    private static final int HEIGHT = 2;
    private static final int TEXTURE_USAGE =
            com.mojang.blaze3d.textures.GpuTexture.USAGE_RENDER_ATTACHMENT
                    | com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_SRC;
    private static final String VERTEX_SHADER = """
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
    private static final String FRAGMENT_SHADER = """
            #version 450
            layout(location=0) out vec4 color0;
            layout(location=1) out vec4 color1;
            void main() {
                color0 = vec4(1.0, 0.0, 0.0, 1.0);
                color1 = vec4(0.0, 1.0, 0.0, 1.0);
            }
            """;

    private final Map<String, String> shaders = new HashMap<>();
    private MetalDevice device;
    private MetalCommandEncoder encoder;
    private Path output;

    @BeforeEach
    void createDevice() throws Exception {
        System.setProperty("metallum.renderContract.enabled", "true");
        System.setProperty("metallum.renderContract.runId", "native-gpu-contract");
        System.setProperty("metallum.renderContract.maxCaptures", "8");
        System.setProperty("metallum.renderContract.maxBytes", "1048576");
        boolean persist = Boolean.getBoolean("metallum.renderContract.persist");
        output = persist
                ? Path.of("build/render-contract/native-gpu-contract-metal"
                        + (Boolean.getBoolean("metallum.opt.metal4") ? "4" : "3"))
                : Files.createTempDirectory("metallum-render-contract-native-");
        if (persist) {
            deleteRecursively(output);
        }
        RenderContractRuntime.start(output, "native-gpu-contract");
        RenderContractRuntime.beginFrame(0L);

        shaders.put("contract_vertex", VERTEX_SHADER);
        shaders.put("contract_fragment", FRAGMENT_SHADER);
        MemorySegment nativeDevice = MetalNativeBridge.metallum_create_system_default_device();
        assertFalse(MetalNativeBridge.isNullHandle(nativeDevice), "MTLCreateSystemDefaultDevice returned null");
        ShaderSource source = (identifier, type) -> type == ShaderType.VERTEX
                ? shaders.get("contract_vertex")
                : shaders.get("contract_fragment");
        device = new MetalDevice(
                source,
                new GpuDebugOptions(2, true, true, true),
                nativeDevice,
                MemorySegment.NULL,
                "Metal render-contract GPU integration device",
                MemorySegment.NULL
        );
        encoder = device.commandEncoder();
    }

    @AfterEach
    void closeDevice() throws Exception {
        try {
            RenderContractRuntime.close();
        } finally {
            MetalFxManager.close();
            if (device != null) {
                device.close();
            }
            System.clearProperty("metallum.renderContract.enabled");
            System.clearProperty("metallum.renderContract.runId");
            System.clearProperty("metallum.renderContract.maxCaptures");
            System.clearProperty("metallum.renderContract.maxBytes");
            if (!Boolean.getBoolean("metallum.renderContract.persist") && output != null) {
                deleteRecursively(output);
            }
        }
    }

    @Test
    void capturesRealMrtAttachmentsAndEvaluatesExactExpectations() throws Exception {
        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation("synthetic/mrt-basic")
                .withVertexShader("synthetic/contract_vertex")
                .withFragmentShader("synthetic/contract_fragment")
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withColorTargetState(0, new ColorTargetState(
                        Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                .withColorTargetState(1, new ColorTargetState(
                        Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                .build();
        MetalGpuTexture color0 = (MetalGpuTexture) device.createTexture(
                "color0", TEXTURE_USAGE, GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 1);
        MetalGpuTexture color1 = (MetalGpuTexture) device.createTexture(
                "color1", TEXTURE_USAGE, GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 1);
        CapturePoint point = new CapturePoint(0L, "synthetic/mrt-basic", CapturePointKind.AFTER_PASS, -1);
        RenderContractRuntime.ReadbackRequest request0 = request("color0", color0);
        RenderContractRuntime.ReadbackRequest request1 = request("color1", color1);
        RenderContractRuntime.requestReadbacks(
                point,
                List.of(request0, request1),
                List.of(
                        ExpectationSpec.forResource("color0-exact", "color0",
                                new ExactExpectation(expectedColor(255, 0, 0, 255))),
                        ExpectationSpec.forResource("color1-exact", "color1",
                                new ExactExpectation(expectedColor(0, 255, 0, 255)))
                )
        );

        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "synthetic/mrt-basic")
                .withRenderArea(new RenderPass.RenderArea(0, 0, WIDTH, HEIGHT));
        try (MetalGpuTextureView view0 = new MetalGpuTextureView(color0, 0, 1);
             MetalGpuTextureView view1 = new MetalGpuTextureView(color1, 0, 1)) {
            descriptor.withColorAttachment(view0, Optional.of(new org.joml.Vector4f(0.0F)));
            descriptor.withColorAttachment(view1, Optional.of(new org.joml.Vector4f(0.0F)));
            MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor);
            pass.setPipeline(pipeline);
            pass.draw(3, 1, 0, 0);
            encoder.submitRenderPass();
            encoder.submit();
            device.waitForSubmittedGpuWork();
        }

        int size = WIDTH * HEIGHT * color0.pixelSize();
        try (MetalGpuBuffer buffer0 = (MetalGpuBuffer) device.createBuffer(
                () -> "contract color0 readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, size);
             MetalGpuBuffer buffer1 = (MetalGpuBuffer) device.createBuffer(
                     () -> "contract color1 readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, size)) {
            encoder.copyTextureToBuffer(color0, buffer0, 0L, () -> { }, 0);
            encoder.copyTextureToBuffer(color1, buffer1, 0L, () -> { }, 0);
            encoder.submit();
            device.waitForSubmittedGpuWork();
            RenderContractRuntime.recordReadbacks(
                    point,
                    List.of(
                            readback("color0", color0, bytes(buffer0, size)),
                            readback("color1", color1, bytes(buffer1, size))
                    ),
                    List.of()
            );
        }

        RenderContractRuntime.endFrame(0L);
        assertTrue(RenderContractRuntime.completionGatePassed(), RenderContractRuntime.snapshot().toString());
        assertEquals(1, RenderContractRuntime.snapshot().completedCaptures());
        assertEquals(0, RenderContractRuntime.snapshot().failedCaptures());
        assertTrue(Files.exists(output.resolve("render-contract/pass-manifest.json")));

        color0.close();
        color1.close();
    }

    private static RenderContractRuntime.ReadbackRequest request(
            final String name,
            final MetalGpuTexture texture
    ) {
        return new RenderContractRuntime.ReadbackRequest(
                name,
                texture.validationResourceId(),
                texture.validationDebugId(),
                texture.getFormat().toString(),
                texture.pixelSize(),
                WIDTH,
                HEIGHT,
                texture.getDepthOrLayers(),
                0,
                1,
                texture.usage(),
                AttachmentSemantic.COLOR
        );
    }

    private static RenderContractRuntime.ReadbackData readback(
            final String name,
            final MetalGpuTexture texture,
            final byte[] bytes
    ) {
        return new RenderContractRuntime.ReadbackData(
                name,
                texture.validationResourceId(),
                texture.validationDebugId(),
                texture.getFormat().toString(),
                texture.pixelSize(),
                WIDTH,
                HEIGHT,
                texture.getDepthOrLayers(),
                0,
                1,
                texture.usage(),
                bytes
        );
    }

    private static byte[] bytes(final MetalGpuBuffer buffer, final int size) {
        ByteBuffer source = buffer.currentStorage().limit(size).slice().order(ByteOrder.nativeOrder());
        byte[] result = new byte[size];
        source.get(result);
        return result;
    }

    private static byte[] expectedColor(final int red, final int green, final int blue, final int alpha) {
        byte[] result = new byte[WIDTH * HEIGHT * 4];
        for (int offset = 0; offset < result.length; offset += 4) {
            result[offset] = (byte) red;
            result[offset + 1] = (byte) green;
            result[offset + 2] = (byte) blue;
            result[offset + 3] = (byte) alpha;
        }
        return result;
    }

    private static void deleteRecursively(final Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            });
        }
    }
}
