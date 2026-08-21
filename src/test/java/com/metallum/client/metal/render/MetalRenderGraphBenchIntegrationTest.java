package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IrisGraphBench: synthetic render-graph oracle for the TBDR attachment
 * compiler (P2). Every scenario runs through the SHIPPING Java render-pass
 * path — no second test renderer — and pins pixel-exact expectations that any
 * load/store/fusion decision must preserve:
 *
 * 1. mrtFullSlots            four live color attachments
 * 2. mrtNullMiddleSlot       holes keep fragment location identity
 * 3. pingPongChain           A stored by P1 is sampled by P3 (non-adjacent)
 * 4. partialViewportScissor  a scissored draw must NOT prove full overwrite
 * 5. blendOverPrevious       blending requires LOAD of prior content
 * 6. computeRawThenSample    compute imageStore ordered before render sample
 * 7. historyTwoPassesApart   store between two distant consumers is required
 * 8. deadAttachmentOverwrite written-never-read then fully overwritten
 *
 * When the system property metallum.rendergraph.output is set, the suite
 * writes structured per-scenario telemetry to that file for baseline/candidate
 * comparison (rendergraph.json).
 */
@EnabledOnOs(OS.MAC)
final class MetalRenderGraphBenchIntegrationTest {
    private static final int WIDTH = 64;
    private static final int HEIGHT = 64;
    private static final int TEXTURE_USAGE =
            GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC;

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

    private static final String SAMPLE_FRAGMENT = """
            #version 450
            uniform sampler2D SourceSampler;
            layout(location=0) out vec4 color;
            void main() {
                color = texelFetch(SourceSampler, ivec2(gl_FragCoord.xy), 0);
            }
            """;

    private static final Map<String, Map<String, Object>> SCENARIOS = new LinkedHashMap<>();

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
                "Metal render-graph bench device",
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
        vertexShaders.clear();
        fragmentShaders.clear();
    }

    @AfterAll
    static void writeRenderGraphEvidence() {
        String output = System.getProperty("metallum.rendergraph.output");
        if (output == null || output.isEmpty()) {
            return;
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("source", "MetalRenderGraphBenchIntegrationTest");
        document.put("abi", "V2-baseline");
        document.put("scenarios", SCENARIOS);
        long passes = 0;
        long encoders = 0;
        long store = 0;
        long load = 0;
        for (Map<String, Object> scenario : SCENARIOS.values()) {
            passes += ((Number) scenario.get("passesRequested")).longValue();
            encoders += ((Number) scenario.get("nativeEncodersCreated")).longValue();
            store += ((Number) scenario.get("colorStoreBytesEstimate")).longValue();
            load += ((Number) scenario.get("colorLoadBytesEstimate")).longValue();
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("passesRequested", passes);
        totals.put("nativeEncodersCreated", encoders);
        totals.put("colorStoreBytesEstimate", store);
        totals.put("colorLoadBytesEstimate", load);
        document.put("totals", totals);
        try {
            java.nio.file.Path path = java.nio.file.Path.of(output);
            if (path.getParent() != null) {
                java.nio.file.Files.createDirectories(path.getParent());
            }
            byte[] json = toJson(document).getBytes(StandardCharsets.UTF_8);
            java.nio.file.Files.write(path, json);
        } catch (Exception failure) {
            throw new IllegalStateException("failed to write rendergraph evidence: " + output, failure);
        }
    }

    // ------------------------------------------------------------------
    // Scenarios
    // ------------------------------------------------------------------

    @Test
    void mrtFullSlots() {
        withScenario("mrtFullSlots", () -> {
            List<MetalGpuTexture> textures = createTextures(
                    List.of(GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM),
                    "bench-mrt-full");
            try {
                RenderPipeline pipeline = solidPipeline("mrt_full", 4,
                        new float[] { 1.0F, 0.0F, 0.0F, 1.0F },
                        new float[] { 0.0F, 1.0F, 0.0F, 1.0F },
                        new float[] { 0.0F, 0.0F, 1.0F, 1.0F },
                        new float[] { 1.0F, 1.0F, 0.0F, 1.0F });
                RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "bench mrt full");
                for (int index = 0; index < 4; index++) {
                    descriptor.withColorAttachment(view(textures.get(index)), Optional.of(new Vector4f(0.0F)));
                }
                descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, WIDTH, HEIGHT));
                MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor);
                pass.setPipeline(pipeline);
                pass.draw(3, 1, 0, 0);
                encoder.submitRenderPass();
                encoder.submit();
                device.waitForSubmittedGpuWork();

                assertRgba(readback(textures.get(0)), 255, 0, 0, "slot0 red");
                assertRgba(readback(textures.get(1)), 0, 255, 0, "slot1 green");
                assertRgba(readback(textures.get(2)), 0, 0, 255, "slot2 blue");
                assertRgba(readback(textures.get(3)), 255, 255, 0, "slot3 yellow");
            } finally {
                closeAll(textures);
            }
        });
    }

    @Test
    void mrtNullMiddleSlot() {
        withScenario("mrtNullMiddleSlot", () -> {
            List<MetalGpuTexture> textures = createTextures(
                    List.of(GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM),
                    "bench-mrt-null");
            try {
                // Locations 0 and 2 with an unused slot 1: the pipeline must
                // mark slot 1 unused to match the pass signature fail-closed.
                fragmentShaders.put("mrt_null", """
                        #version 450
                        layout(location=0) out vec4 out0;
                        layout(location=2) out vec4 out2;
                        void main() {
                            out0 = vec4(1.0, 0.0, 0.0, 1.0);
                            out2 = vec4(0.0, 0.0, 1.0, 1.0);
                        }
                        """);
                RenderPipeline pipeline = RenderPipeline.builder()
                        .withLocation("metallum_test/mrt_null")
                        .withVertexShader("metallum_test/fullscreen")
                        .withFragmentShader("metallum_test/mrt_null")
                        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                        .withCull(false)
                        .withColorTargetState(0, new ColorTargetState(
                                Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                        .withUnusedColorTargetState(1)
                        .withColorTargetState(2, new ColorTargetState(
                                Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                        .build();
                RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "bench mrt null middle");
                descriptor.withColorAttachment(view(textures.get(0)), Optional.of(new Vector4f(0.0F)));
                descriptor.withUnusedColorAttachment();
                descriptor.withColorAttachment(view(textures.get(1)), Optional.of(new Vector4f(0.0F)));
                descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, WIDTH, HEIGHT));
                MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor);
                pass.setPipeline(pipeline);
                pass.draw(3, 1, 0, 0);
                encoder.submitRenderPass();
                encoder.submit();
                device.waitForSubmittedGpuWork();

                assertRgba(readback(textures.get(0)), 255, 0, 0, "location0 red survives null slot");
                assertRgba(readback(textures.get(1)), 0, 0, 255, "location2 blue survives null slot");
            } finally {
                closeAll(textures);
            }
        });
    }

    @Test
    void pingPongChain() {
        withScenario("pingPongChain", () -> {
            List<MetalGpuTexture> textures = createTextures(
                    List.of(GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM),
                    "bench-pingpong");
            MetalGpuTexture a = textures.get(0);
            MetalGpuTexture b = textures.get(1);
            try {
                RenderPipeline samplePipeline = samplePipeline("pingpong_sample");
                GpuSampler nearest = nearestSampler();

                // P1: A <- red
                fullscreenSolid("pingpong_red", a, new Vector4f(1.0F, 0.0F, 0.0F, 1.0F));
                // P2: B <- sample(A) => B becomes red
                sampleInto("pingpong_p2", samplePipeline, nearest, b, a);
                // P3: A <- sample(B) => A becomes red again; requires A's P1
                // store to survive and a LOAD of A's previous content.
                sampleInto("pingpong_p3", samplePipeline, nearest, a, b);

                assertRgba(readback(a), 255, 0, 0, "ping-pong final A carries sampled history");
                assertRgba(readback(b), 255, 0, 0, "ping-pong B carries sampled copy");
            } finally {
                closeAll(textures);
            }
        });
    }

    @Test
    void partialViewportScissor() {
        withScenario("partialViewportScissor", () -> {
            List<MetalGpuTexture> textures = createTextures(List.of(GpuFormat.RGBA8_UNORM), "bench-scissor");
            MetalGpuTexture texture = textures.get(0);
            try {
                RenderPipeline redPipeline = solidPipeline("scissor_red", 1,
                        new float[] { 1.0F, 0.0F, 0.0F, 1.0F }, null, null, null);

                // P1: clear to white only.
                RenderPassDescriptor clearDescriptor = RenderPassDescriptor.create(() -> "bench scissor clear");
                clearDescriptor.withColorAttachment(view(texture), Optional.of(new Vector4f(1.0F, 1.0F, 1.0F, 1.0F)));
                clearDescriptor.withRenderArea(new RenderPass.RenderArea(0, 0, WIDTH, HEIGHT));
                MetalRenderPass clearPass = (MetalRenderPass) encoder.createRenderPass(clearDescriptor);
                encoder.submitRenderPass();

                // P2: LOAD + scissored quadrant draw. The quadrant draw does
                // NOT prove full overwrite, so the load of the other pixels'
                // content is semantically mandatory.
                RenderPassDescriptor drawDescriptor = RenderPassDescriptor.create(() -> "bench scissor draw");
                drawDescriptor.withColorAttachment(view(texture), Optional.empty());
                drawDescriptor.withRenderArea(new RenderPass.RenderArea(0, 0, WIDTH, HEIGHT));
                MetalRenderPass drawPass = (MetalRenderPass) encoder.createRenderPass(drawDescriptor);
                drawPass.setPipeline(redPipeline);
                drawPass.enableScissor(0, 0, WIDTH / 2, HEIGHT / 2);
                drawPass.draw(3, 1, 0, 0);
                drawPass.disableScissor();
                encoder.submitRenderPass();
                encoder.submit();
                device.waitForSubmittedGpuWork();

                ByteBuffer pixels = readback(texture);
                assertPixel(pixels, WIDTH / 4, HEIGHT / 4, 255, 0, 0, "scissored quadrant drawn red");
                assertPixel(pixels, WIDTH - 2, HEIGHT - 2, 255, 255, 255, "outside quadrant keeps loaded white");
            } finally {
                closeAll(textures);
            }
        });
    }

    @Test
    void blendOverPreviousContent() {
        withScenario("blendOverPreviousContent", () -> {
            List<MetalGpuTexture> textures = createTextures(List.of(GpuFormat.RGBA8_UNORM), "bench-blend");
            MetalGpuTexture texture = textures.get(0);
            try {
                RenderPipeline blendPipeline = RenderPipeline.builder()
                        .withLocation("metallum_test/blend_over")
                        .withVertexShader("metallum_test/fullscreen")
                        .withFragmentShader("metallum_test/blend_over")
                        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                        .withCull(false)
                        .withColorTargetState(0, new ColorTargetState(
                                Optional.of(BlendFunction.TRANSLUCENT), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                        .build();
                fragmentShaders.put("blend_over", """
                        #version 450
                        layout(location=0) out vec4 color;
                        void main() { color = vec4(1.0, 0.0, 0.0, 0.5); }
                        """);

                // P1: opaque gray base.
                RenderPassDescriptor baseDescriptor = RenderPassDescriptor.create(() -> "bench blend base");
                baseDescriptor.withColorAttachment(view(texture), Optional.of(new Vector4f(0.5F, 0.5F, 0.5F, 1.0F)));
                baseDescriptor.withRenderArea(new RenderPass.RenderArea(0, 0, WIDTH, HEIGHT));
                MetalRenderPass basePass = (MetalRenderPass) encoder.createRenderPass(baseDescriptor);
                encoder.submitRenderPass();

                // P2: translucent red over gray. Blending reads the stored
                // destination, so this pass's LOAD is semantically required.
                RenderPassDescriptor blendDescriptor = RenderPassDescriptor.create(() -> "bench blend over");
                blendDescriptor.withColorAttachment(view(texture), Optional.empty());
                blendDescriptor.withRenderArea(new RenderPass.RenderArea(0, 0, WIDTH, HEIGHT));
                MetalRenderPass blendPass = (MetalRenderPass) encoder.createRenderPass(blendDescriptor);
                blendPass.setPipeline(blendPipeline);
                blendPass.draw(3, 1, 0, 0);
                encoder.submitRenderPass();
                encoder.submit();
                device.waitForSubmittedGpuWork();

                ByteBuffer pixels = readback(texture);
                assertByteNear(pixels.get(0), 192, "blended red over gray");
                assertByteNear(pixels.get(1), 64, "blended green channel");
                assertByteNear(pixels.get(2), 64, "blended blue channel");
            } finally {
                closeAll(textures);
            }
        });
    }

    @Test
    void computeRawThenSample() {
        withScenario("computeRawThenSample", () -> {
            List<MetalGpuTexture> textures = createTextures(List.of(GpuFormat.RGBA8_UNORM), "bench-compute-raw");
            MetalGpuTexture target = textures.get(0);
            String glsl = """
                    #version 450
                    layout(local_size_x = 8, local_size_y = 8) in;
                    layout(binding = 0, rgba8) writeonly uniform image2D dst;
                    void main() {
                        ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                        float checker = ((p.x / 4 + p.y / 4) % 2 == 0) ? 1.0 : 0.0;
                        imageStore(dst, p, vec4(checker, 1.0 - checker, 0.25, 1.0));
                    }
                    """;
            try (MetalGpuTexture storage = (MetalGpuTexture) device.createTexture(
                    "bench-compute-raw-storage",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC | MetalGpuTexture.USAGE_SHADER_WRITE,
                    GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 1);
                 MetalComputePipeline pipeline = MetalComputePipeline.compileGlsl(device, "bench_raw_store", glsl)) {
                try (MetalComputePass computePass = encoder.createComputePass()) {
                    computePass.setPipeline(pipeline)
                            .bindTexture(0, storage)
                            .dispatchThreadsCovering(WIDTH, HEIGHT, 1);
                }
                RenderPipeline samplePipeline = samplePipeline("raw_sample");
                sampleInto("bench_raw_render", samplePipeline, nearestSampler(), target, storage);
                encoder.submit();
                device.waitForSubmittedGpuWork();

                ByteBuffer pixels = readback(target);
                assertPixel(pixels, 0, 0, 255, 0, 64, "checker even cell from compute RAW chain");
                assertPixel(pixels, 4, 2, 0, 255, 64, "checker odd cell from compute RAW chain");
            } finally {
                closeAll(textures);
            }
        });
    }

    @Test
    void historyTwoPassesApart() {
        withScenario("historyTwoPassesApart", () -> {
            List<MetalGpuTexture> textures = createTextures(
                    List.of(GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM),
                    "bench-history");
            MetalGpuTexture a = textures.get(0);
            MetalGpuTexture b = textures.get(1);
            MetalGpuTexture c = textures.get(2);
            try {
                RenderPipeline samplePipeline = samplePipeline("history_sample");
                GpuSampler nearest = nearestSampler();

                fullscreenSolid("history_p1", a, new Vector4f(1.0F, 0.0F, 0.0F, 1.0F));
                fullscreenSolid("history_p2", b, new Vector4f(0.0F, 0.0F, 1.0F, 1.0F));
                sampleInto("history_p3", samplePipeline, nearest, c, a);

                assertRgba(readback(c), 255, 0, 0, "two-pass-apart history consumer sees P1 output");
            } finally {
                closeAll(textures);
            }
        });
    }

    @Test
    void deadAttachmentOverwrite() {
        withScenario("deadAttachmentOverwrite", () -> {
            List<MetalGpuTexture> textures = createTextures(List.of(GpuFormat.RGBA8_UNORM), "bench-dead");
            MetalGpuTexture texture = textures.get(0);
            try {
                // P1 writes magenta; nothing ever samples it.
                fullscreenSolid("dead_p1", texture, new Vector4f(1.0F, 0.0F, 1.0F, 1.0F));
                // P2 fully clears and redraws cyan: P1's store was provably
                // dead bandwidth under V2 semantics.
                RenderPipeline cyanPipeline = solidPipeline("dead_cyan", 1,
                        new float[] { 0.0F, 1.0F, 1.0F, 1.0F }, null, null, null);
                RenderPassDescriptor overwrite = RenderPassDescriptor.create(() -> "bench dead overwrite");
                overwrite.withColorAttachment(view(texture), Optional.of(new Vector4f(0.0F, 1.0F, 1.0F, 1.0F)));
                overwrite.withRenderArea(new RenderPass.RenderArea(0, 0, WIDTH, HEIGHT));
                MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(overwrite);
                pass.setPipeline(cyanPipeline);
                pass.draw(3, 1, 0, 0);
                encoder.submitRenderPass();
                encoder.submit();
                device.waitForSubmittedGpuWork();

                assertRgba(readback(texture), 0, 255, 255, "full overwrite replaces dead content");
            } finally {
                closeAll(textures);
            }
        });
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private interface ScenarioBody {
        void run() throws Exception;
    }

    private static void withScenario(final String name, final ScenarioBody body) {
        RenderGraphTelemetry.reset();
        try {
            body.run();
        } catch (Exception failure) {
            throw new RuntimeException("scenario failed: " + name, failure);
        }
        SCENARIOS.put(name, RenderGraphTelemetry.snapshot());
    }

    private MetalGpuTextureView view(final MetalGpuTexture texture) {
        return new MetalGpuTextureView(texture, 0, 1);
    }

    private List<MetalGpuTexture> createTextures(final List<GpuFormat> formats, final String label) {
        java.util.List<MetalGpuTexture> textures = new java.util.ArrayList<>(formats.size());
        for (int index = 0; index < formats.size(); index++) {
            textures.add((MetalGpuTexture) device.createTexture(
                    label + "-" + index, TEXTURE_USAGE, formats.get(index), WIDTH, HEIGHT, 1, 1));
        }
        return textures;
    }

    private static void closeAll(final List<MetalGpuTexture> textures) {
        for (MetalGpuTexture texture : textures) {
            if (texture != null) {
                texture.close();
            }
        }
    }

    private RenderPipeline solidPipeline(
            final String name,
            final int slots,
            final float[] slot0,
            final float[] slot1,
            final float[] slot2,
            final float[] slot3
    ) {
        StringBuilder fragment = new StringBuilder("""
                #version 450
                layout(location=0) out vec4 out0;
                """);
        if (slots > 1) {
            fragment.append("layout(location=1) out vec4 out1;\n");
        }
        if (slots > 2) {
            fragment.append("layout(location=2) out vec4 out2;\n");
        }
        if (slots > 3) {
            fragment.append("layout(location=3) out vec4 out3;\n");
        }
        fragment.append("void main() {\n");
        appendColor(fragment, "out0", slot0);
        if (slots > 1) {
            appendColor(fragment, "out1", slot1);
        }
        if (slots > 2) {
            appendColor(fragment, "out2", slot2);
        }
        if (slots > 3) {
            appendColor(fragment, "out3", slot3);
        }
        fragment.append("}\n");
        fragmentShaders.put(name, fragment.toString());

        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation("metallum_test/" + name)
                .withVertexShader("metallum_test/fullscreen")
                .withFragmentShader("metallum_test/" + name)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false);
        for (int index = 0; index < slots; index++) {
            builder.withColorTargetState(index, new ColorTargetState(
                    Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL));
        }
        return builder.build();
    }

    private static void appendColor(final StringBuilder builder, final String out, final float[] rgba) {
        if (rgba == null) {
            builder.append(out).append(" = vec4(0.0);\n");
            return;
        }
        builder.append(out).append(" = vec4(")
                .append(rgba[0]).append(", ").append(rgba[1])
                .append(", ").append(rgba[2]).append(", ").append(rgba[3]).append(");\n");
    }

    private RenderPipeline samplePipeline(final String name) {
        fragmentShaders.put(name, SAMPLE_FRAGMENT);
        BindGroupLayout layout = BindGroupLayout.builder()
                .withSampler("SourceSampler")
                .build();
        return RenderPipeline.builder()
                .withLocation("metallum_test/" + name)
                .withVertexShader("metallum_test/fullscreen")
                .withFragmentShader("metallum_test/" + name)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withBindGroupLayout(layout)
                .withColorTargetState(0, new ColorTargetState(
                        Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                .build();
    }

    private GpuSampler nearestSampler() {
        return device.createSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST,
                FilterMode.NEAREST,
                1,
                java.util.OptionalDouble.empty()
        );
    }

    /** One full-screen clear+solid-draw pass into {@code target}. */
    private void fullscreenSolid(final String shaderName, final MetalGpuTexture target, final Vector4f clear) {
        RenderPipeline pipeline = solidPipeline(shaderName, 1,
                new float[] { clear.x(), clear.y(), clear.z(), clear.w() }, null, null, null);
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "bench " + shaderName);
        descriptor.withColorAttachment(view(target), Optional.of(clear));
        descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, WIDTH, HEIGHT));
        MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor);
        pass.setPipeline(pipeline);
        pass.draw(3, 1, 0, 0);
        encoder.submitRenderPass();
    }

    /** One full-screen pass sampling {@code source} into {@code target} (load, no clear). */
    private void sampleInto(
            final String label,
            final RenderPipeline pipeline,
            final GpuSampler sampler,
            final MetalGpuTexture target,
            final MetalGpuTexture source
    ) {
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "bench " + label);
        descriptor.withColorAttachment(view(target), Optional.empty());
        descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, WIDTH, HEIGHT));
        MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor);
        pass.setPipeline(pipeline);
        pass.bindTexture("SourceSampler", view(source), sampler);
        pass.draw(3, 1, 0, 0);
        encoder.submitRenderPass();
    }

    private ByteBuffer readback(final MetalGpuTexture texture) {
        int size = WIDTH * HEIGHT * texture.pixelSize();
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "render-graph bench readback",
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

    private static void assertRgba(final ByteBuffer pixels, final int r, final int g, final int b, final String message) {
        assertByteNear(pixels.get(0), r, message + " (r)");
        assertByteNear(pixels.get(1), g, message + " (g)");
        assertByteNear(pixels.get(2), b, message + " (b)");
    }

    private static void assertPixel(
            final ByteBuffer pixels,
            final int x,
            final int y,
            final int r,
            final int g,
            final int b,
            final String message
    ) {
        int offset = (y * WIDTH + x) * 4;
        assertByteNear(pixels.get(offset), r, message + " (r)");
        assertByteNear(pixels.get(offset + 1), g, message + " (g)");
        assertByteNear(pixels.get(offset + 2), b, message + " (b)");
    }

    private static void assertByteNear(final int actualSigned, final int expected, final String message) {
        int actual = actualSigned & 0xFF;
        assertTrue(Math.abs(actual - expected) <= 2, message + ": expected ~" + expected + " got " + actual);
    }

    /** Minimal deterministic JSON writer (no external dependency in tests). */
    private static String toJson(final Map<String, Object> document) {
        StringBuilder builder = new StringBuilder();
        writeMap(builder, document);
        return builder.toString();
    }

    private static void writeMap(final StringBuilder builder, final Map<?, ?> map) {
        builder.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append('"').append(String.valueOf(entry.getKey())).append("\":");
            writeValue(builder, entry.getValue());
        }
        builder.append('}');
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(final StringBuilder builder, final Object value) {
        switch (value) {
            case null -> builder.append("null");
            case Number number -> builder.append(number);
            case Boolean bool -> builder.append(bool);
            case Map<?, ?> map -> writeMap(builder, map);
            case List<?> list -> {
                builder.append('[');
                boolean first = true;
                for (Object item : list) {
                    if (!first) {
                        builder.append(',');
                    }
                    first = false;
                    writeValue(builder, item);
                }
                builder.append(']');
            }
            default -> {
                builder.append('"');
                for (char c : String.valueOf(value).toCharArray()) {
                    if (c == '"' || c == '\\') {
                        builder.append('\\');
                    }
                    builder.append(c);
                }
                builder.append('"');
            }
        }
    }
}
