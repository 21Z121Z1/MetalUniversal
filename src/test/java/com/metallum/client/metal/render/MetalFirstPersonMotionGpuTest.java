package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Actual source-pass bindings -> transactional hand history -> packaged shader -> GPU readback. */
@EnabledOnOs(OS.MAC)
final class MetalFirstPersonMotionGpuTest {
    private static final int W = 64, H = 48, UNIFORM_OFFSET = 256;
    private MetalDevice device;
    private MetalCommandEncoder encoder;

    record Fixture(String name, String shader, float oldFov, float fov, boolean cameraLocked,
                   boolean deformed, boolean alphaHole, boolean previousBehind, float side) {}

    static Stream<Fixture> fixtures() {
        return Stream.of(
                new Fixture("hand_camera_locked_world_turn", "core/entity", 75, 75, true, false, false, false, .1F),
                new Fixture("hand_swing_and_bob", "core/entity", 75, 75, false, true, false, false, .1F),
                new Fixture("hand_independent_hud_fov", "core/entity", 55, 95, false, false, false, false, .1F),
                new Fixture("hand_layered_item_model_view", "core/item", 75, 75, false, false, false, false, .1F),
                new Fixture("off_hand_swing", "core/item", 75, 75, false, true, false, false, -.1F),
                new Fixture("hand_cutout_hole", "core/entity", 75, 75, false, false, true, false, .1F),
                new Fixture("hand_previous_behind_camera", "core/entity", 75, 75, false, false, false, true, .1F)
        );
    }

    static Stream<Boolean> handDepthSources() { return Stream.of(false, true); }

    @ParameterizedTest(name = "separate post-effect HUD depth: {0}")
    @MethodSource("handDepthSources")
    void actualHudDepthSnapshotDoesNotClassifyRetainedWorldAsHand(boolean separateHudTarget) throws Exception {
        int copyUsage = GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;
        int computeUsage = GpuTexture.USAGE_TEXTURE_BINDING | MetalGpuTexture.USAGE_SHADER_WRITE | copyUsage;
        int sourceWidth = separateHudTarget ? W*2 : W, sourceHeight = separateHudTarget ? H*2 : H;
        ByteBuffer hudBytes = ByteBuffer.allocateDirect(sourceWidth*sourceHeight*4).order(ByteOrder.nativeOrder());
        ByteBuffer integratedBytes = ByteBuffer.allocateDirect(W*H*4).order(ByteOrder.nativeOrder());
        for (int y = 0; y < sourceHeight; y++) for (int x = 0; x < sourceWidth; x++) {
            hudBytes.putFloat(x >= W/2 && x < W*3/4 && y >= H/2 && y < H*3/4 ? .8F : 0);
        }
        hudBytes.flip();
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            integratedBytes.putFloat(x >= W/2 && x < W*3/4 && y >= H/2 && y < H*3/4 ? .8F : .2F);
        }
        integratedBytes.flip();
        try (var actualHud = (MetalGpuTexture)device.createTexture("actual render3dHud target", copyUsage | GpuTexture.USAGE_RENDER_ATTACHMENT,
                     GpuFormat.D32_FLOAT, sourceWidth, sourceHeight, 1, 1);
             var main = texture("world plus integrated HUD", GpuFormat.D32_FLOAT, copyUsage);
             var snapshot = texture("isolated hand depth snapshot", GpuFormat.D32_FLOAT, copyUsage | GpuTexture.USAGE_TEXTURE_BINDING);
             var flow = texture("hand overlay motion", GpuFormat.RG16_FLOAT, computeUsage);
             var valid = texture("hand overlay validity", GpuFormat.R8_UNORM, computeUsage);
             var exact = texture("no exact hand producer", GpuFormat.R8_UNORM, computeUsage);
             var reactive = texture("hand reactivity", GpuFormat.R8_UNORM, computeUsage)) {
            encoder.writeToTexture(actualHud, hudBytes, 0, 0, 0, 0, sourceWidth, sourceHeight);
            encoder.writeToTexture(main, integratedBytes, 0, 0, 0, 0, W, H);
            assertFalse(MetalFxManager.copyFirstPersonDepth(encoder, actualHud, snapshot, W+1, H),
                    "a snapshot with incompatible frame dimensions must fail closed");
            assertTrue(MetalFxManager.copyFirstPersonDepth(encoder, actualHud, snapshot, W, H));
            encoder.clearColorTexture(flow, new Vector4f(-.125F, .0625F, 0, 0));
            encoder.clearColorTexture(valid, new Vector4f(1));
            encoder.clearColorTexture(exact, new Vector4f(0));
            encoder.clearColorTexture(reactive, new Vector4f(0));
            assertTrue(encoder.encodeHandOverlayMotion(snapshot, flow, valid, exact, reactive, W, H, .8F));
            encoder.submit(); device.waitForSubmittedGpuWork();
            byte[] depth = readback(snapshot), mixed = readback(main), motion = readback(flow), mask = readback(valid), react = readback(reactive);
            ByteBuffer depths = ByteBuffer.wrap(depth).order(ByteOrder.nativeOrder());
            ByteBuffer world = ByteBuffer.wrap(mixed).order(ByteOrder.nativeOrder());
            ByteBuffer vectors = ByteBuffer.wrap(motion).order(ByteOrder.nativeOrder());
            Path dir = Path.of("build/agent-evidence/motion-producers/isolated_hud_depth_"+separateHudTarget);
            Files.createDirectories(dir);
            Files.write(dir.resolve("isolated-hand.d32f"), depth); Files.write(dir.resolve("retained-world.d32f"), mixed);
            Files.write(dir.resolve("motion.rg16f"), motion); Files.write(dir.resolve("reactive.r8"), react);
            for (int p = 0; p < W*H; p++) {
                int x = p%W, y = p/W;
                boolean hand = x >= W/2 && x < W*3/4 && y >= H/2 && y < H*3/4;
                assertEquals(hand ? .8F : 0, depths.getFloat(p*4), 0);
                assertEquals(hand ? .8F : .2F, world.getFloat(p*4), 1e-7, "snapshot/overlay must leave integrated depth untouched");
                assertEquals(hand ? 0 : -.125F, Float.float16ToFloat(vectors.getShort(p*4)), 0,
                        "world pixels must retain their own motion, even when main has nonzero depth");
                assertEquals(hand ? 0 : .0625F, Float.float16ToFloat(vectors.getShort(p*4+2)), 0);
                assertEquals(255, Byte.toUnsignedInt(mask[p]));
                assertEquals(hand ? 204 : 0, Byte.toUnsignedInt(react[p]), 1);
            }
            Files.writeString(dir.resolve("result.json"), "{\"width\":"+W+",\"height\":"+H
                    +",\"separateHudTarget\":"+separateHudTarget+",\"handPixels\":"+(W/4*H/4)
                    +",\"worldMotionPreserved\":true,\"sourceDepthUnchanged\":true}");
            encoder.clearDepthTexture(actualHud, 0);
            assertTrue(MetalFxManager.copyFirstPersonDepth(encoder, actualHud, snapshot, W, H));
            encoder.submit(); device.waitForSubmittedGpuWork();
            for (byte b : readback(snapshot)) assertEquals(0, b, "a disappearing hand must not retain coverage");
        }
    }

    @BeforeEach
    void setup() {
        ShaderSource sources = MetalShaderSourceAdapters.from((id, type) -> {
            String resource = "assets/" + id.getNamespace() + "/shaders/" + id.getPath()
                    + (type == ShaderType.VERTEX ? ".vsh" : ".fsh");
            try { return MetalMotionShaderCompilationTest.expandIncludes(resource, new HashSet<>()); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        });
        device = new MetalDevice(sources, new GpuDebugOptions(2, true, true, true),
                MetalNativeBridge.metallum_create_system_default_device(), MemorySegment.NULL,
                "First-person projection history", MemorySegment.NULL);
        encoder = device.commandEncoder();
        MetalPreviousVertexHistory.reset();
    }

    @Test
    void frontendPendingCompilesRetainTheirOwnOriginalPipelineSemantics() {
        var resources = BindGroupLayout.builder().withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER).withUniform("Fog", UniformType.UNIFORM_BUFFER)
                .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER).build();
        var snippet = RenderPipeline.builder().withVertexShader("core/entity").withFragmentShader("core/entity")
                .withVertexBinding(0, DefaultVertexFormat.ENTITY).withBindGroupLayout(resources)
                .withColorTargetState(ColorTargetState.DEFAULT).withShaderDefine("NO_CARDINAL_LIGHTING")
                .withShaderDefine("NO_OVERLAY").withShaderDefine("EMISSIVE").buildSnippet();
        var first = RenderPipeline.builder(snippet).withLocation("source_contract/shared_location")
                .withShaderDefine("ALPHA_CUTOUT", .1F).build();
        var second = RenderPipeline.builder(snippet).withLocation("source_contract/shared_location")
                .withShaderDefine("ALPHA_CUTOUT", .8F).withShaderDefine("APPLY_TEXTURE_MATRIX").build();
        try (var builder = new com.mojang.renderpearl.frontend.shaders.PipelineBuilder(device)) {
            var pendingFirst = MetalBackend.preservePipelineSource(
                    builder.compilePipeline(first, device.activeShaderSource(), Runnable::run).join(), first);
            var pendingSecond = MetalBackend.preservePipelineSource(
                    builder.compilePipeline(second, device.activeShaderSource(), Runnable::run).join(), second);
            // Reverse completion proves source identity belongs to the pending compile,
            // rather than a global current-pipeline slot or a map keyed by location.
            try (var b = pendingSecond.finishCompile(); var a = pendingFirst.finishCompile()) {
                var backendA = (MetalCompiledRenderPipeline)((com.mojang.renderpearl.frontend.FrontendRenderPipeline)a).backendRenderPipeline();
                var backendB = (MetalCompiledRenderPipeline)((com.mojang.renderpearl.frontend.FrontendRenderPipeline)b).backendRenderPipeline();
                assertSame(first, backendA.sourcePipeline()); assertSame(second, backendB.sourcePipeline());
                assertEquals(DefaultVertexFormat.ENTITY, backendA.sourcePipeline().getVertexFormatBinding(0));
                assertEquals("0.1", backendA.sourcePipeline().getShaderDefines().values().get("ALPHA_CUTOUT"));
                assertTrue(backendB.sourcePipeline().getShaderDefines().flags().contains("APPLY_TEXTURE_MATRIX"));
                assertThrows(IllegalStateException.class, () -> backendA.captureSourcePipeline(second));
                assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(backendA.sourcePipeline()));
            }
        }
    }

    @Test
    void worldDrawHistoryMatchesActualMinecraftColorShaderRaster() throws Exception {
        var source = RenderPipeline.builder().withLocation("source_contract/world_entity")
                .withVertexShader("core/entity").withFragmentShader("core/entity")
                .withVertexBinding(0, DefaultVertexFormat.ENTITY).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withColorTargetState(ColorTargetState.DEFAULT).withCull(false)
                .withShaderDefine("NO_CARDINAL_LIGHTING").withShaderDefine("NO_OVERLAY").withShaderDefine("EMISSIVE")
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("Projection", UniformType.UNIFORM_BUFFER)
                        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER).withUniform("Fog", UniformType.UNIFORM_BUFFER)
                        .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER).build()).build();
        ShaderSource liveLibrary = device.activeShaderSource();
        device.precompilePipeline(source, MetalShaderSourceAdapters.overlay(java.util.Map.of(), liveLibrary));
        assertSame(liveLibrary, device.activeShaderSource(), "a per-pipeline overlay must not poison the live renderer shader library");
        Fixture geometry = new Fixture("world_source", "core/entity", 75, 75, false, true, false, false, .1F);
        Matrix4f baseP = projection(75), oldP = new Matrix4f(baseP).m20(.011F).m21(-.007F);
        Matrix4f newP = new Matrix4f(baseP).m20(-.009F).m21(.005F);
        Matrix4f oldView = new Matrix4f().translate(-.04F, .06F, 0).rotateZ(.08F);
        Matrix4f view = new Matrix4f().translate(.03F, -.02F, 0).rotateZ(-.06F);
        ByteBuffer oldMesh = vertices(geometry, true, oldView), mesh = vertices(geometry, false, view);
        float[] oldPositions = positions(oldMesh), currentPositions = positions(mesh);
        ByteBuffer fogBytes = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        fogBytes.putFloat(16, 1000).putFloat(20, 2000).putFloat(24, 1000).putFloat(28, 2000);
        ByteBuffer indexBytes = ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder());
        for (short i=0; i<6; i++) indexBytes.putShort(i); indexBytes.flip();
        var sample = new MetalEntityMotionCapture.Sample(8801, 4, new Matrix4f(), new Matrix4f());
        try (var oldV = device.createBuffer(() -> "previous world pose", GpuBuffer.USAGE_VERTEX, oldMesh);
             var currentV = device.createBuffer(() -> "current world pose", GpuBuffer.USAGE_VERTEX, mesh);
             var index = device.createBuffer(() -> "world indices", GpuBuffer.USAGE_INDEX, indexBytes);
             var oldProjection = projectionBuffer(oldP); var currentProjection = projectionBuffer(newP);
             var referenceProjection = projectionBuffer(baseP);
             var oldDynamic = dynamicBuffer(oldView); var dynamic = dynamicBuffer(view);
             var fog = device.createBuffer(() -> "real shader fog", GpuBuffer.USAGE_UNIFORM, fogBytes);
             var sampled = texture("source material", GpuFormat.RGBA8_UNORM, GpuTexture.USAGE_TEXTURE_BINDING);
             var color = texture("real Minecraft color", GpuFormat.RGBA8_UNORM, GpuTexture.USAGE_COPY_SRC);
             var flow = texture("world flow", GpuFormat.RG16_FLOAT, GpuTexture.USAGE_COPY_SRC);
             var validity = texture("world validity", GpuFormat.R8_UNORM, GpuTexture.USAGE_COPY_SRC);
             var sampledView = device.createTextureView(sampled); var colorView = device.createTextureView(color);
             var flowView = device.createTextureView(flow); var validityView = device.createTextureView(validity);
             var sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                     FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty())) {
            encoder.clearColorTexture(sampled, new Vector4f(1));
            byte[][] reference = new byte[2][], actualSource = new byte[2][];
            MetalPreviousVertexHistory.DrawToken token = null;
            for (int frame=0; frame<2; frame++) {
                MetalPreviousVertexHistory.beginFrame();
                token = MetalPreviousVertexHistory.reserveDraw(sample, source);
                var format = source.getVertexFormatBinding(0);
                MetalPreviousVertexHistory.stageSnapshot(token, new MetalPreviousVertexHistory.Signature(
                        source.getLocation().toString(), format.getElements(), format.getVertexSize(),
                        PrimitiveTopology.TRIANGLES, 6, 6), frame == 0 ? oldPositions : currentPositions);
                for (boolean unjitteredReference : new boolean[]{false, true}) {
                    try (var pass = encoder.createRenderPass(RenderPassDescriptor.builder(() -> "Actual Minecraft source shader")
                            .withColorAttachment(colorView, Optional.of(new Vector4f(0, 0, 0, 0)))
                            .withRenderArea(new RenderPass.RenderArea(0, 0, W, H)).build())) {
                        pass.setPipeline(source);
                        pass.setUniform("Projection", (unjitteredReference ? referenceProjection
                                : frame == 0 ? oldProjection : currentProjection).slice(UNIFORM_OFFSET, 64));
                        pass.setUniform("DynamicTransforms", (frame == 0 ? oldDynamic : dynamic).slice(UNIFORM_OFFSET, 256));
                        pass.setUniform("Fog", fog.slice()); pass.bindTexture("Sampler0", sampledView, sampler);
                        pass.setVertexBuffer(0, (frame == 0 ? oldV : currentV).slice());
                        pass.setIndexBuffer(index, IndexType.SHORT); pass.drawIndexed(6, 1, 0, 0, 0);
                        if (!unjitteredReference) {
                            assertTrue(MetalPreviousVertexReplay.captureSourceTransform(token,
                                    MetalPreviousVertexReplay.sourceDrawInputs(pass),
                                    new Matrix4f(baseP).mul(new Matrix4f(frame == 0 ? oldP : newP).invert())));
                            MetalPreviousVertexHistory.stageSurface(token,
                                    MetalPreviousVertexReplay.surfaceIdentity(MetalPreviousVertexReplay.sourceState(pass)));
                        }
                    }
                    encoder.submit(); device.waitForSubmittedGpuWork();
                    if (unjitteredReference) reference[frame] = readback(color);
                    else actualSource[frame] = readback(color);
                }
                if (frame == 0) MetalPreviousVertexHistory.commitSubmittedFrame();
            }
            assertTrue(MetalPreviousVertexHistory.matchingSurfaceManifest(token));
            var transforms = MetalPreviousVertexReplay.worldTransforms(token); assertNotNull(transforms);
            var info = new StagedVertexBuffer.ExecuteInfo(currentV, index, IndexType.SHORT, 0, 0, 6, PrimitiveTopology.TRIANGLES);
            var plan = MetalPreviousVertexReplay.plan(source, info, token); assertNotNull(plan);
            ByteBuffer overwritten = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()); projection(35).get(0, overwritten);
            encoder.writeToBuffer(currentProjection.slice(UNIFORM_OFFSET, 64), overwritten);
            try (var capturedProjection = projectionBuffer(transforms.currentProjection());
                 var motion = motionBuffer(transforms.currentUnjitteredFromRaster(), transforms.previousFromPosition());
                 var previous = positionBuffer(plan.previousPositions());
                 var pass = encoder.createRenderPass(RenderPassDescriptor.builder(() -> "Motion from real source draw history")
                         .withColorAttachment(flowView, Optional.of(new Vector4f()))
                         .withColorAttachment(validityView, Optional.of(new Vector4f()))
                         .withRenderArea(new RenderPass.RenderArea(0, 0, W, H)).build())) {
                pass.setPipeline(MetalEntityMotionPipeline.forPreviousPositions(source));
                pass.setUniform("Projection", capturedProjection.slice(UNIFORM_OFFSET, 64));
                pass.setUniform("DynamicTransforms", dynamic.slice(UNIFORM_OFFSET, 256)); pass.setUniform("MetallumMotion", motion.slice());
                pass.bindTexture("Sampler0", sampledView, sampler); pass.setVertexBuffer(0, plan.currentVertexBuffer());
                pass.setVertexBuffer(1, previous.slice()); pass.setIndexBuffer(index, IndexType.SHORT);
                pass.drawIndexed(6, 1, 0, plan.replayBaseVertex(), 0);
            }
            encoder.submit(); device.waitForSubmittedGpuWork();
            byte[] mask = readback(validity), vectors = readback(flow);
            byte[][] alpha = {new byte[W*H], new byte[W*H]};
            for (int frame=0; frame<2; frame++) for (int p=0; p<W*H; p++) alpha[frame][p] = reference[frame][p*4+3];
            double[] before = centroid(alpha[0]), after = centroid(alpha[1]);
            ByteBuffer values = ByteBuffer.wrap(vectors).order(ByteOrder.nativeOrder());
            double x=0,y=0; int count=0;
            for (int p=0; p<W*H; p++) if (Byte.toUnsignedInt(mask[p]) > 127) {
                x += Float.float16ToFloat(values.getShort(p*4))*W/2; y += Float.float16ToFloat(values.getShort(p*4+2))*H/2; count++;
            }
            if (count > 0) { x /= count; y /= count; }
            Path dir = Path.of("build/agent-evidence/motion-producers/world_actual_shader_source"); Files.createDirectories(dir);
            Files.write(dir.resolve("previous-source.rgba8"), actualSource[0]); Files.write(dir.resolve("current-source.rgba8"), actualSource[1]);
            Files.write(dir.resolve("previous-reference.rgba8"), reference[0]); Files.write(dir.resolve("current-reference.rgba8"), reference[1]);
            Files.write(dir.resolve("motion.rg16f"), vectors); Files.write(dir.resolve("current-validity.r8"), mask);
            Files.writeString(dir.resolve("result.json"), "{\"fixture\":\"world_actual_shader_source\",\"width\":"+W+",\"height\":"+H
                    +",\"coveredPixels\":"+count+",\"motionPixels\":["+x+","+y+"],\"rasterDisplacementPixels\":["
                    +(before[0]-after[0])+","+(before[1]-after[1])+"],\"referencePixels\":["+before[2]+","+after[2]+"],\"surfaceHistoryMatched\":true}");
            assertTrue(count > 100 && before[2] > 100 && after[2] > 100);
            assertTrue(before[2] < W*H/2 && after[2] < W*H/2,
                    "transparent reference background must not count as geometry");
            assertEquals(before[0]-after[0], x, .6, "motion must agree with the actual Minecraft unjittered color shader");
            assertEquals(before[1]-after[1], y, .6);
        }
    }

    @AfterEach
    void cleanup() {
        MetalPreviousVertexHistory.discardFrame();
        MetalPreviousVertexHistory.reset();
        MetalEntityMotionPipeline.clear();
        if (device != null) device.close();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void sourceHudHistoryMatchesIndependentRasterFrames(Fixture f) throws Exception {
        var source = RenderPipeline.builder().withLocation("hand_contract/" + f.name())
                .withVertexShader(f.shader()).withFragmentShader(f.shader())
                .withVertexBinding(0, DefaultVertexFormat.ENTITY)
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                        .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER).build())
                .withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
                .withShaderDefine("ALPHA_CUTOUT", .5F).build();
        var pipeline = MetalEntityMotionPipeline.forPreviousPositions(source);
        var sample = new MetalEntityMotionCapture.Sample(f.side() > 0 ? 101 : 102, -7,
                new Matrix4f(), new Matrix4f(), FrameSynthesisContract.ProducerDomain.FIRST_PERSON);
        Matrix4f oldProjection = projection(f.oldFov()), projection = projection(f.fov());
        Matrix4f oldView = f.cameraLocked() ? new Matrix4f().rotateY(.23F)
                : new Matrix4f().translate(-.04F, .06F, 0).rotateZ(.08F);
        Matrix4f view = f.cameraLocked() ? new Matrix4f().rotateY(-.31F)
                : new Matrix4f().translate(.03F, -.02F, 0).rotateZ(-.06F);
        if (f.previousBehind()) oldView.rotateY((float)Math.PI);
        ByteBuffer oldVertices = vertices(f, true, oldView), vertices = vertices(f, false, view);
        float[] oldPositions = positions(oldVertices), currentPositions = positions(vertices);
        ByteBuffer indices = ByteBuffer.allocateDirect(6*Short.BYTES).order(ByteOrder.nativeOrder());
        for (short i = 0; i < 6; i++) indices.putShort(i);
        indices.flip();
        try (GpuBuffer previousVertices = device.createBuffer(() -> "previous posed hand", GpuBuffer.USAGE_VERTEX, oldVertices);
             GpuBuffer currentVertices = device.createBuffer(() -> "current posed hand", GpuBuffer.USAGE_VERTEX, vertices);
             GpuBuffer index = device.createBuffer(() -> "hand indices", GpuBuffer.USAGE_INDEX, indices);
             GpuBuffer previousPositions = positionBuffer(oldPositions);
             GpuBuffer oldPr = projectionBuffer(oldProjection);
             GpuBuffer pr = projectionBuffer(projection);
             GpuBuffer oldDynamic = dynamicBuffer(oldView);
             GpuBuffer dynamic = dynamicBuffer(view);
             GpuBuffer oldMotion = motionBuffer(MetalMotionContract.minecraftToMetalFxClip(new Matrix4f()),
                     MetalMotionContract.minecraftToMetalFxClip(new Matrix4f(oldProjection).mul(oldView)));
             GpuBuffer currentMotion = motionBuffer(MetalMotionContract.minecraftToMetalFxClip(new Matrix4f()),
                     MetalMotionContract.minecraftToMetalFxClip(new Matrix4f(projection).mul(view)));
             MetalGpuTexture sampled = texture("hand texture", GpuFormat.RGBA8_UNORM, GpuTexture.USAGE_TEXTURE_BINDING);
             MetalGpuTexture flow = texture("hand flow", GpuFormat.RG16_FLOAT, GpuTexture.USAGE_COPY_SRC);
             MetalGpuTexture valid = texture("dedicated hand validity", GpuFormat.R8_UNORM, GpuTexture.USAGE_COPY_SRC);
             MetalGpuTexture depth = texture("independent HUD depth", GpuFormat.D32_FLOAT, 0);
             var sampledView = device.createTextureView(sampled);
             var flowView = device.createTextureView(flow);
             var validView = device.createTextureView(valid);
             var depthView = device.createTextureView(depth);
             var sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                     FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty())) {
            encoder.clearColorTexture(sampled, new Vector4f(1, 1, 1, f.alphaHole() ? 0 : 1));
            // Rasterize each real source frame with its own projection/model-view, independently
            // of the history-derived motion matrices. Capture exactly the bound source slices.
            byte[][] sourceMasks = new byte[2][];
            MetalPreviousVertexHistory.DrawToken token = null;
            for (int frame = 0; frame < 2; frame++) {
                MetalPreviousVertexHistory.beginFrame();
                token = MetalPreviousVertexHistory.reserveDraw(sample, source);
                var format = source.getVertexFormatBinding(0);
                MetalPreviousVertexHistory.stageSnapshot(token, new MetalPreviousVertexHistory.Signature(
                        source.getLocation().toString(), format.getElements(), format.getVertexSize(),
                        PrimitiveTopology.TRIANGLES, 6, 6), frame == 0 ? oldPositions : currentPositions);
                var descriptor = RenderPassDescriptor.builder(() -> "Independent HUD source raster")
                        .withColorAttachment(flowView, Optional.of(new Vector4f()))
                        .withColorAttachment(validView, Optional.of(new Vector4f()))
                        .withDepthAttachment(depthView, OptionalDouble.of(0))
                        .withRenderArea(new RenderPass.RenderArea(0, 0, W, H)).build();
                try (MetalRenderPass pass = encoder.createRenderPass(descriptor)) {
                    pass.setPipeline(pipeline);
                    pass.setUniform("Projection", (frame == 0 ? oldPr : pr).slice(UNIFORM_OFFSET, 64));
                    pass.setUniform("DynamicTransforms", (frame == 0 ? oldDynamic : dynamic).slice(UNIFORM_OFFSET, 256));
                    pass.setUniform("MetallumMotion", (frame == 0 ? oldMotion : currentMotion).slice());
                    pass.bindTexture("Sampler0", sampledView, sampler);
                    pass.setVertexBuffer(0, (frame == 0 ? previousVertices : currentVertices).slice());
                    pass.setVertexBuffer(1, previousPositions.slice());
                    pass.setIndexBuffer(index, IndexType.SHORT);
                    pass.drawIndexed(6, 1, 0, 0, 0);
                    var inputs = MetalPreviousVertexReplay.sourceDrawInputs(pass);
                    assertNotNull(inputs);
                    assertSame(depthView, inputs.depth(), "Hand replay must retain the source HUD depth attachment");
                    assertTrue(MetalPreviousVertexReplay.captureHandTransform(token, inputs));
                }
                encoder.submit(); device.waitForSubmittedGpuWork();
                sourceMasks[frame] = readback(valid);
                if (frame == 0) MetalPreviousVertexHistory.commitSubmittedFrame();
            }
            var hand = MetalPreviousVertexReplay.handTransforms(token);
            assertNotNull(hand);
            var executeInfo = new StagedVertexBuffer.ExecuteInfo(currentVertices, index, IndexType.SHORT, 0, 0, 6,
                    PrimitiveTopology.TRIANGLES);
            var replay = MetalPreviousVertexReplay.plan(source, executeInfo, token);
            assertNotNull(replay);
            assertArrayEquals(oldPositions, replay.previousPositions());
            // Later screen effects overwrite the same mutable ProjectionMatrixBuffer. The queued
            // hand must still rasterize with its captured source projection, not this world FOV.
            ByteBuffer replacement = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
            projection(35).get(0, replacement);
            encoder.writeToBuffer(pr.slice(UNIFORM_OFFSET, 64), replacement);
            try (GpuBuffer replayPr = projectionBuffer(hand.currentProjection());
                 GpuBuffer replayMotion = motionBuffer(hand.currentUnjitteredFromRaster(), hand.previousFromPosition());
                 GpuBuffer replayPositions = positionBuffer(replay.previousPositions());
                 MetalRenderPass pass = encoder.createRenderPass(RenderPassDescriptor.builder(() -> "Exact hand replay from history")
                         .withColorAttachment(flowView, Optional.of(new Vector4f()))
                         .withColorAttachment(validView, Optional.of(new Vector4f()))
                         .withDepthAttachment(depthView)
                         .withRenderArea(new RenderPass.RenderArea(0, 0, W, H)).build())) {
                pass.setPipeline(pipeline);
                pass.setUniform("Projection", replayPr.slice(UNIFORM_OFFSET, 64));
                pass.setUniform("DynamicTransforms", dynamic.slice(UNIFORM_OFFSET, 256));
                pass.setUniform("MetallumMotion", replayMotion.slice());
                pass.bindTexture("Sampler0", sampledView, sampler);
                pass.setVertexBuffer(0, replay.currentVertexBuffer());
                pass.setVertexBuffer(1, replayPositions.slice());
                pass.setIndexBuffer(index, IndexType.SHORT);
                pass.drawIndexed(6, 1, 0, replay.replayBaseVertex(), 0);
                encoder.submitRenderPass(); encoder.submit(); device.waitForSubmittedGpuWork();
            }
            byte[] mask = readback(valid), vectors = readback(flow);
            double[] before = centroid(sourceMasks[0]), after = centroid(sourceMasks[1]);
            int count = 0; double x = 0, y = 0, maxMotion = 0;
            ByteBuffer values = ByteBuffer.wrap(vectors).order(ByteOrder.nativeOrder());
            for (int pixel = 0; pixel < W*H; pixel++) if (Byte.toUnsignedInt(mask[pixel]) > 127) {
                float vx = Float.float16ToFloat(values.getShort(pixel*4))*W/2;
                float vy = Float.float16ToFloat(values.getShort(pixel*4+2))*H/2;
                assertTrue(Float.isFinite(vx) && Float.isFinite(vy));
                x += vx; y += vy; maxMotion = Math.max(maxMotion, Math.max(Math.abs(vx), Math.abs(vy))); count++;
            }
            if (count > 0) { x /= count; y /= count; }
            Path dir = Path.of("build/agent-evidence/motion-producers/" + f.name()); Files.createDirectories(dir);
            Files.write(dir.resolve("previous-validity.r8"), sourceMasks[0]);
            Files.write(dir.resolve("source-current-validity.r8"), sourceMasks[1]);
            Files.write(dir.resolve("current-validity.r8"), mask); Files.write(dir.resolve("motion.rg16f"), vectors);
            Files.writeString(dir.resolve("result.json"), "{\"fixture\":\"" + f.name() + "\",\"width\":" + W
                    + ",\"height\":" + H + ",\"coveredPixels\":" + count + ",\"motionPixels\":[" + x + "," + y
                    + "],\"rasterDisplacementPixels\":[" + (before[0]-after[0]) + "," + (before[1]-after[1])
                    + "],\"maxMotionPixels\":" + maxMotion + ",\"sourceUniformOffset\":" + UNIFORM_OFFSET + "}");
            if (f.alphaHole() || f.previousBehind()) assertEquals(0, count);
            else {
                assertArrayEquals(sourceMasks[1], mask, "Replay must preserve the source HUD projection and coverage");
                assertTrue(count > 100 && before[2] > 100);
                assertEquals(before[0]-after[0], x, .6, "Current-to-previous X agrees with independent source raster");
                assertEquals(before[1]-after[1], y, .6, "Current-to-previous Y agrees with shipping compiler raster orientation");
                if (f.cameraLocked()) assertEquals(0, maxMotion, 1E-3, "World camera turn cannot invent hand motion");
                else assertTrue(maxMotion > 1, "Swing/FOV/model-view motion must not silently collapse to zero");
            }
        }
    }

    @Test
    void sourceUniformCaptureRejectsPrivateClosedMalformedAndNonFiniteBackings() {
        try (GpuBuffer valid = projectionBuffer(projection(75));
             GpuBuffer privateBuffer = device.createBuffer(() -> "GPU-only uniform", GpuBuffer.USAGE_UNIFORM, 64)) {
            var good = new MetalPreviousVertexReplay.SourceDrawInputs(valid.slice(UNIFORM_OFFSET, 64), valid.slice(UNIFORM_OFFSET, 64), null);
            var source = RenderPipeline.builder().withLocation("hand_contract/admission").withVertexShader("core/entity")
                    .withFragmentShader("core/entity").withVertexBinding(0, DefaultVertexFormat.ENTITY)
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();
            MetalPreviousVertexHistory.beginFrame();
            var sample = new MetalEntityMotionCapture.Sample(101, -1, new Matrix4f(), new Matrix4f());
            var token = MetalPreviousVertexHistory.reserveDraw(sample, source);
            var format = source.getVertexFormatBinding(0);
            MetalPreviousVertexHistory.stageSnapshot(token, new MetalPreviousVertexHistory.Signature(source.getLocation().toString(),
                    format.getElements(), format.getVertexSize(), PrimitiveTopology.TRIANGLES, 1, 3), new float[]{0,0,-1});
            assertTrue(MetalPreviousVertexReplay.captureHandTransform(token, good));
            for (GpuBufferSlice bad : List.of(privateBuffer.slice(), valid.slice(0, 64), valid.slice(UNIFORM_OFFSET, 63),
                    new GpuBufferSlice(valid, Long.MAX_VALUE, 64), new GpuBufferSlice(valid, -1, 64))) {
                assertFalse(MetalPreviousVertexReplay.captureHandTransform(token,
                        new MetalPreviousVertexReplay.SourceDrawInputs(bad, good.dynamicTransforms(), null)));
                assertNull(MetalPreviousVertexHistory.currentDrawTransform(token));
            }
            valid.close();
            assertFalse(MetalPreviousVertexReplay.captureHandTransform(token, good));
            assertFalse(MetalPreviousVertexReplay.captureHandTransform(token, null));
        }
    }

    private GpuBuffer projectionBuffer(Matrix4f matrix) {
        ByteBuffer bytes = ByteBuffer.allocateDirect(512).order(ByteOrder.nativeOrder());
        new Matrix4f().m00(Float.NaN).get(0, bytes); matrix.get(UNIFORM_OFFSET, bytes);
        return device.createBuffer(() -> "source projection with slice offset", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, bytes);
    }

    private GpuBuffer dynamicBuffer(Matrix4f matrix) {
        GpuBuffer buffer = device.createBuffer(() -> "mapped source DynamicTransforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, 512);
        try (var mapped = buffer.slice(UNIFORM_OFFSET, 256).map(false, true)) {
            ByteBuffer bytes = mapped.data().order(ByteOrder.nativeOrder()); matrix.get(0, bytes);
            new Matrix4f().get(64, bytes); for (int i = 0; i < 4; i++) bytes.putFloat(128+i*4, 1);
        }
        return buffer;
    }

    private GpuBuffer positionBuffer(float[] positions) {
        ByteBuffer bytes = ByteBuffer.allocateDirect(positions.length*4).order(ByteOrder.nativeOrder());
        for (float position : positions) bytes.putFloat(position); bytes.flip();
        return device.createBuffer(() -> "previous hand positions", GpuBuffer.USAGE_VERTEX, bytes);
    }

    private GpuBuffer motionBuffer(Matrix4f current, Matrix4f previous) {
        ByteBuffer bytes = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder()); current.get(0, bytes); previous.get(64, bytes);
        return device.createBuffer(() -> "hand motion uniforms", GpuBuffer.USAGE_UNIFORM, bytes);
    }

    private MetalGpuTexture texture(String name, GpuFormat format, int usage) {
        return (MetalGpuTexture) device.createTexture(name, usage | GpuTexture.USAGE_RENDER_ATTACHMENT, format, W, H, 1, 1);
    }

    private static Matrix4f projection(float fov) {
        return new Matrix4f().perspective((float)Math.toRadians(fov), (float)W/H, 100, .05F, true);
    }

    private static ByteBuffer vertices(Fixture f, boolean previous, Matrix4f modelView) {
        float[][] xy = {{-.4F,-.4F},{.4F,-.4F},{-.4F,.4F},{-.4F,.4F},{.4F,-.4F},{.4F,.4F}};
        var format = DefaultVertexFormat.ENTITY; int stride = format.getVertexSize();
        ByteBuffer bytes = ByteBuffer.allocateDirect(6*stride).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 6; i++) {
            float x = xy[i][0] + f.side(), y = xy[i][1];
            if (previous && f.deformed()) { x = x*.85F-.12F; y = y*1.1F+.07F; }
            Vector4f point = new Vector4f(x, y, -1, 1);
            if (f.cameraLocked()) point.mul(new Matrix4f(modelView).invert());
            int p = i*stride+format.getElement("Position").offset();
            bytes.putFloat(p,point.x).putFloat(p+4,point.y).putFloat(p+8,point.z);
            bytes.putInt(i*stride+format.getElement("Color").offset(), 0xFFFFFFFF);
        }
        return bytes;
    }

    private static float[] positions(ByteBuffer vertices) {
        return MetalPreviousVertexHistory.extractPositions(DefaultVertexFormat.ENTITY, List.of(vertices), 6);
    }

    private byte[] readback(MetalGpuTexture texture) {
        int size = W*H*texture.pixelSize();
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(() -> "hand motion readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, size)) {
            encoder.copyTextureToBuffer(texture, buffer, 0, () -> {}, 0); encoder.submit(); device.waitForSubmittedGpuWork();
            byte[] bytes = new byte[size]; buffer.currentStorage().get(0, bytes); return bytes;
        }
    }

    private static double[] centroid(byte[] mask) {
        double x = 0, y = 0, count = 0;
        for (int p = 0; p < mask.length; p++) if (Byte.toUnsignedInt(mask[p]) > 127) { x += p%W+.5; y += p/W+.5; count++; }
        return count == 0 ? new double[]{0,0,0} : new double[]{x/count,y/count,count};
    }
}
