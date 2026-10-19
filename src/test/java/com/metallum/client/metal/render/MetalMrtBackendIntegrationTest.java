package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
            com.mojang.renderpearl.api.textures.GpuTexture.USAGE_RENDER_ATTACHMENT
                    | com.mojang.renderpearl.api.textures.GpuTexture.USAGE_COPY_SRC;

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
        ShaderSource source = MetalShaderSourceAdapters.from((identifier, type) -> {
            String name = identifier.getPath().substring(identifier.getPath().lastIndexOf('/') + 1);
            return type == ShaderType.VERTEX
                    ? vertexShaders.getOrDefault(name, VERTEX_SHADER)
                    : fragmentShaders.get(name);
        });
        device = new MetalDevice(
                source,
                new GpuDebugOptions(2, true, true, true),
                nativeDevice,
                MemorySegment.NULL,
                "Metal MRT integration device",
                MemorySegment.NULL
        );
        encoder = device.commandEncoder();
        if (Boolean.getBoolean("metallum.test.mrtMetal4Commands")) {
            assertTrue(device.metal4Available(), "MTL4 command lane requires actual device capability");
            encoder.submit();
            device.waitForSubmittedGpuWork();
            assertEquals(1, MetalNativeBridge.metallum_metal4_main_renderer_enable(nativeDevice, MemorySegment.NULL),
                    "the shipping offscreen MTL4 command queue must activate");
        }
    }

    @AfterEach
    void closeDevice() {
        MetalFxManager.close();
        if (device != null) {
            device.close();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "metallum.ci.requireOptimizedDefaults", matches = "true")
    void productionDefaultsEnableTheValidatedCpuOptimizations() {
        assertEquals(new MetalBackendOptimizations.Snapshot(true, true, true, true, true), MetalBackendOptimizations.snapshot());
    }

    @Test
    @EnabledIfSystemProperty(named = "metallum.ci.encodingCost", matches = "true")
    void hostedEncodingCost() {
        String name = "hosted_encoding_cost";
        fragmentShaders.put(name, """
                #version 450
                layout(location=0) out vec4 color;
                void main() { color = vec4(1, 0, 1, 1); }
                """);
        RenderPipeline pipeline = pipeline(name, List.of(GpuFormat.RGBA8_UNORM), null, ColorTargetState.WRITE_ALL);
        RenderPipeline alternatePipeline = pipeline(name, List.of(GpuFormat.RGBA8_UNORM),
                BlendFunction.ADDITIVE, ColorTargetState.WRITE_ALL);
        List<MetalGpuTexture> textures = createTextures(List.of(GpuFormat.RGBA8_UNORM), name);
        var allocation = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        assertTrue(allocation.isThreadAllocatedMemorySupported());
        allocation.setThreadAllocatedMemoryEnabled(true);
        try {
            // Precompile both variants before any measured draw. Alpha=1 and
            // saturated magenta make both blend states produce the same pixels.
            device.getOrCompilePipeline(pipeline);
            device.getOrCompilePipeline(alternatePipeline);
            for (int workload = 0; workload < 3; workload++) {
                boolean manyEncoders = workload == 2;
                boolean multipleStates = workload == 1;
                double[] timings = new double[8];
                long[] allocatedBytes = new long[8];
                int operations = manyEncoders ? 256 : 4096;
                int warmupRounds = manyEncoders ? 64 : 8;
                for (int round = -warmupRounds; round < timings.length; round++) {
                    long allocatedBefore = allocation.getCurrentThreadAllocatedBytes();
                    long start;
                    long elapsed;
                    if (manyEncoders) {
                        start = System.nanoTime();
                        for (int i = 0; i < operations; i++) {
                            try (PassWithViews pass = createPass(textures, null, false)) {
                                pass.pass().setPipeline(pipeline);
                                pass.pass().draw(3, 1, 0, 0);
                                encoder.submitRenderPass();
                            }
                        }
                        elapsed = System.nanoTime() - start;
                    } else {
                        try (PassWithViews pass = createPass(textures, null, false)) {
                            pass.pass().setPipeline(pipeline);
                            pass.pass().draw(3, 1, 0, 0); // PSO/bind setup is outside the window.
                            start = System.nanoTime();
                            for (int i = 0; i < operations; i++) {
                                if (multipleStates) pass.pass().setPipeline((i & 1) == 0 ? pipeline : alternatePipeline);
                                pass.pass().enableScissor(i & 1, 0, WIDTH - 1, HEIGHT);
                                pass.pass().draw(3, 1, 0, 0);
                            }
                            elapsed = System.nanoTime() - start;
                        }
                    }
                    long bytes = allocation.getCurrentThreadAllocatedBytes() - allocatedBefore;
                    encoder.submit();
                    device.waitForSubmittedGpuWork(); // Never time the GPU wait as CPU encoding.
                    if (round >= 0) {
                        timings[round] = (double) elapsed / operations;
                        allocatedBytes[round] = bytes / operations;
                    }
                }
                System.out.println("HOSTED_ENCODING_COST {\"profile\":\""
                        + System.getProperty("metallum.ci.encodingCostProfile") + "\",\"workload\":\""
                        + (manyEncoders ? "encoder" : multipleStates ? "multiple-state-draw" : "single-state-draw") + "\",\"nsPerOperation\":"
                        + java.util.Arrays.toString(timings) + ",\"javaBytesPerOperation\":"
                        + java.util.Arrays.toString(allocatedBytes) + "}");
            }
            ByteBuffer pixels = readback(textures.get(0));
            for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
                assertByteNear(pixels.get(pixel * 4), 255, "cost workload red");
                assertByteNear(pixels.get(pixel * 4 + 1), 0, "cost workload green");
                assertByteNear(pixels.get(pixel * 4 + 2), 255, "cost workload blue");
            }
        } finally {
            closeTextures(textures);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "metallum.ci.indexedBatchCost", matches = "true")
    void hostedIndexedBatchCost() {
        assertTrue(MetalBackendOptimizations.FUSED_STATE_DRAW && MetalBackendOptimizations.REUSE_ENCODER_STATE
                && MetalBackendOptimizations.REUSE_NATIVE_ENCODER_ARGUMENTS && MetalBackendOptimizations.DYNAMIC_UPLOAD_RANGE_COPY,
                "the prior four optimizations must stay enabled in both cost lanes");
        assertEquals("candidate".equals(System.getProperty("metallum.ci.encodingCostProfile")),
                MetalBackendOptimizations.BATCH_INDEXED_VERTEX_REBASE);
        assertFalse(Boolean.getBoolean("metallum.hotpath.telemetry"), "timing must run without hot-path counters");
        assertFalse(Boolean.getBoolean("metallum.ci.reuseActivationTelemetry"), "timing must run without reuse counters");
        String name = "indexed_batch_cost";
        RenderPipeline pipeline = indexedColorPipeline(name);
        List<MetalGpuTexture> textures = createTextures(List.of(GpuFormat.RGBA8_UNORM), name);
        ByteBuffer indices = ByteBuffer.allocateDirect(6 * Integer.BYTES).order(ByteOrder.nativeOrder());
        for (int index : new int[]{0, 1, 2, 2, 3, 0}) indices.putInt(index);
        indices.flip();
        var parameters = ByteBuffer.allocateDirect(128 * 3 * Integer.BYTES)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        for (int draw = 0; draw < 128; draw++) parameters.put(0).put(6).put(4 + (draw & 1) * 4);
        parameters.flip();
        var allocation = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        assertTrue(allocation.isThreadAllocatedMemorySupported());
        allocation.setThreadAllocatedMemoryEnabled(true);
        try (GpuBuffer vertices = device.createBuffer(() -> name + " vertices", GpuBuffer.USAGE_VERTEX, indexedColorVertices(4));
             GpuBuffer indexBuffer = device.createBuffer(() -> name + " indices", GpuBuffer.USAGE_INDEX, indices)) {
            device.getOrCompilePipeline(pipeline);
            for (int batchSize : new int[]{2, 16, 128}) {
                int operations = 2048 / batchSize;
                double[] timings = new double[8];
                long[] allocatedBytes = new long[8];
                for (int round = -16; round < timings.length; round++) {
                    try (PassWithViews pass = createPass(textures, null, false)) {
                        pass.pass().setPipeline(pipeline);
                        pass.pass().setVertexBuffer(0, vertices.slice());
                        pass.pass().setIndexBuffer(indexBuffer, IndexType.INT);
                        pass.pass().drawIndexed(6, 1, 0, 4, 0);
                        long beforeBytes = allocation.getCurrentThreadAllocatedBytes();
                        long start = System.nanoTime();
                        for (int operation = 0; operation < operations; operation++) {
                            pass.pass().multiDrawIndexed(parameters, 1, 0, batchSize);
                        }
                        long elapsed = System.nanoTime() - start;
                        long bytes = allocation.getCurrentThreadAllocatedBytes() - beforeBytes;
                        if (round >= 0) {
                            timings[round] = (double) elapsed / (operations * batchSize);
                            allocatedBytes[round] = bytes / (operations * batchSize);
                        }
                        encoder.submitRenderPass();
                    }
                    encoder.submit();
                    device.waitForSubmittedGpuWork();
                }
                System.out.println("HOSTED_INDEXED_BATCH_COST {\"profile\":\""
                        + System.getProperty("metallum.ci.encodingCostProfile")
                        + "\",\"drawsPerBatch\":" + batchSize + ",\"nsPerDraw\":"
                        + java.util.Arrays.toString(timings) + ",\"javaBytesPerDraw\":"
                        + java.util.Arrays.toString(allocatedBytes) + "}");
            }
            ByteBuffer pixels = readback(textures.getFirst());
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    int pixel = (y * WIDTH + x) * 4;
                    assertByteNear(pixels.get(pixel), x < WIDTH / 2 ? 0 : 255, "indexed cost red");
                    assertByteNear(pixels.get(pixel + 1), x < WIDTH / 2 ? 255 : 0, "indexed cost green");
                    assertByteNear(pixels.get(pixel + 2), 0, "indexed cost blue");
                }
            }
        } finally {
            closeTextures(textures);
        }
    }

    private RenderPipeline indexedColorPipeline(String name) {
        vertexShaders.put(name, """
                #version 450
                layout(location=0) in vec2 position;
                layout(location=1) in vec4 vertexColor;
                layout(location=0) out vec4 color;
                void main() { gl_Position = vec4(position, 0, 1); color = vertexColor; }
                """);
        fragmentShaders.put(name, """
                #version 450
                layout(location=0) in vec4 color;
                layout(location=0) out vec4 outputColor;
                void main() { outputColor = color; }
                """);
        return RenderPipeline.builder()
                .withLocation("metallum_test/" + name)
                .withVertexShader("metallum_test/" + name)
                .withFragmentShader("metallum_test/" + name)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
                .withVertexBinding(0, com.mojang.renderpearl.api.vertex.VertexFormat.builder(0)
                        .addAttribute("position", GpuFormat.RG32_FLOAT)
                        .addAttribute("vertexColor", GpuFormat.RGBA8_UNORM).build())
                .withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM,
                        ColorTargetState.WRITE_ALL)).build();
    }

    private static ByteBuffer indexedColorVertices(int prefixVertices) {
        ByteBuffer data = ByteBuffer.allocateDirect((prefixVertices + 8) * 12).order(ByteOrder.nativeOrder());
        data.position(prefixVertices * 12);
        for (int quad = 0; quad < 2; quad++) {
            for (int vertex = 0; vertex < 4; vertex++) {
                data.putFloat(quad - 1 + (vertex == 1 || vertex == 2 ? 1 : 0));
                data.putFloat(vertex >= 2 ? 1 : -1);
                data.putInt(quad == 0 ? 0xff00ff00 : 0xff0000ff);
            }
        }
        return data.flip();
    }

    @Test
    void submissionBudgetBoundsObservedBacklogAndCompletesEachCallbackOnce() {
        encoder.submit();
        device.waitForSubmittedGpuWork();
        int budget = new MetalSubmissionBackpressure(System.getProperty("metallum.maxSubmitsInFlight")).limit();
        assertEquals(budget, encoder.maxSubmitsInFlight());
        AtomicInteger committed = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger outstanding = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        for (int i = 0; i < 12; i++) {
            encoder.commandBuffer();
            encoder.onCurrentSubmit(() -> {
                committed.incrementAndGet();
                peak.accumulateAndGet(outstanding.incrementAndGet(), Math::max);
            }, () -> {
                completed.incrementAndGet();
                outstanding.decrementAndGet();
            }, () -> fail("GPU submission failed"));
            encoder.submit();
        }
        device.waitForSubmittedGpuWork();
        assertEquals(12, committed.get());
        assertEquals(12, completed.get(), "completion must be delivered exactly once across ring reuse");
        assertEquals(0, outstanding.get());
        assertTrue(peak.get() <= budget, "observed completion backlog exceeded the configured GPU budget");
    }

    @Test
    void stateDrawTransportPreservesOffsetsInstancingAndSingleDrawAcrossEncoders() {
        String name = "state_draw_transport";
        vertexShaders.put("mrt_vertex", """
                #version 450
                layout(location=0) flat out int instance_id;
                void main() {
                    vec2 p[3] = vec2[](vec2(-1,-1), vec2(3,-1), vec2(-1,3));
                    gl_Position = vec4(p[gl_VertexIndex - 3], 0, 1);
                    instance_id = gl_InstanceIndex;
                }
                """);
        fragmentShaders.put(name, """
                #version 450
                layout(location=0) flat in int instance_id;
                layout(location=0) out vec4 color;
                void main() { color = vec4(float(instance_id + 1) / 64.0, 0.125, 0.0625, 1); }
                """);
        RenderPipeline pipeline = pipeline(name, List.of(GpuFormat.RGBA8_UNORM),
                BlendFunction.ADDITIVE, ColorTargetState.WRITE_ALL);
        ByteBuffer data = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());
        data.putShort((short) 999).putShort((short) 4).putShort((short) 5).putShort((short) 6).flip();
        long before = com.metallum.client.metal.render.mtl.MetalRenderStatePacketTelemetry.fusedDrawCalls();
        long rejectedBefore = com.metallum.client.metal.render.mtl.MetalRenderStatePacketTelemetry.rejectedFusedDrawCalls();
        var reuseBefore = com.metallum.client.metal.render.mtl.MetalRenderStatePacketTelemetry.snapshot();
        List<MetalGpuTexture> textures = createTextures(List.of(GpuFormat.RGBA8_UNORM), name);
        try (GpuBuffer indices = device.createBuffer(() -> "state draw indices", GpuBuffer.USAGE_INDEX, data)) {
            for (int round = 0; round < 4; round++) {
                try (PassWithViews pass = createPass(textures, null, false)) {
                    pass.pass().setPipeline(pipeline);
                    if ((round & 1) == 0) {
                        pass.pass().draw(3, 2, 3, 3);
                    } else {
                        pass.pass().setIndexBuffer(indices, IndexType.SHORT);
                        pass.pass().drawIndexed(3, 2, 1, -1, 3);
                    }
                    encoder.submitRenderPass();
                    encoder.submit();
                    device.waitForSubmittedGpuWork();
                }
                ByteBuffer pixels = readback(textures.get(0));
                for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
                    assertByteNear(pixels.get(pixel * 4), 36, "instance/base-instance red");
                    assertByteNear(pixels.get(pixel * 4 + 1), 64, "exactly two additive instances green");
                    assertByteNear(pixels.get(pixel * 4 + 2), 32, "exactly one draw blue");
                }
            }
        } finally {
            closeTextures(textures);
        }
        if (MetalBackendOptimizations.FUSED_STATE_DRAW) {
            assertTrue(com.metallum.client.metal.render.bridge.MetalRenderStatePacketBridge.drawAvailable(),
                    "the enabled lane requires the shipping v2 entry, not legacy fallback");
            assertTrue(com.metallum.client.metal.render.mtl.MetalRenderStatePacketTelemetry.fusedDrawCalls() >= before + 4,
                    "every recreated encoder must actually activate fused state/draw transport");
            assertEquals(rejectedBefore, com.metallum.client.metal.render.mtl.MetalRenderStatePacketTelemetry.rejectedFusedDrawCalls(),
                    "valid direct and indexed draws must not silently fall back");
        }
        if (com.metallum.client.metal.render.mtl.MetalRenderStatePacketTelemetry.reuseActivationTelemetryEnabled()) {
            var after = com.metallum.client.metal.render.mtl.MetalRenderStatePacketTelemetry.snapshot();
            if (MetalBackendOptimizations.REUSE_ENCODER_STATE) {
                assertTrue(after.shadowReuseHits() > reuseBefore.shadowReuseHits());
                assertTrue(after.packetStorageReuseHits() > reuseBefore.packetStorageReuseHits());
            }
            if (MetalBackendOptimizations.REUSE_NATIVE_ENCODER_ARGUMENTS) {
                assertTrue(after.nativeEncoderArgumentReuseCalls() > reuseBefore.nativeEncoderArgumentReuseCalls());
            }
        }
    }

    @Test
    void timestampQueriesWaitForGpuCompletionAndCalibrateToJvmClock() {
        ByteBuffer data = ByteBuffer.allocateDirect(256);
        for (int i = 0; i < data.capacity(); i++) data.put(i, (byte) (i * 7));
        try (var queries = device.createTimestampQueryPool(2);
             MetalGpuBuffer destination = (MetalGpuBuffer) device.createBuffer(
                     () -> "timestamp upload", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, data.capacity())) {
            assertEquals(2, queries.size());
            assertTrue(queries.getValue(0).isEmpty());
            if (device.getDeviceInfo().timestampPeriod() == 0.0F) {
                assertThrows(UnsupportedOperationException.class, () -> encoder.writeTimestamp(queries, 0),
                        "a device without counter storage must reject GPU timestamp writes");
                assertTrue(queries.getValue(0).isEmpty(), "unsupported queries cannot fabricate samples");
                assertThrows(UnsupportedOperationException.class, device::getTimestampCalibrationOffset,
                        "a device without GPU samples cannot calibrate their clock");
                return;
            }
            encoder.writeTimestamp(queries, 0);
            encoder.writeToBuffer(destination.slice(0, data.capacity()), data);
            encoder.writeTimestamp(queries, 1);
            assertTrue(queries.getValue(0).isEmpty(), "unsubmitted GPU work must not return a timestamp");
            assertThrows(IllegalStateException.class, () -> encoder.writeTimestamp(queries, 1),
                    "an in-flight slot cannot be silently overwritten");
            encoder.submit();
            device.waitForSubmittedGpuWork();
            var values = queries.getValues(0, 2);
            assertTrue(values[0].isPresent(), "first GPU sample must resolve");
            assertTrue(values[1].isPresent(), "second GPU sample must resolve");
            assertTrue(values[1].getAsLong() >= values[0].getAsLong(),
                    "GPU samples must preserve command-stream order");
            long offset = device.getTimestampCalibrationOffset();
            long hostEquivalent = Math.round(values[1].getAsLong()
                    * (double) device.getDeviceInfo().timestampPeriod()) + offset;
            assertTrue(Math.abs(hostEquivalent - System.nanoTime()) < 5_000_000_000L,
                    () -> "calibrated GPU sample must be near the completed JVM timeline: gpu="
                            + values[1].getAsLong() + ", period=" + device.getDeviceInfo().timestampPeriod()
                            + ", offset=" + offset + ", hostEquivalent=" + hostEquivalent
                            + ", now=" + System.nanoTime());
            for (int i = 0; i < data.capacity(); i++) {
                assertEquals(data.get(i), destination.currentStorage().get(i));
            }
            assertThrows(IndexOutOfBoundsException.class, () -> queries.getValue(2));
            encoder.writeTimestamp(queries, 0);
            assertTrue(queries.getValue(0).isEmpty(), "a reused slot must discard its previous result");
            encoder.submit();
            device.waitForSubmittedGpuWork();
            assertTrue(queries.getValue(0).orElseThrow() >= values[1].getAsLong());
        }
    }

    @Test
    void timestampQueryPoolSupportsProfilerCapacity() {
        try (var queries = device.createTimestampQueryPool(1024)) {
            assertEquals(1024, queries.size());
            if (device.getDeviceInfo().timestampPeriod() == 0.0F) {
                assertThrows(UnsupportedOperationException.class, () -> encoder.writeTimestamp(queries, 1023));
                assertTrue(queries.getValues(1022, 2)[1].isEmpty());
                return;
            }
            encoder.writeTimestamp(queries, 1023);
            assertTrue(queries.getValues(1022, 2)[1].isEmpty());
            encoder.submit();
            device.waitForSubmittedGpuWork();
            var values = queries.getValues(1022, 2);
            assertTrue(values[0].isEmpty());
            assertTrue(values[1].isPresent());
        }
    }

    @Test
    void renderPassTimestampIsGpuSampleOrExplicitlyUnsupported() {
        try (var queries = device.createTimestampQueryPool(1);
             MetalGpuTexture texture = (MetalGpuTexture) device.createTexture(
                     "timestamp attachment", TEXTURE_USAGE, GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 1);
             MetalGpuTextureView view = new MetalGpuTextureView(texture, 0, 1)) {
            MetalRenderPass pass = encoder.createRenderPass(RenderPassDescriptor.builder(() -> "timestamp pass")
                    .withColorAttachment(view, Optional.of(new Vector4f(0.25F, 0.5F, 0.75F, 1.0F))).build());
            boolean supported = true;
            try {
                pass.writeTimestamp(queries, 0);
            } catch (UnsupportedOperationException unavailable) {
                supported = false;
                assertFalse(Boolean.getBoolean("metallum.test.mrtMetal4Commands"),
                        "Metal 4 counter heaps must support render-pass timestamps");
            }
            encoder.submitRenderPass();
            encoder.submit();
            device.waitForSubmittedGpuWork();
            assertEquals(supported, queries.getValue(0).isPresent(),
                    "unavailable render-pass samples must never become host timestamps");
            assertByteNear(readback(texture).get(0), 64, "timestamp pass must still clear red");
        }
    }

    @Test
    void emptyBufferTransfersAreNoOpsUnderMetalValidation() {
        try (MetalGpuBuffer source = (MetalGpuBuffer) device.createBuffer(
                () -> "empty copy source", GpuBuffer.USAGE_COPY_SRC, 16);
             MetalGpuBuffer destination = (MetalGpuBuffer) device.createBuffer(
                     () -> "empty copy destination", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 16)) {
            destination.currentStorage().put(0, (byte) 0x5A);
            encoder.writeToBuffer(destination.slice(0, 0), ByteBuffer.allocateDirect(0));
            encoder.copyToBuffer(source.slice(0, 0), destination.slice(0, 0));
            encoder.submit();
            device.waitForSubmittedGpuWork();
            assertEquals((byte) 0x5A, destination.currentStorage().get(0));
        }
    }

    @Test
    void pollingUnsubmittedFencePreservesTransientUploadsUntilExplicitSubmit() {
        ByteBuffer source = ByteBuffer.allocateDirect(64);
        for (int i = 0; i < 64; i++) source.put(i, (byte) (i * 17));
        try (MetalGpuBuffer destination = (MetalGpuBuffer) device.createBuffer(
                () -> "fence poll copy", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, 64)) {
            var slice = encoder.transientMemory().uploadStaging(source, 4, GpuBuffer.USAGE_COPY_SRC);
            MetalGpuBuffer transientBuffer = (MetalGpuBuffer) slice.buffer();
            encoder.copyToBuffer(slice, destination.slice(0, 64));
            AtomicInteger submits = new AtomicInteger();
            encoder.onCurrentSubmit(submits::incrementAndGet, () -> fail("submit failed"));
            try (var fence = encoder.createFence()) {
                assertFalse(fence.awaitCompletion(0L));
                assertFalse(fence.awaitCompletion(0L));
                assertEquals(0, submits.get(), "polling must not split the frame submission");
                assertFalse(transientBuffer.isClosed(), "polling must not invalidate current transient slices");

                encoder.submit();
                assertEquals(1, submits.get());
                assertTrue(fence.awaitCompletion(-1L), "RenderPearl's infinite wait must complete");
                assertTrue(fence.awaitCompletion(0L), "completed fences remain complete");
                ByteBuffer copied = destination.currentStorage();
                for (int i = 0; i < 64; i++) assertEquals((byte) (i * 17), copied.get(i));

                // Frame-end ring rotation occurs after submit. Its fence must
                // not start depending on work recorded after that rotation.
                try (var frameEndFence = encoder.createFence()) {
                    encoder.commandBuffer();
                    encoder.onCurrentSubmit(submits::incrementAndGet, () -> fail("later submit failed"));
                    assertTrue(frameEndFence.awaitCompletion(-1L));
                    assertEquals(1, submits.get(), "frame-end fence must not flush future work");
                    encoder.submit();
                    assertEquals(2, submits.get());
                }
            }
        }
    }

    @Test
    void blockingFenceMaterializesDeferredClearBeforeUsingPreviousSubmit() {
        // The device may leave an empty command buffer open during setup. End
        // it so this reproduces the deferred-clear-only state from the
        // production boundary.
        encoder.submit();
        device.waitForSubmittedGpuWork();
        try (MetalGpuTexture texture = (MetalGpuTexture) device.createTexture(
                () -> "blocking deferred fence clear",
                TEXTURE_USAGE,
                GpuFormat.RGBA8_UNORM,
                WIDTH,
                HEIGHT,
                1,
                1
        )) {
            // This leaves no native command buffer open. The fence must still
            // cover the deferred clear rather than treating the previous
            // submit as the completion witness.
            encoder.clearColorTexture(texture, new Vector4f(0.125F, 0.25F, 0.5F, 1.0F));
            try (var fence = encoder.createFence()) {
                assertFalse(fence.awaitCompletion(0L),
                        "zero-time poll must not materialize or submit deferred clear");
                assertTrue(encoder.hasPendingClear(texture));
                assertTrue(fence.awaitCompletion(-1L));
                assertFalse(encoder.hasPendingClear(texture),
                        "blocking fence must materialize deferred clear before it completes");
            }
            device.waitForSubmittedGpuWork();
        }
    }

    @Test
    void pendingPipelineDoesNotSurviveCacheInvalidation() {
        String name = "pending_generation";
        fragmentShaders.put(name, """
                #version 450
                layout(location=0) out vec4 color;
                void main() { color = vec4(1); }
                """);
        RenderPipeline pipeline = pipeline(name, List.of(GpuFormat.RGBA8_UNORM), null, ColorTargetState.WRITE_ALL);
        ShaderSource source = MetalShaderSourceAdapters.from((identifier, type) ->
                type == ShaderType.VERTEX ? VERTEX_SHADER : fragmentShaders.get(name));
        try (var builder = new com.mojang.renderpearl.frontend.shaders.PipelineBuilder(device);
             var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var pending = builder.compilePipeline(pipeline, source, executor).join();
            device.clearPipelineCache();
            assertNull(pending.finishCompile(), "a cleared generation must not publish its prepared native PSO");
            assertThrows(IllegalStateException.class, pending::finishCompile,
                    "a consumed pending must not compile again using retired shader modules");
        }
        MetalCompiledRenderPipeline first = device.getOrCompilePipeline(pipeline);
        first.close();
        first.close();
        assertFalse(first.isValid(), "closed PSO must not remain valid in the device cache");
        MetalCompiledRenderPipeline replacement = device.getOrCompilePipeline(pipeline);
        assertNotSame(first, replacement);
        assertTrue(replacement.isValid());
    }

    @Test
    void partialDynamicUploadKeepsPriorGpuConsumerAndUntouchedBytes() {
        var telemetryBefore = com.metallum.client.metal.render.mtl.MetalHotPathTelemetry.snapshot();
        int size = 65536, patchOffset = 8192, patchLength = 49152;
        ByteBuffer original = ByteBuffer.allocateDirect(size);
        for (int i = 0; i < size; i++) original.put(i, (byte) i);
        ByteBuffer patch = ByteBuffer.allocateDirect(patchLength + 4);
        for (int i = 0; i < patch.capacity(); i++) patch.put(i, (byte) (100 + i));
        patch.position(2).limit(patchLength + 2);
        try (MetalGpuBuffer source = (MetalGpuBuffer) device.createBuffer(
                    () -> "dynamic source", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST, size);
             MetalGpuBuffer before = (MetalGpuBuffer) device.createBuffer(
                    () -> "old consumer", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, size);
             MetalGpuBuffer after = (MetalGpuBuffer) device.createBuffer(
                    () -> "new consumer", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, size)) {
            encoder.writeToBuffer(source.slice(0, size), original);
            MemorySegment oldBacking = source.nativeHandle();
            encoder.copyToBuffer(source.slice(0, size), before.slice(0, size));
            encoder.writeToBuffer(source.slice(patchOffset, patchLength), patch);
            assertNotEquals(oldBacking, source.nativeHandle());
            encoder.copyToBuffer(source.slice(0, size), after.slice(0, size));
            encoder.submit();
            device.waitForSubmittedGpuWork();
            for (int i = 0; i < size; i++) {
                assertEquals((byte) i, before.currentStorage().get(i), "prior GPU consumer byte " + i);
                byte expected = i >= patchOffset && i < patchOffset + patchLength ? (byte) (102 + i - patchOffset) : (byte) i;
                assertEquals(expected, after.currentStorage().get(i), "updated consumer byte " + i);
            }
            assertEquals(2, patch.position());
            assertEquals(patchLength + 2, patch.limit());
            if (Boolean.getBoolean("metallum.hotpath.telemetry")
                    && MetalBackendOptimizations.DYNAMIC_UPLOAD_RANGE_COPY) {
                var afterCounters = com.metallum.client.metal.render.mtl.MetalHotPathTelemetry.snapshot();
                assertEquals(telemetryBefore.dynamicRangeCopies() + 1, afterCounters.dynamicRangeCopies());
                assertEquals(telemetryBefore.dynamicCopyBytesAvoided() + patchLength, afterCounters.dynamicCopyBytesAvoided());
            }
        }
    }

    @Test
    void oneAndTwoAttachmentReadback() {
        runRgbaAttachmentCount(1);
        runRgbaAttachmentCount(2);
    }

    @Test
    void positionedTextureUploadAndTransientRetirementPreserveData() {
        int bytes = WIDTH * HEIGHT * 4;
        int prefix = 37;
        ByteBuffer source = ByteBuffer.allocateDirect(prefix + bytes + 11);
        for (int i = 0; i < bytes; i++) source.put(prefix + i, (byte) (i * 31));
        source.position(prefix);
        source.limit(prefix + bytes + 11);
        try (MetalGpuTexture texture = (MetalGpuTexture) device.createTexture(
                () -> "positioned upload", TEXTURE_USAGE
                        | com.mojang.renderpearl.api.textures.GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 1)) {
            encoder.writeToTexture(texture, source, 0, 0, 0, 0, WIDTH, HEIGHT);
            ByteBuffer actual = readback(texture);
            for (int i = 0; i < bytes; i++) assertEquals((byte) (i * 31), actual.get(i), "texel byte " + i);
            assertEquals(prefix, source.position());
            assertEquals(prefix + bytes + 11, source.limit());
        }

        try (MetalGpuBuffer destination = (MetalGpuBuffer) device.createBuffer(
                () -> "transient copy", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, 64)) {
            var slice = encoder.transientMemory().uploadStaging(
                    source.slice(prefix, 64), 4, GpuBuffer.USAGE_COPY_SRC);
            MetalGpuBuffer transientBuffer = (MetalGpuBuffer) slice.buffer();
            assertDoesNotThrow(transientBuffer::nativeHandle);
            encoder.copyToBuffer(slice, destination.slice(0, 64));
            encoder.submit();
            assertTrue(transientBuffer.isClosed());
            assertThrows(IllegalStateException.class, transientBuffer::nativeHandle);
            assertThrows(IllegalStateException.class, transientBuffer::allocationIdentity);
            device.waitForSubmittedGpuWork();
            ByteBuffer copied = destination.currentStorage();
            for (int i = 0; i < 64; i++) assertEquals((byte) (i * 31), copied.get(i));
        }
    }

    @Test
    void shaderSourceReplacementRebuildsNativePipelineAfterResourceReload() {
        String name = "resource_reload_fragment";
        fragmentShaders.put(name, """
                #version 450
                layout(location=0) out vec4 color;
                void main() { color = vec4(1.0, 0.0, 0.0, 1.0); }
                """);
        RenderPipeline pipeline = pipeline(name, List.of(GpuFormat.RGBA8_UNORM), null, ColorTargetState.WRITE_ALL);
        try (MetalGpuTexture texture = (MetalGpuTexture) device.createTexture(
                "shader source replacement", TEXTURE_USAGE, GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 1)) {
            render(pipeline, List.of(texture), List.of(new Vector4f(0.0F)));
            ByteBuffer before = readback(texture);
            assertByteNear(before.get(0), 255, "original shader red");
            assertByteNear(before.get(4), 255, "original shader red at another pixel");
            assertByteNear(before.get(1), 0, "original shader green");

            // Keep the same RenderPearl shader identifier and pipeline key;
            // a resource-pack reload replaces the source behind that name.
            fragmentShaders.put(name, """
                    #version 450
                    layout(location=0) out vec4 color;
                    void main() { color = vec4(0.0, 1.0, 0.0, 1.0); }
                    """);
            device.clearPipelineCache();
            render(pipeline, List.of(texture), List.of(new Vector4f(0.0F)));
            ByteBuffer after = readback(texture);
            assertByteNear(after.get(0), 0, "replacement shader red");
            assertByteNear(after.get(4), 0, "replacement shader red at another pixel");
            assertByteNear(after.get(1), 255, "replacement shader green");
        }
    }

    @Test
    void oddWidthTextureUploadRepackagesRowsWithoutChangingSourceBounds() {
        int width = 5;
        int height = 3;
        int bytes = width * height * 4;
        int prefix = 13;
        int suffix = 9;
        ByteBuffer source = ByteBuffer.allocateDirect(prefix + bytes + suffix);
        for (int i = 0; i < bytes; i++) {
            source.put(prefix + i, (byte) (i * 37 + 11));
        }
        source.position(prefix);
        source.limit(prefix + bytes + suffix);
        int originalPosition = source.position();
        int originalLimit = source.limit();

        try (MetalGpuTexture texture = (MetalGpuTexture) device.createTexture(
                () -> "odd-width row-aligned upload",
                TEXTURE_USAGE | com.mojang.renderpearl.api.textures.GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM,
                width,
                height,
                1,
                1
        )) {
            encoder.writeToTexture(texture, source, 0, 0, 0, 0, width, height);
            assertEquals(originalPosition, source.position(), "upload must preserve source position");
            assertEquals(originalLimit, source.limit(), "upload must preserve source limit");

            ByteBuffer actual = readback(texture);
            for (int i = 0; i < bytes; i++) {
                assertEquals((byte) (i * 37 + 11), actual.get(i), "uploaded texel byte " + i);
            }
        }
    }

    @Test
    void rowPackerKeepsOddWidthRowsSeparateAtSixteenByteStride() {
        int sourceRowBytes = 5 * 4;
        int destinationRowBytes = 32;
        int height = 3;
        int prefix = 7;
        ByteBuffer source = ByteBuffer.allocateDirect(prefix + sourceRowBytes * height + 5);
        for (int i = 0; i < sourceRowBytes * height; i++) {
            source.put(prefix + i, (byte) (i * 19 + 3));
        }
        source.position(prefix);
        source.limit(prefix + sourceRowBytes * height);
        int originalPosition = source.position();
        int originalLimit = source.limit();

        ByteBuffer packed = MetalCommandEncoder.packTextureUploadRows(
                source, sourceRowBytes, destinationRowBytes, height);
        try {
            assertEquals(destinationRowBytes * height, packed.remaining());
            for (int row = 0; row < height; row++) {
                for (int column = 0; column < sourceRowBytes; column++) {
                    assertEquals((byte) ((row * sourceRowBytes + column) * 19 + 3),
                            packed.get(row * destinationRowBytes + column),
                            "row " + row + " byte " + column);
                }
                for (int padding = sourceRowBytes; padding < destinationRowBytes; padding++) {
                    assertEquals(0, packed.get(row * destinationRowBytes + padding),
                            "row " + row + " padding " + padding);
                }
            }
            assertEquals(originalPosition, source.position());
            assertEquals(originalLimit, source.limit());
        } finally {
            MemoryUtil.memFree(packed);
        }
    }

    @Test
    void textureUploadRejectsShortSourceAndOverflowBeforeEncoding() {
        try (MetalGpuTexture texture = (MetalGpuTexture) device.createTexture(
                () -> "texture upload validation",
                TEXTURE_USAGE | com.mojang.renderpearl.api.textures.GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM,
                1,
                1,
                1,
                1
        )) {
            ByteBuffer shortSource = ByteBuffer.allocateDirect(3);
            shortSource.position(1);
            int originalPosition = shortSource.position();
            int originalLimit = shortSource.limit();
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.writeToTexture(texture, shortSource, 0, 0, 0, 0, 1, 1));
            assertEquals(originalPosition, shortSource.position());
            assertEquals(originalLimit, shortSource.limit());

            ByteBuffer tinySource = ByteBuffer.allocateDirect(1);
            assertThrows(ArithmeticException.class,
                    () -> encoder.writeToTexture(texture, tinySource, 0, 0, 0, 0, Integer.MAX_VALUE, 1));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 4, 1528541})
    void sodiumCompactVerticesKeepTheirLayoutThroughIndexedMultiDraw(int baseVertex) {
        long rebasesBefore = com.metallum.client.metal.render.mtl.MetalHotPathTelemetry.indexedVertexRebases();
        long batchesBefore = com.metallum.client.metal.render.mtl.MetalHotPathTelemetry.indexedRebaseBatches();
        long avoidedBefore = com.metallum.client.metal.render.mtl.MetalHotPathTelemetry.indexedRebaseRestoresAvoided();
        String name = "sodium_compact_vertex_multidraw";
        vertexShaders.put(name, """
                #version 450
                layout(location=0) in uvec2 a_Position;
                layout(location=1) in vec4 a_Color;
                layout(location=2) in uvec2 a_TexCoord;
                layout(location=3) in uvec4 a_LightAndData;
                layout(location=4) in vec4 instanceColor;
                layout(location=0) out vec4 vertexColor;
                layout(std140) uniform u_Globals { vec4 transform; };
                layout(std140) uniform Guard1 { uvec4 guard1; };
                layout(std140) uniform Guard2 { uvec4 guard2; };
                layout(std140) uniform Guard3 { uvec4 guard3; };
                layout(push_constant) uniform PC {
                    vec3 regionOffset;
                    int currentTime;
                    uint regionId;
                };
                void main() {
                    uvec3 hi = (uvec3(a_Position.x) >> uvec3(0, 10, 20)) & 1023u;
                    uvec3 lo = (uvec3(a_Position.y) >> uvec3(0, 10, 20)) & 1023u;
                    vec3 position = vec3((hi << 10u) | lo) * (32.0 / 1048576.0) - 8.0;
                    gl_Position = vec4((position.xy + regionOffset.xy) * transform.xy + transform.zw, 0, 1);
                    bool dataValid = all(equal(a_LightAndData.xy, uvec2(248)))
                        && a_LightAndData.z == 79u
                        && (a_LightAndData.w == 37u || a_LightAndData.w == 199u)
                        && all(lessThanEqual(a_TexCoord, uvec2(65535)))
                        && all(equal(guard1, uvec4(1, 2, 3, 4)))
                        && all(equal(guard2, uvec4(5, 6, 7, 8)))
                        && all(equal(guard3, uvec4(9, 10, 11, 12)))
                        && currentTime == 37 && regionId == 54321u;
                    vertexColor = dataValid ? a_Color * instanceColor : vec4(0, 0, 1, 1);
                }
                """);
        fragmentShaders.put(name, """
                #version 450
                layout(location=0) in vec4 vertexColor;
                layout(location=0) out vec4 color;
                void main() { color = vertexColor; }
                """);
        var format = net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex.VERTEX_FORMAT;
        assertEquals(20, format.getVertexSize());
        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation("metallum_test/" + name)
                .withVertexShader("metallum_test/" + name)
                .withFragmentShader("metallum_test/" + name)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withVertexBinding(0, format).withCull(false)
                .withVertexBinding(1, com.mojang.renderpearl.api.vertex.VertexFormat.builder(1)
                        .addAttribute("instanceColor", GpuFormat.RGBA8_UNORM).build())
                .withPushConstantSize(20)
                .withBindGroupLayout(com.mojang.renderpearl.api.pipeline.BindGroupLayout.builder()
                        .withUniform("u_Globals", com.mojang.renderpearl.api.pipeline.UniformType.UNIFORM_BUFFER)
                        .withUniform("Guard1", com.mojang.renderpearl.api.pipeline.UniformType.UNIFORM_BUFFER)
                        .withUniform("Guard2", com.mojang.renderpearl.api.pipeline.UniformType.UNIFORM_BUFFER)
                        .withUniform("Guard3", com.mojang.renderpearl.api.pipeline.UniformType.UNIFORM_BUFFER).build())
                .withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM,
                        ColorTargetState.WRITE_ALL)).build();
        int prefix = 32;
        int vertexBytes = Math.multiplyExact(baseVertex + 8, 20);
        ByteBuffer vertices = MemoryUtil.memCalloc(prefix + vertexBytes).order(ByteOrder.nativeOrder());
        ByteBuffer indices = MemoryUtil.memAlloc(11 * Integer.BYTES).order(ByteOrder.nativeOrder());
        ByteBuffer parameters = MemoryUtil.memAlloc(11 * Integer.BYTES).order(ByteOrder.nativeOrder());
        List<MetalGpuTexture> textures = createTextures(List.of(GpuFormat.RGBA8_UNORM), name);
        try {
            var compact = new net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex();
            for (int quad = 0; quad < 2; quad++) {
                var points = net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder.Vertex.uninitializedQuad();
                for (int i = 0; i < 4; i++) {
                    var point = points[i];
                    point.x = quad * 8 + (i == 1 || i == 2 ? 8 : 0);
                    point.y = i >= 2 ? 16 : 0;
                    point.color = quad == 0 ? 0xff00ff00 : 0xff0000ff;
                    point.ao = 1;
                    point.u = 0.25f;
                    point.v = 0.5f;
                    point.light = 0x00f000f0;
                }
                compact.getEncoder().write(MemoryUtil.memAddress(vertices) + prefix + (baseVertex + quad * 4L) * 20,
                        79, points, quad == 0 ? 37 : 199);
            }
            for (int i = 0; i < 5; i++) indices.putInt(999);
            for (int i : new int[]{0, 1, 2, 2, 3, 0}) indices.putInt(i);
            indices.flip();
            // Vulkan's interleaved record is firstIndex, indexCount, vertexOffset.
            parameters.putInt(999).putInt(999);
            parameters.putInt(5).putInt(6).putInt(baseVertex);
            parameters.putInt(5).putInt(6).putInt(baseVertex + 4);
            parameters.putInt(5).putInt(0).putInt(Integer.MIN_VALUE); // inactive records never constrain admission
            parameters.flip().position(2 * Integer.BYTES);
            ByteBuffer instanceColors = ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder());
            instanceColors.putInt(0xffff0000).putInt(0xffff0000).putInt(0xffffffff).flip();
            try (GpuBuffer vertexBuffer = device.createBuffer(() -> name + " vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_SRC, vertices);
                 GpuBuffer indexBuffer = device.createBuffer(() -> name + " indices", GpuBuffer.USAGE_INDEX, indices);
                 GpuBuffer instanceBuffer = device.createBuffer(() -> name + " instance colors", GpuBuffer.USAGE_VERTEX, instanceColors)) {
                for (boolean multiDraw : List.of(false, true)) {
                    try (PassWithViews pass = createPass(textures, null, false)) {
                        pass.pass().setPipeline(pipeline);
                        assertEquals(5, device.getOrCompilePipeline(pipeline).firstAvailableVertexBufferSlot());
                        String[] names = {"u_Globals", "Guard1", "Guard2", "Guard3"};
                        for (int uniform = 0; uniform < names.length; uniform++) {
                            try (var mapped = encoder.transientMemory().allocateGpuMapped(16, 256, GpuBuffer.USAGE_UNIFORM)) {
                                if (uniform == 0) mapped.data().putFloat(0.125f).putFloat(0.125f).putFloat(-1.5f).putFloat(-1.75f);
                                else for (int i = 0; i < 4; i++) mapped.data().putInt((uniform - 1) * 4 + i + 1);
                                pass.pass().setUniform(names[uniform], mapped.slice());
                            }
                        }
                        ByteBuffer constants = ByteBuffer.allocateDirect(20).order(ByteOrder.nativeOrder());
                        constants.putFloat(4).putFloat(6).putFloat(0).putInt(37).putInt(54321).flip();
                        pass.pass().pushConstants(constants);
                        pass.pass().setVertexBuffer(0, vertexBuffer.slice(prefix, vertexBytes));
                        pass.pass().setVertexBuffer(1, instanceBuffer.slice());
                        pass.pass().setIndexBuffer(indexBuffer, IndexType.INT);
                        if (multiDraw) {
                            pass.pass().multiDrawIndexed(parameters.asIntBuffer(), 1, 2, 3);
                            if (baseVertex <= 4) {
                                // This non-indexed draw reads the original binding.
                                // Instance zero is blue, so the green triangle
                                // must blacken this one pixel after batch restore.
                                pass.pass().enableScissor(WIDTH * 3 / 8, HEIGHT / 2, 1, 1);
                                pass.pass().draw(3, 1, baseVertex, 0);
                            }
                        }
                        else {
                            pass.pass().drawIndexed(6, 1, 5, baseVertex, 2);
                            pass.pass().drawIndexed(6, 1, 5, baseVertex + 4, 2);
                        }
                        encoder.submitRenderPass();
                    }
                    encoder.submit();
                    device.waitForSubmittedGpuWork();
                    // Verify the uploaded bytes after drawing, so this proof
                    // cannot hide an upload/fetch ordering failure with a
                    // readback barrier before the draw under test.
                    try (MetalGpuBuffer input = (MetalGpuBuffer) device.createBuffer(() -> "compact input proof",
                            GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, 8 * 20)) {
                        encoder.copyToBuffer(vertexBuffer.slice(prefix + (long) baseVertex * 20, 8 * 20), input.slice());
                        encoder.submit();
                        device.waitForSubmittedGpuWork();
                        ByteBuffer uploaded = input.currentStorage();
                        for (int i = 0; i < 8 * 20; i++) {
                            assertEquals(vertices.get(prefix + baseVertex * 20 + i), uploaded.get(i), "compact uploaded byte " + i);
                        }
                    }
                    ByteBuffer pixels = readback(textures.getFirst());
                    for (int y = 0; y < HEIGHT; y++) {
                        for (int x = 0; x < WIDTH; x++) {
                            int pixel = (y * WIDTH + x) * 4;
                            boolean restoreProbe = multiDraw && baseVertex <= 4 && x == WIDTH * 3 / 8 && y == HEIGHT / 2;
                            assertByteNear(pixels.get(pixel), x < WIDTH / 2 ? 0 : 255, "compact vertex red, multiDraw=" + multiDraw);
                            assertByteNear(pixels.get(pixel + 1), x < WIDTH / 2 && !restoreProbe ? 255 : 0, "compact vertex green, multiDraw=" + multiDraw);
                            assertByteNear(pixels.get(pixel + 2), 0, "compact vertex blue, multiDraw=" + multiDraw);
                        }
                    }
                }
            }
        } finally {
            closeTextures(textures);
            MemoryUtil.memFree(parameters);
            MemoryUtil.memFree(indices);
            MemoryUtil.memFree(vertices);
        }
        if (Boolean.getBoolean("metallum.hotpath.telemetry")) {
            assertTrue(com.metallum.client.metal.render.mtl.MetalHotPathTelemetry.indexedVertexRebases() >= rebasesBefore + 4,
                    "both direct and multi-draw must exercise the admitted vertex-buffer rebase");
            int admittedBatches = MetalBackendOptimizations.BATCH_INDEXED_VERTEX_REBASE ? 1 : 0;
            assertEquals(batchesBefore + admittedBatches,
                    com.metallum.client.metal.render.mtl.MetalHotPathTelemetry.indexedRebaseBatches());
            assertEquals(avoidedBefore + admittedBatches,
                    com.metallum.client.metal.render.mtl.MetalHotPathTelemetry.indexedRebaseRestoresAvoided());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, -4})
    void indexedBatchMixedBaseOffsetsKeepOriginalBindings(int secondBase) {
        String name = "indexed_batch_mixed_bases";
        RenderPipeline pipeline = indexedColorPipeline(name);
        ByteBuffer indices = ByteBuffer.allocateDirect(12 * Integer.BYTES).order(ByteOrder.nativeOrder());
        for (int value : new int[]{0, 1, 2, 2, 3, 0}) indices.putInt(value);
        for (int value : new int[]{0, 1, 2, 2, 3, 0}) indices.putInt(value - secondBase);
        indices.flip();
        var parameters = ByteBuffer.allocateDirect(9 * Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer();
        parameters.put(999).put(999).put(999);
        parameters.put(0).put(6).put(4);
        parameters.put(6).put(6).put(secondBase);
        parameters.flip().position(3);
        long batchesBefore = com.metallum.client.metal.render.mtl.MetalHotPathTelemetry.indexedRebaseBatches();
        List<MetalGpuTexture> textures = createTextures(List.of(GpuFormat.RGBA8_UNORM), name);
        try (GpuBuffer vertices = device.createBuffer(() -> name + " vertices", GpuBuffer.USAGE_VERTEX, indexedColorVertices(0));
             GpuBuffer indexBuffer = device.createBuffer(() -> name + " indices", GpuBuffer.USAGE_INDEX, indices)) {
            try (PassWithViews pass = createPass(textures, null, false)) {
                pass.pass().setPipeline(pipeline);
                pass.pass().setVertexBuffer(0, vertices.slice());
                pass.pass().setIndexBuffer(indexBuffer, IndexType.INT);
                pass.pass().multiDrawIndexed(parameters, 1, 0, 2);
                encoder.submitRenderPass();
            }
            encoder.submit();
            device.waitForSubmittedGpuWork();
            ByteBuffer pixels = readback(textures.getFirst());
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    int pixel = (y * WIDTH + x) * 4;
                    assertByteNear(pixels.get(pixel), x < WIDTH / 2 ? 0 : 255, "mixed base red");
                    assertByteNear(pixels.get(pixel + 1), x < WIDTH / 2 ? 255 : 0, "mixed base green");
                    assertByteNear(pixels.get(pixel + 2), 0, "mixed base blue");
                }
            }
            assertEquals(batchesBefore, com.metallum.client.metal.render.mtl.MetalHotPathTelemetry.indexedRebaseBatches(),
                    "a mixed zero/negative-base batch must use the per-draw fallback");
        } finally {
            closeTextures(textures);
        }
    }

    @Test
    void indexedStageInShaderKeepsObservableVertexIndex() {
        String name = "indexed_stage_in_vertex_index";
        vertexShaders.put(name, """
                #version 450
                layout(location=0) in vec2 position;
                layout(location=0) out vec4 vertexColor;
                void main() {
                    gl_Position = vec4(position, 0, 1);
                    vertexColor = gl_VertexIndex >= 3 ? vec4(0, 1, 0, 1) : vec4(0, 0, 1, 1);
                }
                """);
        fragmentShaders.put(name, """
                #version 450
                layout(location=0) in vec4 vertexColor;
                layout(location=0) out vec4 color;
                void main() { color = vertexColor; }
                """);
        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation("metallum_test/" + name)
                .withVertexShader("metallum_test/" + name)
                .withFragmentShader("metallum_test/" + name)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
                .withVertexBinding(0, com.mojang.renderpearl.api.vertex.VertexFormat.builder(0)
                        .addAttribute("position", GpuFormat.RG32_FLOAT).build())
                .withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM,
                        ColorTargetState.WRITE_ALL)).build();
        ByteBuffer vertices = ByteBuffer.allocateDirect(12 * Float.BYTES).order(ByteOrder.nativeOrder());
        for (float value : new float[]{0, 0, 0, 0, 0, 0, -1, -1, 3, -1, -1, 3}) vertices.putFloat(value);
        vertices.flip();
        ByteBuffer indices = ByteBuffer.allocateDirect(3 * Short.BYTES).order(ByteOrder.nativeOrder());
        indices.putShort((short) 0).putShort((short) 1).putShort((short) 2).flip();
        long rebasesBefore = com.metallum.client.metal.render.mtl.MetalHotPathTelemetry.indexedVertexRebases();
        List<MetalGpuTexture> textures = createTextures(List.of(GpuFormat.RGBA8_UNORM), name);
        try (GpuBuffer vertexBuffer = device.createBuffer(() -> name + " vertices", GpuBuffer.USAGE_VERTEX, vertices);
             GpuBuffer indexBuffer = device.createBuffer(() -> name + " indices", GpuBuffer.USAGE_INDEX, indices)) {
            try (PassWithViews pass = createPass(textures, null, false)) {
                pass.pass().setPipeline(pipeline);
                assertFalse(device.getOrCompilePipeline(pipeline).indexedVertexRebaseSafe());
                pass.pass().setVertexBuffer(0, vertexBuffer.slice());
                pass.pass().setIndexBuffer(indexBuffer, IndexType.SHORT);
                pass.pass().drawIndexed(3, 1, 0, 3, 0);
                var parameters = ByteBuffer.allocateDirect(6 * Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer();
                parameters.put(0).put(3).put(3).put(0).put(3).put(3).flip();
                pass.pass().multiDrawIndexed(parameters, 1, 0, 2);
                encoder.submitRenderPass();
            }
            encoder.submit();
            device.waitForSubmittedGpuWork();
            ByteBuffer pixels = readback(textures.getFirst());
            for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
                assertByteNear(pixels.get(pixel * 4), 0, "vertex-index observer red");
                assertByteNear(pixels.get(pixel * 4 + 1), 255, "vertex-index observer green");
                assertByteNear(pixels.get(pixel * 4 + 2), 0, "vertex-index observer blue");
            }
            assertEquals(rebasesBefore, com.metallum.client.metal.render.mtl.MetalHotPathTelemetry.indexedVertexRebases());
        } finally {
            closeTextures(textures);
        }
    }

    @Test
    void renderPearlIndirectDrawsPreserveOffsetsAndFirstInstance() {
        String name = "vanilla_indirect";
        vertexShaders.put(name, """
                #version 450
                layout(location=0) flat out int instance;
                void main() {
                    vec2 positions[9] = vec2[](
                        vec2(0), vec2(0), vec2(0),
                        vec2(-1, -1), vec2(1, -1), vec2(1, 1),
                        vec2(-1, -1), vec2(1, 1), vec2(-1, 1)
                    );
                    instance = gl_InstanceIndex;
                    vec2 p = positions[gl_VertexIndex];
                    gl_Position = vec4(p.x * 0.5 + (instance == 2 ? -0.5 : 0.5), p.y, 0, 1);
                }
                """);
        fragmentShaders.put(name, """
                #version 450
                layout(location=0) flat in int instance;
                layout(location=0) out vec4 color;
                void main() {
                    color = instance == 2 ? vec4(0, 1, 0, 1)
                          : instance == 5 ? vec4(1, 0, 0, 1) : vec4(0, 0, 1, 1);
                }
                """);
        ColorTargetState target = new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM,
                ColorTargetState.WRITE_ALL);
        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation("metallum_test/" + name)
                .withVertexShader("metallum_test/" + name)
                .withFragmentShader("metallum_test/" + name)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false).withColorTargetState(0, target).build();
        // Exercise RenderPearl's actual feature/argument validation, not just a
        // direct backend call that could hide contradictory capability flags.
        FrontendRenderPipeline frontendPipeline = new FrontendRenderPipeline(name,
                device.getOrCompilePipeline(pipeline), pipeline.getVertexFormatBindings(),
                new Object2IntOpenHashMap<>(), List.of(), List.of(target), false, 0);
        FrontendCommandEncoder frontend = new FrontendCommandEncoder(null, device, encoder);
        ByteBuffer indices = ByteBuffer.allocateDirect(7 * Short.BYTES).order(ByteOrder.nativeOrder());
        indices.putShort((short) 999);
        for (int index = 7; index <= 12; index++) indices.putShort((short) index);
        indices.flip();
        List<MetalGpuTexture> textures = createTextures(List.of(GpuFormat.RGBA8_UNORM), name);
        try (MetalGpuTextureView view = new MetalGpuTextureView(textures.get(0), 0, 1);
             MetalGpuBuffer indexBuffer = (MetalGpuBuffer) device.createBuffer(
                     () -> "indirect indices", GpuBuffer.USAGE_INDEX, indices)) {
            for (boolean indexed : List.of(true, false)) {
                int stride = indexed ? 20 : 16;
                int prefix = 16;
                ByteBuffer args = ByteBuffer.allocateDirect(prefix + 2 * stride).order(ByteOrder.nativeOrder());
                args.position(prefix);
                for (int firstInstance : List.of(2, 5)) {
                    args.putInt(6).putInt(1).putInt(indexed ? 1 : 3);
                    if (indexed) args.putInt(-4);
                    args.putInt(firstInstance);
                }
                args.flip();
                try (GpuBuffer commands = device.createBuffer(
                        () -> "indirect arguments", GpuBuffer.USAGE_INDIRECT_PARAMETERS, args)) {
                    try (RenderPass pass = frontend.createRenderPass(() -> "RenderPearl indirect",
                            view, Optional.of(new Vector4f(0)))) {
                        pass.setPipeline(frontendPipeline);
                        pass.setIndexBuffer(indexBuffer, IndexType.SHORT);
                        if (indexed) pass.drawIndexedIndirect(commands.slice(prefix, 2L * stride), 2);
                        else pass.drawIndirect(commands.slice(prefix, 2L * stride), 2);
                    }
                    frontend.submit();
                    device.waitForSubmittedGpuWork();
                    ByteBuffer pixels = readback(textures.get(0));
                    for (int y = 0; y < HEIGHT; y++) {
                        for (int x = 0; x < WIDTH; x++) {
                            int pixel = (y * WIDTH + x) * 4;
                            assertByteNear(pixels.get(pixel), x < WIDTH / 2 ? 0 : 255, "indirect red");
                            assertByteNear(pixels.get(pixel + 1), x < WIDTH / 2 ? 255 : 0, "indirect green");
                            assertByteNear(pixels.get(pixel + 2), 0, "indirect blue");
                        }
                    }
                }
            }
        } finally {
            closeTextures(textures);
        }
    }

    @Test
    void deferredColorStoreClearIsAttachmentLocal() {
        long submitsBefore = Boolean.getBoolean("metallum.test.mrtMetal4Commands")
                ? MetalNativeBridge.metallum_metal4_main_renderer_stats()[2] : -1;
        try {
            for (String abi : List.of("v2", "v3")) {
                MetalCommandEncoder.setRenderPassAbiModeForTests(abi);
                for (int count = 1; count <= 8; count++) {
                    List<MetalGpuTexture> textures = createTextures(
                            java.util.Collections.nCopies(count, GpuFormat.RGBA8_UNORM), "local-store");
                    List<MetalGpuTextureView> views = new ArrayList<>();
                    try {
                        for (MetalGpuTexture texture : textures) {
                            views.add(new MetalGpuTextureView(texture, 0, 1));
                        }
                        // Retain a hole between live slots without renumbering the last slot.
                        int hole = count > 2 ? 1 : -1;
                        RenderPassDescriptor.Builder first = RenderPassDescriptor.builder(() -> "store first");
                        RenderPassDescriptor.Builder second = RenderPassDescriptor.builder(() -> "store successor");
                        for (int slot = 0; slot < count; slot++) {
                            if (slot == hole) {
                                first.withUnusedColorAttachment();
                                second.withUnusedColorAttachment();
                            } else {
                                first.withColorAttachment(views.get(slot), Optional.of(new Vector4f(1, 0, 0, 1)));
                                second.withColorAttachment(views.get(slot), slot == count - 1
                                        ? Optional.of(new Vector4f(0, 1, 0, 1)) : Optional.empty());
                            }
                        }
                        RenderGraphTelemetry.reset();
                        encoder.createRenderPass(first.build());
                        encoder.submitRenderPass();
                        encoder.createRenderPass(second.build());
                        encoder.submitRenderPass();
                        assertEquals(abi.equals("v3") && MetalCommandEncoder.DEFERRED_COLOR_STORE
                                        ? (long) WIDTH * HEIGHT * 4 : 0L,
                                RenderGraphTelemetry.snapshot().get("colorStoreKilledBytes"),
                                abi + ": only the cleared slot may discard its predecessor, count=" + count);
                        encoder.submit();
                        device.waitForSubmittedGpuWork();
                        for (int slot = 0; slot < count; slot++) {
                            if (slot == hole) continue;
                            ByteBuffer pixels = readback(textures.get(slot));
                            for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
                                assertByteNear(pixels.get(pixel * 4), slot == count - 1 ? 0 : 255, "preserved red");
                                assertByteNear(pixels.get(pixel * 4 + 1), slot == count - 1 ? 255 : 0, "clear green");
                            }
                        }
                    } finally {
                        views.forEach(MetalGpuTextureView::close);
                        closeTextures(textures);
                    }
                }
            }
        } finally {
            MetalCommandEncoder.setRenderPassAbiModeForTests("auto");
        }
        if (submitsBefore >= 0) {
            long[] stats = MetalNativeBridge.metallum_metal4_main_renderer_stats();
            assertEquals(1L, stats[0], "MTL4 queue remains active");
            assertTrue(stats[2] > submitsBefore, "MRT readbacks must submit actual MTL4 command buffers");
        }
    }

    @Test
    void fourAttachmentReadback() {
        runRgbaAttachmentCount(4);
    }

    @Test
    void deferredColorStorePreservesOtherMipAndDistinctViews() {
        try (MetalGpuTexture texture = (MetalGpuTexture) device.createTexture(
                "store mip views", TEXTURE_USAGE, GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 2);
             MetalGpuTextureView base = new MetalGpuTextureView(texture, 0, 1);
             MetalGpuTextureView mip = new MetalGpuTextureView(texture, 1, 1);
             MetalGpuTextureView anotherMipView = new MetalGpuTextureView(texture, 1, 1)) {
            RenderGraphTelemetry.reset();
            encoder.createRenderPass(RenderPassDescriptor.builder(() -> "base mip")
                    .withColorAttachment(base, Optional.of(new Vector4f(1, 0, 0, 1))).build());
            encoder.submitRenderPass();
            encoder.createRenderPass(RenderPassDescriptor.builder(() -> "other mip")
                    .withColorAttachment(mip, Optional.of(new Vector4f(0, 1, 0, 1))).build());
            encoder.submitRenderPass();
            assertEquals(0L, RenderGraphTelemetry.snapshot().get("colorStoreKilledBytes"));
            encoder.createRenderPass(RenderPassDescriptor.builder(() -> "distinct view, same mip")
                    .withColorAttachment(anotherMipView, Optional.of(new Vector4f(0, 0, 1, 1))).build());
            encoder.submitRenderPass();
            assertEquals(0L, RenderGraphTelemetry.snapshot().get("colorStoreKilledBytes"),
                    "unproven view equivalence conservatively stores");
            encoder.submit();
            device.waitForSubmittedGpuWork();
            ByteBuffer basePixels = readback(texture);
            for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
                assertByteNear(basePixels.get(pixel * 4), 255, "other mip must preserve base red");
                assertByteNear(basePixels.get(pixel * 4 + 2), 0, "other mip must preserve base blue");
            }
            ByteBuffer mipPixels = readback(texture, 1);
            for (int pixel = 0; pixel < (WIDTH / 2) * (HEIGHT / 2); pixel++) {
                assertByteNear(mipPixels.get(pixel * 4), 0, "mip red");
                assertByteNear(mipPixels.get(pixel * 4 + 2), 255, "mip blue");
            }
        }
    }

    @Test
    void fullTextureClearIncludesAllMipsBeforeAnotherMipPass() {
        try (MetalGpuTexture color = (MetalGpuTexture) device.createTexture(
                "clear all mips", TEXTURE_USAGE, GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 2);
             MetalGpuTexture depth = (MetalGpuTexture) device.createTexture(
                "clear all depth mips", TEXTURE_USAGE, GpuFormat.D32_FLOAT, WIDTH, HEIGHT, 1, 2);
             MetalGpuTextureView colorMip = new MetalGpuTextureView(color, 1, 1);
             MetalGpuTextureView depthMip = new MetalGpuTextureView(depth, 1, 1)) {
            encoder.clearColorAndDepthTextures(color, new Vector4f(0, 0, 1, 1), depth, 0.75);
            encoder.createRenderPass(RenderPassDescriptor.builder(() -> "replace only mip 1")
                    .withColorAttachment(colorMip, Optional.of(new Vector4f(1, 0, 0, 1)))
                    .withDepthAttachment(depthMip, java.util.OptionalDouble.of(0.25)).build());
            encoder.submitRenderPass();
            for (int mip = 0; mip < 2; mip++) {
                ByteBuffer colors = readback(color, mip);
                ByteBuffer depths = readback(depth, mip);
                for (int offset = 0; offset < colors.remaining(); offset += 4) {
                    assertByteNear(colors.get(offset + 2), mip == 0 ? 255 : 0, "other mip keeps full clear");
                    assertEquals(mip == 0 ? 0.75F : 0.25F, depths.getFloat(offset), 0.0001F);
                }
            }
            encoder.clearColorAndDepthTextures(color, new Vector4f(0, 1, 0, 1), depth, 0.5);
            assertByteNear(readback(color, 1).get(1), 255, "standalone full clear reaches mip 1");
            assertEquals(0.5F, readback(depth, 1).getFloat(0), 0.0001F);
        }
    }

    @Test
    void regionalClearSelectsMipAndPreservesOutsidePixels() {
        try (MetalGpuTexture color = (MetalGpuTexture) device.createTexture(
                "regional mip color", TEXTURE_USAGE, GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 2);
             MetalGpuTexture depth = (MetalGpuTexture) device.createTexture(
                "regional mip depth", TEXTURE_USAGE, GpuFormat.D32_FLOAT, WIDTH, HEIGHT, 1, 2)) {
            encoder.clearColorAndDepthTextures(color, new Vector4f(0, 0, 1, 1), depth, 0.75);
            encoder.clearColorAndDepthTextures(color, new Vector4f(1, 0, 0, 1), depth, 0.25,
                    1, 0, 3, 1, 1);
            for (int mip = 0; mip < 2; mip++) {
                ByteBuffer colors = readback(color, mip);
                ByteBuffer depths = readback(depth, mip);
                for (int y = 0; y < color.getHeight(mip); y++) {
                    for (int x = 0; x < color.getWidth(mip); x++) {
                        boolean inside = mip == 1 && y == 0 && x >= 1 && x < 4;
                        int offset = (y * color.getWidth(mip) + x) * 4;
                        assertByteNear(colors.get(offset), inside ? 255 : 0, "selected mip region");
                        assertByteNear(colors.get(offset + 2), inside ? 0 : 255, "outside clear preserved");
                        assertEquals(inside ? 0.25F : 0.75F, depths.getFloat(offset), 0.0001F);
                    }
                }
            }
        }
    }

    @Test
    void pendingFullClearPrecedesRegionalClear() {
        int usage = TEXTURE_USAGE | com.mojang.renderpearl.api.textures.GpuTexture.USAGE_COPY_DST;
        try (MetalGpuTexture color = (MetalGpuTexture) device.createTexture(
                     "regional color", usage, GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 1);
             MetalGpuTexture depth = (MetalGpuTexture) device.createTexture(
                     "regional depth", usage, GpuFormat.D32_FLOAT, WIDTH, HEIGHT, 1, 1)) {
            FrontendCommandEncoder frontend = new FrontendCommandEncoder(null, device, encoder);
            frontend.clearColorAndDepthTextures(color, new Vector4f(0, 0, 1, 1), depth, 0.75);
            frontend.clearColorAndDepthTextures(color, new Vector4f(1, 0, 0, 1), depth, 0.25,
                    WIDTH / 4, 1, WIDTH / 2, HEIGHT - 2, 0);
            frontend.submit();
            device.waitForSubmittedGpuWork();
            ByteBuffer colors = readback(color);
            ByteBuffer depths = readback(depth);
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    boolean inside = x >= WIDTH / 4 && x < 3 * WIDTH / 4 && y >= 1 && y < HEIGHT - 1;
                    int offset = (y * WIDTH + x) * 4;
                    assertByteNear(colors.get(offset), inside ? 255 : 0, "regional red");
                    assertByteNear(colors.get(offset + 2), inside ? 0 : 255, "preserved blue");
                    assertEquals(inside ? 0.25F : 0.75F, depths.getFloat(offset), 0.0001F, "regional depth");
                }
            }
        }
    }

    @Test
    void deferredColorStorePreservesReorderedSlotsAndPartialArea() {
        List<MetalGpuTexture> textures = createTextures(
                List.of(GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM), "store reordered");
        try (MetalGpuTextureView a = new MetalGpuTextureView(textures.get(0), 0, 1);
             MetalGpuTextureView b = new MetalGpuTextureView(textures.get(1), 0, 1)) {
            RenderGraphTelemetry.reset();
            encoder.createRenderPass(RenderPassDescriptor.builder(() -> "original slots")
                    .withColorAttachment(a, Optional.of(new Vector4f(1, 0, 0, 1)))
                    .withColorAttachment(b, Optional.of(new Vector4f(0, 1, 0, 1))).build());
            encoder.submitRenderPass();
            encoder.createRenderPass(RenderPassDescriptor.builder(() -> "reordered slots")
                    .withColorAttachment(b, Optional.of(new Vector4f(0, 0, 1, 1)))
                    .withColorAttachment(a, Optional.empty()).build());
            encoder.submitRenderPass();
            assertEquals(0L, RenderGraphTelemetry.snapshot().get("colorStoreKilledBytes"));
            encoder.createRenderPass(RenderPassDescriptor.builder(() -> "partial render area")
                    .withColorAttachment(b, Optional.of(new Vector4f(0, 1, 0, 1)))
                    .withColorAttachment(a, Optional.empty())
                    .withRenderArea(new RenderPass.RenderArea(1, 1, WIDTH - 2, HEIGHT - 2)).build());
            encoder.submitRenderPass();
            assertEquals(0L, RenderGraphTelemetry.snapshot().get("colorStoreKilledBytes"),
                    "partial area supplies no full-coverage discard proof");
            encoder.submit();
            device.waitForSubmittedGpuWork();
            ByteBuffer pixels = readback(textures.get(0));
            for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
                assertByteNear(pixels.get(pixel * 4), 255, "reordered, uncleared attachment survives");
            }
        } finally {
            closeTextures(textures);
        }
    }

    @Test
    void layeredColorAttachmentIsRejectedBeforeEncoding() {
        try (MetalGpuTexture texture = (MetalGpuTexture) device.createTexture(
                "layered store", TEXTURE_USAGE, GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 2, 1);
             MetalGpuTextureView view = new MetalGpuTextureView(texture, 0, 1)) {
            assertThrows(UnsupportedOperationException.class, () -> encoder.createRenderPass(
                    RenderPassDescriptor.builder(() -> "unsupported layered clear")
                            .withColorAttachment(view, Optional.of(new Vector4f(1))).build()));
        }
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
                com.mojang.renderpearl.api.textures.GpuTexture.USAGE_RENDER_ATTACHMENT
                        | com.mojang.renderpearl.api.textures.GpuTexture.USAGE_COPY_SRC,
                GpuFormat.D32_FLOAT, WIDTH, HEIGHT, 1, 1)) {
            RenderPassDescriptor.Builder descriptor = RenderPassDescriptor.builder(() -> "depth+MRT integration");
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
            MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor.build());
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
            RenderPassDescriptor.Builder descriptor = RenderPassDescriptor.builder(() -> "resize integration");
            try (MetalGpuTextureView view = new MetalGpuTextureView(resized, 0, 1)) {
                descriptor.withColorAttachment(view, Optional.of(new Vector4f(0.0F)));
                descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, resizedWidth, resizedHeight));
                MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor.build());
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
        assertTrue(mismatch.getMessage().contains("Failed to translate RenderPearl pipeline"));
        assertNotNull(mismatch.getCause());
        assertTrue(mismatch.getCause().getMessage().contains("Fragment output location mismatch"));
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
        MetalCompiledRenderPipeline compiled = device.getOrCompilePipeline(pipeline);
        assertFalse(compiled.isValid(), "integer output with normalized float target must not create a valid PSO");
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

    private PassWithViews createPass(
            List<MetalGpuTexture> textures,
            List<Vector4f> clearColors,
            boolean load
    ) {
        RenderPassDescriptor.Builder descriptor = RenderPassDescriptor.builder(() -> "Java MRT backend integration");
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
        return new PassWithViews((MetalRenderPass) encoder.createRenderPass(descriptor.build()), views);
    }

    private ByteBuffer readback(MetalGpuTexture texture) {
        return readback(texture, 0);
    }

    private ByteBuffer readback(MetalGpuTexture texture, int mipLevel) {
        int size = texture.getWidth(mipLevel) * texture.getHeight(mipLevel) * texture.pixelSize();
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "MRT readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                size
        )) {
            encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
            }, mipLevel);
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
