package com.metallum.client.metal.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.stream.StreamSupport;

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

    @Test
    void eagerlyTracesIrisAllocationGenerationsAndRetainsIdentityAcrossUses() throws Exception {
        IrisMetalRenderTargets targets = new IrisMetalRenderTargets(
                device, new GpuFormat[]{GpuFormat.RGBA8_UNORM}, WIDTH, HEIGHT
        );
        IrisMetalShadowTargets shadow = new IrisMetalShadowTargets(
                device, new GpuFormat[]{GpuFormat.RGBA8_UNORM}, WIDTH
        );
        List<String> semanticNames = List.of(
                "iris-colortex0-main",
                "iris-colortex0-alt",
                "iris-depthtex0",
                "iris-depthtex1",
                "iris-depthtex2",
                "iris-shadowcolor0-main",
                "iris-shadowcolor0-alt",
                "iris-shadowtex0",
                "iris-shadowtex1"
        );
        try {
            MetalGpuTexture color = targets.colorTargets().readTexture(0);
            JsonObject initial = manifest();
            for (String semanticName : semanticNames) {
                assertEquals(1L, lifecycleCount(initial, "ALLOCATE", semanticName), semanticName);
                assertEquals(0L, lifecycleCount(initial, "INVALIDATE", semanticName), semanticName);
            }

            // The same level-zero lookup used by a readback must not create a
            // second allocation event after eager registration.
            RenderContractRuntime.requestReadbacks(
                    new CapturePoint(0L, "iris/eager-registration", CapturePointKind.AFTER_PASS, -1),
                    List.of(request(color.getLabel(), color)),
                    List.of()
            );
            JsonObject afterReadbackLookup = manifest();
            assertEquals(
                    lifecycleCount(initial, "ALLOCATE", "iris-colortex0-main"),
                    lifecycleCount(afterReadbackLookup, "ALLOCATE", "iris-colortex0-main")
            );

            targets.resize(WIDTH + 1, HEIGHT + 1);
            shadow.resize(WIDTH + 1);
            JsonObject resized = manifest();
            for (String semanticName : semanticNames) {
                assertEquals(2L, lifecycleCount(resized, "ALLOCATE", semanticName), semanticName);
                assertEquals(1L, lifecycleCount(resized, "INVALIDATE", semanticName), semanticName);
            }

            shadow.close();
            targets.close();
            JsonObject closed = manifest();
            for (String semanticName : semanticNames) {
                assertEquals(2L, lifecycleCount(closed, "ALLOCATE", semanticName), semanticName);
                assertEquals(2L, lifecycleCount(closed, "INVALIDATE", semanticName), semanticName);
            }
        } finally {
            shadow.close();
            targets.close();
        }
    }

    @Test
    void retainedTextureViewDefersInvalidationUntilLastClose() throws Exception {
        MetalGpuTexture texture = (MetalGpuTexture) device.createTexture(
                "eager-retained-view", TEXTURE_USAGE, GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 1
        );
        texture.registerValidationIdentity();
        MetalGpuTextureView retainedView = new MetalGpuTextureView(texture, 0, 1);
        try {
            texture.close();
            assertEquals(0L, lifecycleCount(manifest(), "INVALIDATE", "eager-retained-view"));

            retainedView.close();
            assertEquals(1L, lifecycleCount(manifest(), "INVALIDATE", "eager-retained-view"));
        } finally {
            retainedView.close();
            texture.close();
        }
    }

    @Test
    void disabledTracingDoesNotRegisterTextureAllocations() throws Exception {
        // Keep the recorder alive, then disable the contract switch. This
        // catches helpers that only test for a stale/closed recorder state.
        System.setProperty("metallum.renderContract.enabled", "false");
        MetalGpuTexture texture = (MetalGpuTexture) device.createTexture(
                "eager-disabled", TEXTURE_USAGE, GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 1
        );
        try {
            texture.registerValidationIdentity();
            assertTrue(RenderContractRuntime.enabled(), "the recorder must remain live for this disabled-switch test");
            assertEquals(0L, lifecycleCount(manifest(), "ALLOCATE", "eager-disabled"));
        } finally {
            texture.close();
        }
    }

    @Test
    void eagerRegistrationUsesBaseMipAndLaterMipIdentityWithoutCollision() throws Exception {
        MetalGpuTexture texture = (MetalGpuTexture) device.createTexture(
                "eager-mip-test", TEXTURE_USAGE, GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 2
        );
        texture.registerValidationIdentity();
        MetalGpuTextureView baseView = new MetalGpuTextureView(texture, 0, 1);
        MetalGpuTextureView mipView = new MetalGpuTextureView(texture, 1, 1);
        try {
            JsonObject base = manifest();
            assertEquals(1L, lifecycleCount(base, "ALLOCATE", "eager-mip-test", 0));
            assertEquals(0L, lifecycleCount(base, "ALLOCATE", "eager-mip-test", 1));

            MetalCommandEncoder.contractResource(texture, 1);
            JsonObject laterMip = manifest();
            assertEquals(1L, lifecycleCount(laterMip, "ALLOCATE", "eager-mip-test", 0));
            assertEquals(1L, lifecycleCount(laterMip, "ALLOCATE", "eager-mip-test", 1));
        } finally {
            texture.close();
            baseView.close();
            mipView.close();
        }
        JsonObject closed = manifest();
        assertEquals(1L, lifecycleCount(closed, "INVALIDATE", "eager-mip-test", 0));
        assertEquals(1L, lifecycleCount(closed, "INVALIDATE", "eager-mip-test", 1));
    }

    private JsonObject manifest() throws Exception {
        RenderContractRuntime.flushManifest();
        return JsonParser.parseString(
                Files.readString(output.resolve("render-contract/pass-manifest.json"))
        ).getAsJsonObject();
    }

    private static long lifecycleCount(
            final JsonObject manifest,
            final String action,
            final String semanticName
    ) {
        return lifecycleCount(manifest, action, semanticName, -1);
    }

    private static long lifecycleCount(
            final JsonObject manifest,
            final String action,
            final String semanticName,
            final int mipLevel
    ) {
        return StreamSupport.stream(manifest.getAsJsonArray("resourceLifecycle").spliterator(), false)
                .map(element -> element.getAsJsonObject())
                .filter(event -> action.equals(event.get("action").getAsString()))
                .map(event -> event.getAsJsonObject("resource"))
                .filter(resource -> semanticName.equals(resource.get("semanticName").getAsString()))
                .filter(resource -> mipLevel < 0 || mipLevel == resource.get("mipLevel").getAsInt())
                .count();
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
