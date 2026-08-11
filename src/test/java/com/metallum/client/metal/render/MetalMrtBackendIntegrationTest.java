package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.bridge.MetalTerrainIcbBridge;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.metallum.client.metal.render.mtl.MetalCommandPacketTelemetry;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
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
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * macOS-only backend integration test. Unlike MetalMRTSmokeTest.swift, this
 * starts at Mojang's Java RenderPassDescriptor and crosses the production
 * MetalCommandEncoder, pipeline metadata, FFM arrays and indexed Swift ABI.
 */
@EnabledOnOs(OS.MAC)
final class MetalMrtBackendIntegrationTest {
    private static final int WIDTH = 256;
    private static final int HEIGHT = 4;
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
                    ? vertexShaders.getOrDefault(name, VERTEX_SHADER)
                    : fragmentShaders.get(name);
        };
        device = new MetalDevice(
                source,
                new GpuDebugOptions(2, true, true, true),
                nativeDevice,
                MemorySegment.NULL,
                "Metal MRT integration device",
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
    void oneAndTwoAttachmentReadback() {
        runRgbaAttachmentCount(1);
        runRgbaAttachmentCount(2);
    }

    @Test
    void fourAttachmentReadback() {
        runRgbaAttachmentCount(4);
    }

    @Test
    void initializedIndexBufferSupportsIndexedTriangleFan() {
        String shaderName = "indexed_triangle_fan";
        vertexShaders.put(shaderName, """
                #version 450
                void main() {
                    vec2 positions[4] = vec2[](
                        vec2(-1.0, -1.0),
                        vec2( 1.0, -1.0),
                        vec2( 1.0,  1.0),
                        vec2(-1.0,  1.0)
                    );
                    gl_Position = vec4(positions[gl_VertexIndex], 0.0, 1.0);
                }
                """);
        fragmentShaders.put(shaderName, """
                #version 450
                layout(location=0) out vec4 color;
                void main() { color = vec4(0.0, 1.0, 0.0, 1.0); }
                """);
        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation("metallum_test/" + shaderName)
                .withVertexShader("metallum_test/" + shaderName)
                .withFragmentShader("metallum_test/" + shaderName)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLE_FAN)
                .withCull(false)
                .withColorTargetState(
                        0,
                        new ColorTargetState(
                                Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL
                        )
                )
                .build();
        ByteBuffer indices = ByteBuffer.allocateDirect(4 * Short.BYTES).order(ByteOrder.nativeOrder());
        indices.putShort((short) 0).putShort((short) 1).putShort((short) 2).putShort((short) 3).flip();

        List<MetalGpuTexture> textures = createTextures(List.of(GpuFormat.RGBA8_UNORM), "triangle-fan");
        try (MetalGpuBuffer indexBuffer = (MetalGpuBuffer) device.createBuffer(
                () -> "triangle fan source indices", GpuBuffer.USAGE_INDEX, indices
        ); PassWithViews pass = createPass(textures, null, false)) {
            assertTrue((indexBuffer.usage() & GpuBuffer.USAGE_MAP_WRITE) != 0);
            pass.pass().setPipeline(pipeline);
            pass.pass().setIndexBuffer(indexBuffer, IndexType.SHORT);
            pass.pass().drawIndexed(4, 1, 0, 0, 0);
            encoder.submitRenderPass();
            encoder.submit();
            device.waitForSubmittedGpuWork();

            ByteBuffer rendered = readback(textures.get(0));
            assertByteNear(rendered.get(0), 0, "triangle fan red");
            assertByteNear(rendered.get(1), 255, "triangle fan green");
            assertByteNear(rendered.get(2), 0, "triangle fan blue");
        }
        closeTextures(textures);
    }

    @Test
    void terrainIcbExecutesTextureArgumentBufferResources() {
        assertTrue(MetalTerrainIcbScope.enabled(), "production terrain ICB gate is disabled");
        assertEquals(1, MetalTerrainIcbBridge.submissionBudget());
        assertTrue(IrisMetalArgumentBindingRuntime.enabled(), "argument buffers are disabled");
        assertTrue(
                device.getDeviceInfo().features().multiDrawDirectInterleaved(),
                "public RenderPass must advertise the interleaved command layout used by Sodium"
        );
        assertEquals(
                MetalDevice.MAX_MULTI_DRAW_DIRECT_INTERLEAVED_DRAWS,
                device.getDeviceInfo().limits().maxMultiDrawDirectInterleavedDrawCount()
        );

        String shaderName = "terrain_icb_argument_buffer";
        fragmentShaders.put(shaderName, """
                #version 450
                uniform sampler2D SourceSampler;
                layout(location=0) out vec4 color;
                void main() {
                    color = texture(SourceSampler, vec2(0.5));
                }
                """);
        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation("metallum_test/" + shaderName)
                .withVertexShader("metallum_test/" + shaderName)
                .withFragmentShader("metallum_test/" + shaderName)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withBindGroupLayout(BindGroupLayout.builder().withSampler("SourceSampler").build())
                .withColorTargetState(0, new ColorTargetState(
                        Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL
                ))
                .build();

        ByteBuffer sourcePixels = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
        sourcePixels.put((byte) 51).put((byte) 128).put((byte) 230).put((byte) 255).flip();
        ByteBuffer indexBytes = ByteBuffer.allocateDirect(3 * Short.BYTES).order(ByteOrder.nativeOrder());
        indexBytes.putShort((short) 0).putShort((short) 1).putShort((short) 2).flip();
        IntBuffer draws = ByteBuffer.allocateDirect(16 * 3 * Integer.BYTES)
                .order(ByteOrder.nativeOrder())
                .asIntBuffer();
        for (int draw = 0; draw < 16; draw++) {
            draws.put(draw * 3, 0);
            draws.put(draw * 3 + 1, 3);
            draws.put(draw * 3 + 2, 0);
        }

        List<MetalGpuTexture> outputs = createTextures(List.of(GpuFormat.RGBA8_UNORM), "terrain-icb");
        try (MetalGpuTexture source = (MetalGpuTexture) device.createTexture(
                "terrain ICB sampled source",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM,
                1,
                1,
                1,
                1
        ); MetalGpuTextureView sourceView = (MetalGpuTextureView) device.createTextureView(source);
             MetalGpuSampler sampler = (MetalGpuSampler) device.createSampler(
                     AddressMode.CLAMP_TO_EDGE,
                     AddressMode.CLAMP_TO_EDGE,
                     FilterMode.NEAREST,
                     FilterMode.NEAREST,
                     1,
                     java.util.OptionalDouble.empty()
             ); MetalGpuBuffer indices = (MetalGpuBuffer) device.createBuffer(
                     () -> "terrain ICB indices", GpuBuffer.USAGE_INDEX, indexBytes
             )) {
            encoder.writeToTexture(source, sourcePixels, 0, 0, 0, 0, 1, 1);
            IrisMetalArgumentBindingRuntime.resetStats();
            MetalCommandPacketTelemetry.reset();

            // Four completed submissions prove that the bounded Metal 4 path
            // executes a real ICB, releases it on feedback, and never reuses an
            // executed ICB across an MTL4 command-buffer lifecycle.
            for (int submission = 0; submission < 4; submission++) {
                try (PassWithViews pass = createPass(outputs, null, false)) {
                    pass.pass().setPipeline(pipeline);
                    pass.pass().bindTexture("SourceSampler", sourceView, sampler);
                    pass.pass().setIndexBuffer(indices, IndexType.SHORT);
                    MetalTerrainIcbScope.enter();
                    try {
                        pass.pass().multiDrawIndexed(draws, 1, 0, 16);
                        if (device.metal4MainRendererEnabled()) {
                            // The second qualifying batch must execute exactly
                            // once through native multi-draw, but Java already
                            // knows the per-submission budget is consumed and
                            // must not cross FFM only to rediscover that fact.
                            pass.pass().multiDrawIndexed(draws, 1, 0, 16);
                        }
                    } finally {
                        MetalTerrainIcbScope.exit();
                    }
                    encoder.submitRenderPass();
                }
                encoder.submit();
                device.waitForSubmittedGpuWork();
            }

            ByteBuffer rendered = readback(outputs.getFirst());
            assertByteNear(rendered.get(0), 51, "terrain ICB sampled red");
            assertByteNear(rendered.get(1), 128, "terrain ICB sampled green");
            assertByteNear(rendered.get(2), 230, "terrain ICB sampled blue");
            assertByteNear(rendered.get(3), 255, "terrain ICB sampled alpha");

            MetalCommandPacketTelemetry.Snapshot commands = MetalCommandPacketTelemetry.snapshot();
            boolean metal4 = device.metal4MainRendererEnabled();
            assertEquals(4L, commands.terrainIcbAttempts());
            assertEquals(4L, commands.terrainIcbAccepted());
            assertEquals(64L, commands.terrainIcbDraws());
            assertEquals(0L, commands.terrainIcbFallbacks());
            assertEquals(metal4 ? 4L : 0L, commands.terrainIcbBudgetSkips());
            assertEquals(metal4 ? 64L : 0L, commands.terrainIcbBudgetSkipDraws());
            IrisMetalArgumentBindingRuntime.Stats arguments = IrisMetalArgumentBindingRuntime.stats();
            assertTrue(arguments.encodedSnapshots() > 0L, "no argument-buffer snapshot executed");
            assertTrue(arguments.updates() >= 2L, "texture/sampler resources were not encoded");
            assertEquals(0L, arguments.failures());
            if (metal4) {
                MetalTerrainIcbBridge.NativeStats icb = MetalTerrainIcbBridge.nativeStats();
                assertTrue(icb.available(), "native ICB lifetime telemetry is unavailable");
                assertTrue(icb.allocations() >= 4L, "terrain ICBs were not allocated");
                assertTrue(
                        icb.completionReleases() >= 4L,
                        "terrain ICBs were not released after GPU completion"
                );
                assertEquals(0L, icb.budgetFallbacks(), "Java did not suppress exhausted-budget FFM calls");
                assertEquals(0L, icb.zeroAllocationFallbacks());
                long[] closure = MetalNativeBridge.metallum_metal4_backend_closure_stats();
                assertEquals(1L, closure[0], "Metal 4 closure telemetry did not engage");
                assertTrue(closure[1] > 0L, "Metal 4 recorded no generic render encoder");
                assertTrue(closure[3] > 0L, "Metal 4 recorded no generic blit encoder");
                assertEquals(0L, closure[4], "Metal 4 escaped through a legacy encoder");
                assertEquals(1L, closure[5], "Metal 4 no-legacy invariant failed");
            }
        }
        closeTextures(outputs);
    }

    @Test
    void argumentBufferPipelineSwitchRestoresLegacyVertexSlotZero() {
        assertTrue(IrisMetalArgumentBindingRuntime.enabled(), "argument buffers are disabled");

        String redName = "legacy_slot_zero_red";
        String argumentName = "argument_slot_zero_green";
        String blueName = "legacy_slot_zero_blue";
        String legacyVertex = """
                #version 450
                layout(location=0) in vec3 Position;
                layout(location=1) in vec2 UV0;
                void main() { gl_Position = vec4(Position, 1.0); }
                """;
        vertexShaders.put(redName, legacyVertex);
        vertexShaders.put(blueName, legacyVertex);
        vertexShaders.put(argumentName, """
                #version 450
                uniform sampler2D SourceSampler;
                void main() {
                    vec2 positions[3] = vec2[](
                        vec2(-1.0, -1.0),
                        vec2( 3.0, -1.0),
                        vec2(-1.0,  3.0)
                    );
                    float keepSamplerActive = texture(SourceSampler, vec2(0.5)).r;
                    gl_Position = vec4(positions[gl_VertexIndex], keepSamplerActive * 0.000001, 1.0);
                }
                """);
        fragmentShaders.put(redName, solidFragment(1.0F, 0.0F, 0.0F));
        fragmentShaders.put(argumentName, solidFragment(0.0F, 1.0F, 0.0F));
        fragmentShaders.put(blueName, solidFragment(0.0F, 0.0F, 1.0F));

        RenderPipeline red = vertexBackedPipeline(redName);
        RenderPipeline argument = RenderPipeline.builder()
                .withLocation("metallum_test/" + argumentName)
                .withVertexShader("metallum_test/" + argumentName)
                .withFragmentShader("metallum_test/" + argumentName)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withBindGroupLayout(BindGroupLayout.builder().withSampler("SourceSampler").build())
                .withColorTargetState(0, new ColorTargetState(
                        Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL
                ))
                .build();
        RenderPipeline blue = vertexBackedPipeline(blueName);

        ByteBuffer sourcePixels = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
        sourcePixels.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255).flip();
        List<MetalGpuTexture> outputs = createTextures(List.of(GpuFormat.RGBA8_UNORM), "slot-zero-order");
        try (MetalGpuBuffer vertices = (MetalGpuBuffer) device.createBuffer(
                () -> "slot-zero legacy vertices",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                fullScreenPositionTexTriangle()
        ); MetalGpuTexture source = (MetalGpuTexture) device.createTexture(
                "slot-zero argument source",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM,
                1,
                1,
                1,
                1
        ); MetalGpuTextureView sourceView = (MetalGpuTextureView) device.createTextureView(source);
             MetalGpuSampler sampler = (MetalGpuSampler) device.createSampler(
                     AddressMode.CLAMP_TO_EDGE,
                     AddressMode.CLAMP_TO_EDGE,
                     FilterMode.NEAREST,
                     FilterMode.NEAREST,
                     1,
                     java.util.OptionalDouble.empty()
             ); PassWithViews pass = createPass(outputs, null, false)) {
            encoder.writeToTexture(source, sourcePixels, 0, 0, 0, 0, 1, 1);
            pass.pass().setVertexBuffer(0, vertices.slice());

            pass.pass().setPipeline(red);
            pass.pass().draw(3, 1, 0, 0);

            pass.pass().setPipeline(argument);
            pass.pass().bindTexture("SourceSampler", sourceView, sampler);
            pass.pass().draw(3, 1, 0, 0);

            // The argument pipeline replaced vertex [[buffer(0)]]. This draw
            // must restore the unchanged Java vertex slice instead of letting
            // the encoder shadow suppress it as a redundant bind.
            pass.pass().setPipeline(blue);
            pass.pass().draw(3, 1, 0, 0);

            encoder.submitRenderPass();
            encoder.submit();
            device.waitForSubmittedGpuWork();

            ByteBuffer rendered = readback(outputs.getFirst());
            assertByteNear(rendered.get(0), 0, "restored slot-zero red");
            assertByteNear(rendered.get(1), 0, "restored slot-zero green");
            assertByteNear(rendered.get(2), 255, "restored slot-zero blue");
            assertByteNear(rendered.get(3), 255, "restored slot-zero alpha");
        }
        closeTextures(outputs);
    }

    @Test
    void nonContiguousDrawBufferMappingPreservesLocations() {
        // Iris "/* DRAWBUFFERS:025 */" semantics: logical outputs land on
        // non-adjacent attachment slots; the unused slots must stay inert.
        String shaderName = "mrt_non_contiguous";
        fragmentShaders.put(shaderName, """
                #version 450
                layout(location=0) out vec4 first;
                layout(location=2) out vec4 second;
                layout(location=5) out vec4 third;
                void main() {
                    first = vec4(0.25, 0.0, 0.0, 1.0);
                    second = vec4(0.0, 0.5, 0.0, 1.0);
                    third = vec4(0.0, 0.0, 0.75, 1.0);
                }
                """);
        List<GpuFormat> formats = new ArrayList<>();
        formats.add(GpuFormat.RGBA8_UNORM);
        formats.add(null);
        formats.add(GpuFormat.RGBA8_UNORM);
        formats.add(null);
        formats.add(null);
        formats.add(GpuFormat.RGBA8_UNORM);
        RenderPipeline pipeline = pipeline(shaderName, formats, null, ColorTargetState.WRITE_ALL);
        List<MetalGpuTexture> textures = createTextures(formats, "non-contiguous");
        render(pipeline, textures, null);
        assertByteNear(readback(textures.get(0)).get(0), 64, "slot 0 red");
        assertByteNear(readback(textures.get(2)).get(1), 128, "slot 2 green");
        assertByteNear(readback(textures.get(5)).get(2), 191, "slot 5 blue");
        closeTextures(textures);
    }

    @Test
    void depthPlusMrtWritesColorAndDepth() {
        String shaderName = "mrt_depth_combo";
        vertexShaders.put("mrt_depth_vertex", """
                #version 450
                void main() {
                    vec2 positions[3] = vec2[](
                        vec2(-1.0, -1.0),
                        vec2( 3.0, -1.0),
                        vec2(-1.0,  3.0)
                    );
                    gl_Position = vec4(positions[gl_VertexIndex], 0.25, 1.0);
                }
                """);
        fragmentShaders.put(shaderName, """
                #version 450
                layout(location=0) out vec4 color;
                layout(location=1) out vec2 motion;
                void main() {
                    color = vec4(0.5, 0.25, 0.75, 1.0);
                    motion = vec2(0.125, -0.5);
                }
                """);
        List<GpuFormat> formats = List.of(GpuFormat.RGBA8_UNORM, GpuFormat.RG16_FLOAT);
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation("metallum_test/" + shaderName)
                .withVertexShader("metallum_test/mrt_depth_vertex")
                .withFragmentShader("metallum_test/" + shaderName)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true));
        for (int index = 0; index < formats.size(); index++) {
            builder.withColorTargetState(index, new ColorTargetState(
                    Optional.empty(), formats.get(index), ColorTargetState.WRITE_ALL));
        }
        RenderPipeline pipeline = builder.build();

        List<MetalGpuTexture> textures = createTextures(formats, "depth-mrt");
        try (MetalGpuTexture depthTexture = (MetalGpuTexture) device.createTexture(
                "depth-mrt-depth",
                com.mojang.blaze3d.textures.GpuTexture.USAGE_RENDER_ATTACHMENT
                        | com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_SRC,
                GpuFormat.D32_FLOAT, WIDTH, HEIGHT, 1, 1)) {
            RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "depth+MRT integration");
            List<MetalGpuTextureView> views = new ArrayList<>();
            for (int index = 0; index < textures.size(); index++) {
                MetalGpuTextureView view = new MetalGpuTextureView(textures.get(index), 0, 1);
                views.add(view);
                descriptor.withColorAttachment(view, Optional.of(new Vector4f(0.0F, 0.0F, 0.0F, 1.0F)));
            }
            MetalGpuTextureView depthView = new MetalGpuTextureView(depthTexture, 0, 1);
            views.add(depthView);
            descriptor.withDepthAttachment(depthView, java.util.OptionalDouble.of(0.75));
            descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, WIDTH, HEIGHT));
            MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor);
            pass.setPipeline(pipeline);
            pass.draw(3, 1, 0, 0);
            encoder.submitRenderPass();
            encoder.submit();
            device.waitForSubmittedGpuWork();

            assertByteNear(readback(textures.get(0)).get(0), 128, "depth+MRT color red");
            ByteBuffer motion = readback(textures.get(1)).order(ByteOrder.nativeOrder());
            assertEquals(0.125F, Float.float16ToFloat(motion.getShort(0)), 0.01F);
            ByteBuffer depthData = readback(depthTexture).order(ByteOrder.nativeOrder());
            assertEquals(0.25F, depthData.getFloat(0), 0.001F, "depth attachment must hold the written z");
            for (MetalGpuTextureView view : views) {
                view.close();
            }
        }
        closeTextures(textures);
    }

    @Test
    void resizeRecreatePathProducesFreshContent() {
        // Framebuffer-resize semantics: after destroying targets and creating
        // differently-sized replacements, rendering must land in the new
        // textures with the new extent and never reuse stale storage.
        String shaderName = "mrt_resize";
        fragmentShaders.put(shaderName, """
                #version 450
                layout(location=0) out vec4 color;
                void main() { color = vec4(0.25, 0.5, 0.75, 1.0); }
                """);
        List<GpuFormat> formats = List.of(GpuFormat.RGBA8_UNORM);
        RenderPipeline pipeline = pipeline(shaderName, formats, null, ColorTargetState.WRITE_ALL);
        List<MetalGpuTexture> original = createTextures(formats, "resize-before");
        render(pipeline, original, null);
        assertByteNear(readback(original.get(0)).get(0), 64, "pre-resize content");
        closeTextures(original);

        int resizedWidth = WIDTH / 2;
        int resizedHeight = HEIGHT * 2;
        try (MetalGpuTexture resized = (MetalGpuTexture) device.createTexture(
                "resize-after-0", TEXTURE_USAGE, GpuFormat.RGBA8_UNORM, resizedWidth, resizedHeight, 1, 1)) {
            RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "resize integration");
            try (MetalGpuTextureView view = new MetalGpuTextureView(resized, 0, 1)) {
                descriptor.withColorAttachment(view, Optional.of(new Vector4f(0.0F)));
                descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, resizedWidth, resizedHeight));
                MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor);
                pass.setPipeline(pipeline);
                pass.draw(3, 1, 0, 0);
                encoder.submitRenderPass();
                encoder.submit();
                device.waitForSubmittedGpuWork();
            }
            int size = resizedWidth * resizedHeight * resized.pixelSize();
            try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                    () -> "resize readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, size)) {
                encoder.copyTextureToBuffer(resized, buffer, 0L, () -> {
                }, 0);
                encoder.submit();
                device.waitForSubmittedGpuWork();
                ByteBuffer data = buffer.currentStorage().limit(size).slice().order(ByteOrder.nativeOrder());
                assertByteNear(data.get(0), 64, "post-resize first pixel red");
                assertByteNear(data.get(size - 4), 64, "post-resize last pixel red");
            }
        }
    }

    @Test
    void mixedThreeAttachmentReadback() {
        runMixedThreeAttachments();
    }

    @Test
    void nullMiddleSlotPreservesFragmentLocation() {
        runNullMiddleSlot();
    }

    @Test
    void eightAttachmentSignatureAndReadback() {
        runEightAttachmentSignature();
    }

    @Test
    void perSlotClearLoadStoreBlendAndWriteMask() {
        runPerSlotClearLoadStoreBlendAndWriteMask();
    }

    @Test
    void legacySingleAttachmentAbiStillWorks() {
        verifyLegacySingleAttachmentAbi();
    }

    @Test
    void pipelineRenderPassSignatureMismatchFailsClosed() {
        verifyPipelineRenderPassSignatureMismatch();
    }

    @Test
    void fragmentOutputLocationMismatchFailsClosed() {
        verifyFragmentOutputLocationMismatchFailsClosed();
    }

    @Test
    void configuredTargetWithoutFragmentOutputRemainsClear() {
        String shaderName = "mrt_unwritten_configured_target";
        fragmentShaders.put(shaderName, """
                #version 450
                layout(location=0) out vec4 color;
                void main() {
                    color = vec4(0.25, 0.5, 0.75, 1.0);
                }
                """);
        List<GpuFormat> formats = List.of(GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM);
        RenderPipeline pipeline = pipeline(shaderName, formats, null, ColorTargetState.WRITE_ALL);
        List<MetalGpuTexture> textures = createTextures(formats, "unwritten-configured-target");
        render(pipeline, textures, List.of(
                new Vector4f(0.0F, 0.0F, 0.0F, 1.0F),
                new Vector4f(0.8F, 0.2F, 0.4F, 1.0F)
        ));

        ByteBuffer written = readback(textures.get(0));
        assertByteNear(written.get(0), 64, "written target red");
        assertByteNear(written.get(1), 128, "written target green");
        assertByteNear(written.get(2), 191, "written target blue");
        ByteBuffer unwritten = readback(textures.get(1));
        assertByteNear(unwritten.get(0), 204, "unwritten target clear red");
        assertByteNear(unwritten.get(1), 51, "unwritten target clear green");
        assertByteNear(unwritten.get(2), 102, "unwritten target clear blue");
        assertByteNear(unwritten.get(3), 255, "unwritten target clear alpha");
        closeTextures(textures);
    }

    @Test
    void fragmentOutputFormatMismatchFailsClosed() {
        verifyFragmentOutputFormatMismatchFailsClosed();
    }

    @Test
    void packedColorTargetsUseTheirDeclaredNumericClass() {
        assertDoesNotThrow(() -> MetalCrossShaderCompiler.validateFragmentOutputSignature(
                pipeline(
                        "packed_rg11b10_float",
                        List.of(GpuFormat.RG11B10_FLOAT),
                        null,
                        ColorTargetState.WRITE_ALL
                ),
                Map.of(0, MetalCrossShaderCompiler.FragmentOutputClass.FLOAT)
        ));
        assertDoesNotThrow(() -> MetalCrossShaderCompiler.validateFragmentOutputSignature(
                pipeline(
                        "packed_rgb10a2_unorm",
                        List.of(GpuFormat.RGB10A2_UNORM),
                        null,
                        ColorTargetState.WRITE_ALL
                ),
                Map.of(0, MetalCrossShaderCompiler.FragmentOutputClass.FLOAT)
        ));
        assertDoesNotThrow(() -> MetalCrossShaderCompiler.validateFragmentOutputSignature(
                pipeline(
                        "packed_rgb10a2_uint",
                        List.of(GpuFormat.RGB10A2_UINT),
                        null,
                        ColorTargetState.WRITE_ALL
                ),
                Map.of(0, MetalCrossShaderCompiler.FragmentOutputClass.UINT)
        ));
        assertThrows(
                ShaderCompileException.class,
                () -> MetalCrossShaderCompiler.validateFragmentOutputSignature(
                        pipeline(
                                "depth_as_color",
                                List.of(GpuFormat.D32_FLOAT),
                                null,
                                ColorTargetState.WRITE_ALL
                        ),
                        Map.of(0, MetalCrossShaderCompiler.FragmentOutputClass.FLOAT)
                )
        );
    }

    @Test
    void submitCallbacksTrackFiveSuccessfulInFlightBuffers() {
        AtomicInteger committed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        for (int index = 0; index < 5; index++) {
            encoder.commandBuffer();
            encoder.onCurrentSubmit(committed::incrementAndGet, failed::incrementAndGet);
            encoder.submit();
        }
        device.waitForSubmittedGpuWork();
        assertEquals(5, committed.get(), "every encoded transaction must observe a real command-buffer commit");
        assertEquals(0, failed.get(), "successful Metal command buffers must not poison frame history");
    }

    private void runRgbaAttachmentCount(final int count) {
        String shaderName = "mrt_rgba_" + count;
        StringBuilder fragment = new StringBuilder("#version 450\n");
        for (int index = 0; index < count; index++) {
            fragment.append("layout(location=").append(index).append(") out vec4 out")
                    .append(index).append(";\n");
        }
        fragment.append("void main() {\n");
        for (int index = 0; index < count; index++) {
            float red = 0.125F * (index + 1);
            fragment.append("out").append(index).append(" = vec4(")
                    .append(red).append(", 0.25, 0.5, 1.0);\n");
        }
        fragment.append("}\n");
        fragmentShaders.put(shaderName, fragment.toString());

        List<GpuFormat> formats = new ArrayList<>();
        for (int index = 0; index < count; index++) formats.add(GpuFormat.RGBA8_UNORM);
        RenderPipeline pipeline = pipeline(shaderName, formats, null, ColorTargetState.WRITE_ALL);
        List<MetalGpuTexture> textures = createTextures(formats, "rgba-" + count);
        render(pipeline, textures, null);

        for (int index = 0; index < count; index++) {
            ByteBuffer data = readback(textures.get(index));
            assertByteNear(data.get(0), Math.round(255.0F * 0.125F * (index + 1)), "RGBA red " + index);
            assertByteNear(data.get(1), 64, "RGBA green " + index);
            assertByteNear(data.get(2), 128, "RGBA blue " + index);
            assertByteNear(data.get(3), 255, "RGBA alpha " + index);
        }
        closeTextures(textures);
    }

    private void runMixedThreeAttachments() {
        String shaderName = "mrt_mixed_three";
        fragmentShaders.put(shaderName, """
                #version 450
                layout(location=0) out vec4 color;
                layout(location=1) out vec2 motion;
                layout(location=2) out float validity;
                void main() {
                    color = vec4(0.25, 0.5, 0.75, 1.0);
                    motion = vec2(-0.25, 0.5);
                    validity = 0.75;
                }
                """);
        List<GpuFormat> formats = List.of(
                GpuFormat.RGBA8_UNORM,
                GpuFormat.RG16_FLOAT,
                GpuFormat.R8_UNORM
        );
        RenderPipeline pipeline = pipeline(shaderName, formats, null, ColorTargetState.WRITE_ALL);
        List<MetalGpuTexture> textures = createTextures(formats, "mixed");
        render(pipeline, textures, List.of(
                new Vector4f(0.1F, 0.2F, 0.3F, 1.0F),
                new Vector4f(0.1F, -0.2F, 0.0F, 1.0F),
                new Vector4f(0.1F, 0.0F, 0.0F, 1.0F)
        ));

        ByteBuffer color = readback(textures.get(0));
        assertByteNear(color.get(0), 64, "mixed color red");
        assertByteNear(color.get(1), 128, "mixed color green");
        assertByteNear(color.get(2), 191, "mixed color blue");
        ByteBuffer motion = readback(textures.get(1)).order(ByteOrder.nativeOrder());
        assertEquals(-0.25F, Float.float16ToFloat(motion.getShort(0)), 0.01F);
        assertEquals(0.5F, Float.float16ToFloat(motion.getShort(2)), 0.01F);
        assertByteNear(readback(textures.get(2)).get(0), 191, "mixed validity");
        closeTextures(textures);
    }

    private void runNullMiddleSlot() {
        String shaderName = "mrt_null_middle";
        fragmentShaders.put(shaderName, """
                #version 450
                layout(location=0) out vec4 color;
                layout(location=2) out float validity;
                void main() {
                    color = vec4(0.75, 0.25, 0.5, 1.0);
                    validity = 0.25;
                }
                """);
        List<GpuFormat> formats = new ArrayList<>();
        formats.add(GpuFormat.RGBA8_UNORM);
        formats.add(null);
        formats.add(GpuFormat.R8_UNORM);
        RenderPipeline pipeline = pipeline(shaderName, formats, null, ColorTargetState.WRITE_ALL);
        List<MetalGpuTexture> textures = createTextures(formats, "null-middle");
        render(pipeline, textures, null);
        ByteBuffer color = readback(textures.get(0));
        assertByteNear(color.get(0), 191, "null slot color red");
        assertByteNear(color.get(1), 64, "null slot color green");
        assertByteNear(color.get(2), 128, "null slot color blue");
        assertByteNear(readback(textures.get(2)).get(0), 64, "null slot validity");
        closeTextures(textures);
    }

    private void runEightAttachmentSignature() {
        String shaderName = "mrt_eight";
        StringBuilder fragment = new StringBuilder("#version 450\n");
        for (int index = 0; index < 8; index++) {
            fragment.append("layout(location=").append(index).append(") out vec4 out")
                    .append(index).append(";\n");
        }
        fragment.append("void main() {\n");
        for (int index = 0; index < 8; index++) {
            fragment.append("out").append(index).append(" = vec4(")
                    .append((index + 1) / 16.0F).append(", 0.0, 0.0, 1.0);\n");
        }
        fragment.append("}\n");
        fragmentShaders.put(shaderName, fragment.toString());
        List<GpuFormat> formats = java.util.Collections.nCopies(8, GpuFormat.RGBA8_UNORM);
        RenderPipeline pipeline = pipeline(shaderName, formats, null, ColorTargetState.WRITE_ALL);
        List<MetalGpuTexture> textures = createTextures(formats, "eight");
        render(pipeline, textures, null);
        assertByteNear(readback(textures.get(7)).get(0), 128, "eighth attachment");
        closeTextures(textures);
    }

    private void runPerSlotClearLoadStoreBlendAndWriteMask() {
        String clearShaderName = "mrt_per_slot_clear";
        fragmentShaders.put(clearShaderName, """
                #version 450
                layout(location=0) out vec4 first;
                layout(location=1) out vec4 second;
                void main() {
                    first = vec4(1.0);
                    second = vec4(1.0);
                }
                """);
        List<GpuFormat> formats = List.of(GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM);
        RenderPipeline clearPipeline = pipeline(
                clearShaderName,
                List.of(
                        new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE),
                        new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE)
                )
        );
        List<MetalGpuTexture> textures = createTextures(formats, "per-slot-state");
        render(clearPipeline, textures, List.of(
                new Vector4f(0.1F, 0.2F, 0.3F, 1.0F),
                new Vector4f(0.4F, 0.5F, 0.6F, 1.0F)
        ));

        ByteBuffer clear0 = readback(textures.get(0));
        assertByteNear(clear0.get(0), 26, "slot 0 clear/store red");
        assertByteNear(clear0.get(1), 51, "slot 0 clear/store green");
        ByteBuffer clear1 = readback(textures.get(1));
        assertByteNear(clear1.get(0), 102, "slot 1 clear/store red");
        assertByteNear(clear1.get(1), 128, "slot 1 clear/store green");

        String shaderName = "mrt_per_slot_blend_mask";
        fragmentShaders.put(shaderName, """
                #version 450
                layout(location=0) out vec4 first;
                layout(location=1) out vec4 second;
                void main() {
                    first = vec4(0.25, 0.5, 0.75, 0.5);
                    second = vec4(0.9, 0.25, 0.1, 0.2);
                }
                """);
        RenderPipeline pipeline = pipeline(
                shaderName,
                List.of(
                        new ColorTargetState(
                                Optional.of(BlendFunction.ADDITIVE),
                                GpuFormat.RGBA8_UNORM,
                                ColorTargetState.WRITE_RED
                        ),
                        new ColorTargetState(
                                Optional.empty(),
                                GpuFormat.RGBA8_UNORM,
                                ColorTargetState.WRITE_GREEN
                        )
                )
        );
        renderLoad(pipeline, textures);
        ByteBuffer first = readback(textures.getFirst());
        assertByteNear(first.get(0), 89, "slot 0 additive red");
        assertByteNear(first.get(1), 51, "slot 0 masked green preserved load");
        assertByteNear(first.get(2), 77, "slot 0 masked blue preserved load");
        assertByteNear(first.get(3), 255, "slot 0 masked alpha preserved load");
        ByteBuffer second = readback(textures.get(1));
        assertByteNear(second.get(0), 102, "slot 1 masked red preserved load");
        assertByteNear(second.get(1), 64, "slot 1 green write");
        assertByteNear(second.get(2), 153, "slot 1 masked blue preserved load");
        assertByteNear(second.get(3), 255, "slot 1 masked alpha preserved load");
        closeTextures(textures);
    }

    private void verifyLegacySingleAttachmentAbi() {
        List<MetalGpuTexture> textures = createTextures(
                List.of(GpuFormat.RGBA8_UNORM), "legacy-single-attachment"
        );
        MetalGpuTexture texture = textures.getFirst();
        // This test intentionally bypasses MetalCommandEncoder's render-pass
        // path. End any batched device-initialization upload before creating
        // the raw legacy render encoder on the same command buffer.
        encoder.endEncoder();
        MTLRenderCommandEncoder legacyEncoder = encoder.commandBuffer().makeRenderCommandEncoder(
                texture.nativeHandle(),
                MemorySegment.NULL,
                WIDTH,
                HEIGHT,
                1,
                0.2F,
                0.4F,
                0.6F,
                1.0F,
                0,
                1.0
        );
        legacyEncoder.endEncoding();
        encoder.submit();
        device.waitForSubmittedGpuWork();
        ByteBuffer data = readback(texture);
        assertByteNear(data.get(0), 51, "legacy ABI red");
        assertByteNear(data.get(1), 102, "legacy ABI green");
        assertByteNear(data.get(2), 153, "legacy ABI blue");
        assertByteNear(data.get(3), 255, "legacy ABI alpha");
        closeTextures(textures);
    }

    private void verifyPipelineRenderPassSignatureMismatch() {
        String shaderName = "mrt_signature_mismatch";
        fragmentShaders.put(shaderName, """
                #version 450
                layout(location=0) out vec4 color;
                layout(location=1) out vec4 extra;
                void main() {
                    color = vec4(1.0);
                    extra = vec4(0.0);
                }
                """);
        RenderPipeline pipeline = pipeline(
                shaderName,
                List.of(GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM),
                null,
                ColorTargetState.WRITE_ALL
        );
        List<MetalGpuTexture> textures = createTextures(List.of(GpuFormat.RGBA8_UNORM), "signature-mismatch");
        try (PassWithViews pass = createPass(textures, null, false)) {
            IllegalArgumentException mismatch = assertThrows(
                    IllegalArgumentException.class,
                    () -> pass.pass().setPipeline(pipeline)
            );
            assertTrue(mismatch.getMessage().contains("signature mismatch"));
            encoder.submitRenderPass();
            encoder.submit();
            device.waitForSubmittedGpuWork();
        }
        closeTextures(textures);
    }

    private void verifyFragmentOutputLocationMismatchFailsClosed() {
        String shaderName = "mrt_fragment_location_mismatch";
        fragmentShaders.put(shaderName, """
                #version 450
                layout(location=1) out vec4 wrongLocation;
                void main() {
                    wrongLocation = vec4(1.0);
                }
                """);
        RenderPipeline pipeline = pipeline(
                shaderName,
                List.of(GpuFormat.RGBA8_UNORM),
                null,
                ColorTargetState.WRITE_ALL
        );
        IllegalStateException mismatch = assertThrows(
                IllegalStateException.class,
                () -> device.getOrCompilePipeline(pipeline)
        );
        assertTrue(mismatch.getMessage().contains("Failed to compile Metal cross shader"));
        assertNotNull(mismatch.getCause());
        assertTrue(mismatch.getCause().getMessage().contains("location mismatch"));
    }

    private void verifyFragmentOutputFormatMismatchFailsClosed() {
        String shaderName = "mrt_fragment_format_mismatch";
        fragmentShaders.put(shaderName, """
                #version 450
                layout(location=0) out uvec4 integerColor;
                void main() {
                    integerColor = uvec4(1u, 2u, 3u, 4u);
                }
                """);
        RenderPipeline pipeline = pipeline(
                shaderName,
                List.of(GpuFormat.RGBA8_UNORM),
                null,
                ColorTargetState.WRITE_ALL
        );
        IllegalStateException mismatch = assertThrows(
                IllegalStateException.class,
                () -> device.getOrCompilePipeline(pipeline)
        );
        assertTrue(mismatch.getMessage().contains("Failed to compile Metal cross shader"));
        assertNotNull(mismatch.getCause());
        assertTrue(mismatch.getCause().getMessage().contains("numeric-class mismatch"));
    }

    private RenderPipeline pipeline(
            String shaderName,
            List<GpuFormat> formats,
            BlendFunction blend,
            int writeMask
    ) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation("metallum_test/" + shaderName)
                .withVertexShader("metallum_test/mrt_vertex")
                .withFragmentShader("metallum_test/" + shaderName)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false);
        for (int index = 0; index < formats.size(); index++) {
            GpuFormat format = formats.get(index);
            if (format == null) {
                builder.withUnusedColorTargetState(index);
            } else {
                builder.withColorTargetState(
                        index,
                        new ColorTargetState(Optional.ofNullable(blend), format, writeMask)
                );
            }
        }
        return builder.build();
    }

    private RenderPipeline pipeline(
            String shaderName,
            List<ColorTargetState> targets
    ) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation("metallum_test/" + shaderName)
                .withVertexShader("metallum_test/mrt_vertex")
                .withFragmentShader("metallum_test/" + shaderName)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false);
        for (int index = 0; index < targets.size(); index++) {
            builder.withColorTargetState(index, targets.get(index));
        }
        return builder.build();
    }

    private List<MetalGpuTexture> createTextures(List<GpuFormat> formats, String labelPrefix) {
        List<MetalGpuTexture> result = new ArrayList<>(formats.size());
        for (int index = 0; index < formats.size(); index++) {
            GpuFormat format = formats.get(index);
            result.add(format == null ? null : (MetalGpuTexture) device.createTexture(
                    labelPrefix + "-" + index,
                    TEXTURE_USAGE,
                    format,
                    WIDTH,
                    HEIGHT,
                    1,
                    1
            ));
        }
        return result;
    }

    private void render(
            RenderPipeline pipeline,
            List<MetalGpuTexture> textures,
            List<Vector4f> clearColors
    ) {
        try (PassWithViews pass = createPass(textures, clearColors, false)) {
            pass.pass().setPipeline(pipeline);
            pass.pass().draw(3, 1, 0, 0);
            encoder.submitRenderPass();
            encoder.submit();
            device.waitForSubmittedGpuWork();
        }
    }

    private void renderLoad(
            RenderPipeline pipeline,
            List<MetalGpuTexture> textures
    ) {
        try (PassWithViews pass = createPass(textures, null, true)) {
            pass.pass().setPipeline(pipeline);
            pass.pass().draw(3, 1, 0, 0);
            encoder.submitRenderPass();
            encoder.submit();
            device.waitForSubmittedGpuWork();
        }
    }

    private RenderPipeline vertexBackedPipeline(final String shaderName) {
        return RenderPipeline.builder()
                .withLocation("metallum_test/" + shaderName)
                .withVertexShader("metallum_test/" + shaderName)
                .withFragmentShader("metallum_test/" + shaderName)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
                .withColorTargetState(0, new ColorTargetState(
                        Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL
                ))
                .build();
    }

    private static String solidFragment(final float red, final float green, final float blue) {
        return "#version 450\nlayout(location=0) out vec4 color;\nvoid main() { color = vec4("
                + Float.toString(red) + ", "
                + Float.toString(green) + ", "
                + Float.toString(blue) + ", 1.0); }\n";
    }

    private static ByteBuffer fullScreenPositionTexTriangle() {
        int stride = DefaultVertexFormat.POSITION_TEX.getVertexSize();
        VertexFormatElement position = DefaultVertexFormat.POSITION_TEX.getElements().stream()
                .filter(candidate -> candidate.name().equals("Position"))
                .findFirst()
                .orElseThrow();
        VertexFormatElement uv = DefaultVertexFormat.POSITION_TEX.getElements().stream()
                .filter(candidate -> candidate.name().equals("UV0"))
                .findFirst()
                .orElseThrow();
        ByteBuffer result = ByteBuffer.allocateDirect(3 * stride).order(ByteOrder.nativeOrder());
        float[][] points = {{-1.0F, -1.0F}, {3.0F, -1.0F}, {-1.0F, 3.0F}};
        for (int index = 0; index < points.length; index++) {
            int base = index * stride;
            result.putFloat(base + position.offset(), points[index][0]);
            result.putFloat(base + position.offset() + Float.BYTES, points[index][1]);
            result.putFloat(base + position.offset() + 2 * Float.BYTES, 0.0F);
            result.putFloat(base + uv.offset(), 0.0F);
            result.putFloat(base + uv.offset() + Float.BYTES, 0.0F);
        }
        return result;
    }

    private PassWithViews createPass(
            List<MetalGpuTexture> textures,
            List<Vector4f> clearColors,
            boolean load
    ) {
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "Java MRT backend integration");
        List<MetalGpuTextureView> views = new ArrayList<>();
        for (int index = 0; index < textures.size(); index++) {
            MetalGpuTexture texture = textures.get(index);
            if (texture == null) {
                descriptor.withUnusedColorAttachment();
            } else {
                MetalGpuTextureView view = new MetalGpuTextureView(texture, 0, 1);
                views.add(view);
                Optional<Vector4fc> clear = load
                        ? Optional.empty()
                        : Optional.of(clearColors == null ? new Vector4f(0.0F) : clearColors.get(index));
                descriptor.withColorAttachment(
                        view,
                        clear
                );
            }
        }
        descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, WIDTH, HEIGHT));
        return new PassWithViews((MetalRenderPass) encoder.createRenderPass(descriptor), views);
    }

    private ByteBuffer readback(MetalGpuTexture texture) {
        int size = WIDTH * HEIGHT * texture.pixelSize();
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "MRT readback",
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

    private record PassWithViews(
            MetalRenderPass pass,
            List<MetalGpuTextureView> views
    ) implements AutoCloseable {
        @Override
        public void close() {
            for (MetalGpuTextureView view : views) {
                view.close();
            }
        }
    }

    private static void closeTextures(List<MetalGpuTexture> textures) {
        for (MetalGpuTexture texture : textures) {
            if (texture != null) texture.close();
        }
    }

    private static void assertByteNear(byte actualByte, int expected, String label) {
        int actual = Byte.toUnsignedInt(actualByte);
        assertTrue(Math.abs(actual - expected) <= 1, label + ": expected " + expected + ", got " + actual);
    }
}
