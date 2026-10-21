package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;

/** Original canonical color + identical-depth background -> staged history -> packaged MRT -> native V4. */
@EnabledOnOs(OS.MAC)
final class MetalParticleVertexAlphaGpuTest {
    private static final int W = 64, H = 48, PIXELS = W * H;
    private ShaderSource library;
    private MetalDevice device;
    private MetalCommandEncoder encoder;

    @BeforeEach
    void setup() {
        library = MetalShaderSourceLibraryTest.rawLibrary(false, false);
        device = new MetalDevice(library, new GpuDebugOptions(2, true, true, true),
                MetalNativeBridge.metallum_create_system_default_device(), MemorySegment.NULL,
                "Previous zero contribution", MemorySegment.NULL);
        encoder = device.commandEncoder();
        MetalPreviousVertexHistory.reset();
    }

    @AfterEach
    void close() {
        MetalPreviousVertexHistory.discardFrame(); MetalPreviousVertexHistory.reset();
        MetalEntityMotionPipeline.clear();
        if (device != null) device.close();
        if (library != null) library.close();
    }

    @ParameterizedTest(name = "particle vertex alpha: pair={0}, old A alpha={1}")
    @CsvSource({"false,0", "false,255", "true,0", "true,255"})
    void actualQuadAlphaAndCrossBatchIdsCannotBorrowSameDepthBackground(boolean pairScene, int oldAlpha) throws Exception {
        var format = DefaultVertexFormat.PARTICLE;
        var source = RenderPipeline.builder().withLocation("particle_vertex_alpha_gpu/" + pairScene + oldAlpha)
                .withVertexShader("core/particle").withFragmentShader("core/particle")
                .withVertexBinding(0, format).withPrimitiveTopology(PrimitiveTopology.QUADS).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
                .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("Projection", UniformType.UNIFORM_BUFFER)
                        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER).withUniform("Fog", UniformType.UNIFORM_BUFFER)
                        .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("Sampler2", UniformType.COMBINED_IMAGE_SAMPLER).build()).build();
        ByteBuffer background = vertices(format, 1);
        for (int v = 0; v < 4; v++) {
            int colorOffset = v * format.getVertexSize() + format.getElement("Color").offset();
            background.put(colorOffset, (byte)0).put(colorOffset + 1, (byte)0).put(colorOffset + 2, (byte)255);
        }
        ByteBuffer indices = bytes(24);
        for (int quad = 0; quad < 2; quad++) for (int i : new int[]{0,1,2,2,3,0}) indices.putShort((short)(quad * 4 + i));
        indices.flip();
        ByteBuffer projection = bytes(64); new Matrix4f().get(0, projection);
        ByteBuffer fog = bytes(40); fog.putFloat(16,1000).putFloat(20,2000).putFloat(24,1000).putFloat(28,2000);
        int input = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;
        int output = input | MetalGpuTexture.USAGE_SHADER_WRITE;
        Path dir = Path.of("build/agent-evidence/motion-producers/particle-vertex-alpha/" + pairScene + "_" + oldAlpha);
        Files.createDirectories(dir);
        var results = new ArrayList<Map<String, Object>>();
        var sourceFrames = new ArrayList<Map<String, Object>>();
        var sample = new MetalEntityMotionCapture.Sample(4817, 19, new Matrix4f(), new Matrix4f(), FrameSynthesisContract.ProducerDomain.PARTICLES_WEATHER);
        try (var bg = device.createBuffer(() -> "same-Z blue background geometry", GpuBuffer.USAGE_VERTEX, background);
             var ib = device.createBuffer(() -> "staged indices", GpuBuffer.USAGE_INDEX, indices);
             var pr = device.createBuffer(() -> "source projection", GpuBuffer.USAGE_UNIFORM, projection);
             var fo = device.createBuffer(() -> "source fog", GpuBuffer.USAGE_UNIFORM, fog);
             var sampled = texture("unchanged white material/lightmap", GpuFormat.RGBA8_UNORM, input);
             var color = texture("original source color", GpuFormat.RGBA8_UNORM, input);
             var scene = texture("actual original source depth", GpuFormat.D32_FLOAT, input);
             var ownership = texture("private replay depth", GpuFormat.D32_FLOAT, input);
             var flow = texture("source XY", GpuFormat.RG16_FLOAT, output);
             var valid = texture("source ownership", GpuFormat.R8_UNORM, input);
             var pair = texture("previous/current Z", GpuFormat.RG32_FLOAT, input);
             var oldWorld = texture("accepted original depth with background", GpuFormat.R32_FLOAT, input);
             var oldHud = texture("no previous HUD", GpuFormat.R32_FLOAT, input);
             var noHand = texture("no HUD ownership", GpuFormat.R8_UNORM, input);
             var merged = texture("V4 XY", GpuFormat.RG16_FLOAT, output);
             var reactive = texture("V4 reactive", GpuFormat.R8_UNORM, output);
             var camera = texture("V4 camera", GpuFormat.RG16_FLOAT, output);
             var disocclusion = texture("V4 rejection", GpuFormat.R8_UNORM, output);
             var nextWorld = texture("V4 next world", GpuFormat.R32_FLOAT, output);
             var presented = texture("V4 presentation Z", GpuFormat.R32_FLOAT, output);
             var nextHud = texture("V4 next HUD", GpuFormat.R32_FLOAT, output);
             var sv = device.createTextureView(sampled); var cv = device.createTextureView(color);
             var sd = device.createTextureView(scene); var od = device.createTextureView(ownership);
             var fv = device.createTextureView(flow); var vv = device.createTextureView(valid); var zv = device.createTextureView(pair);
             var sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                     FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty())) {
            encoder.clearColorTexture(sampled, new Vector4f(1));
            encoder.clearColorTexture(oldHud, new Vector4f(0)); encoder.clearColorTexture(noHand, new Vector4f(0));
            byte[] acceptedDepth = null;
            int frames = oldAlpha == 0 ? 4 : 2;
            for (int frame = 0; frame < frames; frame++) {
                final int frameIndex = frame;
                // Modulator, UVs, sampled texture, owner, generation, positions and indices stay fixed.
                // Only A's source vertex alpha changes. The old A and B are separate real draws.
                ByteBuffer dynamic = dynamic(1);
                List<List<Long>> groups = pairScene && frame == 0 ? List.of(List.of(11L), List.of(22L))
                        : List.of(pairScene ? List.of(22L, 11L) : List.of(11L));
                var frameBuffers = new ArrayList<GpuBuffer>();
                var tokens = new ArrayList<MetalPreviousVertexHistory.DrawToken>();
                try (var du = device.createBuffer(() -> "unchanged modulator one", GpuBuffer.USAGE_UNIFORM, dynamic)) {
                    MetalPreviousVertexHistory.beginFrame();
                    try (var pass = encoder.createRenderPass(RenderPassDescriptor.builder(() -> "Actual canonical blue background and red particles")
                            .withColorAttachment(cv, Optional.of(new Vector4f(0))).withDepthAttachment(sd, OptionalDouble.of(0))
                            .withRenderArea(new RenderPass.RenderArea(0, 0, W, H)).build())) {
                        pass.setPipeline(source); pass.setUniform("Projection", pr.slice()); pass.setUniform("Fog", fo.slice());
                        pass.bindTexture("Sampler0", sv, sampler); pass.bindTexture("Sampler2", sv, sampler);
                        pass.setIndexBuffer(ib, IndexType.SHORT); pass.setUniform("DynamicTransforms", du.slice());
                        pass.setVertexBuffer(0, bg.slice()); pass.drawIndexed(6, 1, 0, 0, 0);
                        for (var ids : groups) {
                            var token = MetalPreviousVertexHistory.reserveDraw(sample, source); tokens.add(token);
                            ByteBuffer mesh = MetalParticleAlphaFixtures.mesh(ids, pairScene, frame == 0 ? oldAlpha : 255);
                            ByteBuffer uploaded = MetalParticleAlphaFixtures.capture(token, format, PrimitiveTopology.QUADS,
                                    mesh, ids.size() * 4, ids.size() * 6);
                            MetalPreviousVertexHistory.stageParticleIdentities(token, ids);
                            var vb = device.createBuffer(() -> "actual captured source slices", GpuBuffer.USAGE_VERTEX, uploaded);
                            frameBuffers.add(vb); pass.setVertexBuffer(0, vb.slice()); pass.drawIndexed(ids.size() * 6, 1, 0, 0, 0);
                            assertTrue(MetalPreviousVertexReplay.captureSourceTransform(token,
                                    MetalPreviousVertexReplay.sourceDrawInputs(pass), new Matrix4f()));
                            var state = MetalPreviousVertexReplay.sourceState(pass); assertNotNull(state);
                            assertSame(MetalShaderSourceAdapters.libraryProof(library), state.shaderOwner()); assertTrue(state.shaderSourceLive());
                            MetalPreviousVertexHistory.stageSurface(token, MetalPreviousVertexReplay.surfaceIdentity(state));
                        }
                    }
                    encoder.submit(); device.waitForSubmittedGpuWork();
                    byte[] rgba = readback(color), actualDepth = readback(scene);
                    Files.write(dir.resolve("frame-" + frame + "-original.rgba8"), rgba);
                    Files.write(dir.resolve("frame-" + frame + "-original.d32f"), actualDepth);
                    int redPixels = 0, aRed = 0, bRed = 0;
                    for (int p = 0; p < PIXELS; p++) {
                        assertEquals(.5F, ByteBuffer.wrap(actualDepth).order(ByteOrder.nativeOrder()).getFloat(p * 4), 0,
                                "Original blue background has exactly the foreground's Z");
                        if (Byte.toUnsignedInt(rgba[p * 4]) == 255) {
                            redPixels++;
                            if (p % W < W / 2) aRed++; else bRed++;
                        } else assertEquals(255, Byte.toUnsignedInt(rgba[p * 4 + 2]), "No missing original background pixels");
                    }
                    int expectedOwned = W * H / 4, expectedInvisible = pairScene ? expectedOwned / 2 : expectedOwned;
                    assertEquals(expectedOwned - (frame == 0 && oldAlpha == 0 ? expectedInvisible : 0), redPixels,
                            "Original particle shader independently proves zero/nonzero source contribution");
                    if (pairScene) {assertEquals(frame == 0 && oldAlpha == 0 ? 0 : expectedOwned / 2, aRed); assertEquals(expectedOwned / 2, bRed);}
                    sourceFrames.add(Map.of("frame", frame, "modulatorAlpha", 1, "aVertexAlpha", frame == 0 ? oldAlpha : 255,
                            "redContributionPixels", redPixels, "leftRedPixels", aRed, "rightRedPixels", bRed, "sameDepthPixels", PIXELS,
                            "batchIds", groups));
                    if (frame > 0) {
                        var token = tokens.getFirst(); var vb = frameBuffers.getFirst(); int vertices = pairScene ? 8 : 4, indexCount = pairScene ? 12 : 6;
                        var info = new StagedVertexBuffer.ExecuteInfo(vb, ib, IndexType.SHORT, 0, 0, indexCount, PrimitiveTopology.QUADS);
                        assertTrue(MetalPreviousVertexHistory.matchingSurfaceManifest(token));
                        assertNotNull(MetalPreviousVertexHistory.matchedPreviousPositions(token));
                        assertEquals(1, MetalPreviousVertexHistory.matchedPreviousSourceUniforms(token).colorModulator().w);
                        var exact = MetalPreviousVertexReplay.plan(source, info, token);
                        var plan = exact != null ? exact : MetalPreviousVertexReplay.currentOnlyPlan(source, info, token);
                        var transforms = exact != null ? MetalPreviousVertexReplay.worldTransforms(token)
                                : MetalPreviousVertexReplay.currentOnlyTransforms(token, false);
                        assertNotNull(plan); assertNotNull(transforms);
                        ByteBuffer oldDepth = bytes(acceptedDepth.length); oldDepth.put(acceptedDepth).flip();
                        encoder.writeToTexture(oldWorld, oldDepth, 0, 0, 0, 0, W, H);
                        for (int control = 0; control < (oldAlpha == 0 && frame == 1 ? 2 : 1); control++) {
                            // Explicitly emulate removing only the new guard, retaining the same old geometry/depth/material.
                            boolean bypass = control == 1;
                            var selected = bypass ? new MetalPreviousVertexReplay.Plan(vb.slice(), 0,
                                    MetalPreviousVertexHistory.matchedPreviousPositions(token)) : plan;
                            var matrices = bypass ? MetalPreviousVertexReplay.worldTransforms(token) : transforms;
                            ByteBuffer positions = bytes(vertices * 16);
                            MetalPreviousVertexReplay.writePreviousPositions(selected.previousPositions(), positions); positions.flip();
                            ByteBuffer motion = bytes(128);
                            matrices.currentUnjitteredFromRaster().get(0, motion); matrices.previousFromPosition().get(64, motion);
                            try (var pp = device.createBuffer(() -> "previous position validity", GpuBuffer.USAGE_VERTEX, positions);
                                 var mo = device.createBuffer(() -> "production replay matrices", GpuBuffer.USAGE_UNIFORM, motion)) {
                                encoder.copyTextureToTexture(scene, ownership, 0, 0, 0, 0, 0, W, H);
                                try (var pass = encoder.createRenderPass(RenderPassDescriptor.builder(() -> "Production staged replay frame " + frameIndex)
                                        .withColorAttachment(fv, Optional.of(new Vector4f(0))).withColorAttachment(vv, Optional.of(new Vector4f(0)))
                                        .withColorAttachment(zv, Optional.of(new Vector4f(-1))).withDepthAttachment(od)
                                        .withRenderArea(new RenderPass.RenderArea(0, 0, W, H)).build())) {
                                    pass.setPipeline(MetalEntityMotionPipeline.forPreviousPositionsWithDepth(source));
                                    pass.setUniform("Projection", pr.slice()); pass.setUniform("DynamicTransforms", du.slice());
                                    pass.setUniform("MetallumMotion", mo.slice()); pass.bindTexture("Sampler0", sv, sampler);
                                    pass.bindTexture("Sampler2", sv, sampler); pass.bindTexture("MetallumSourceDepth", sd, sampler);
                                    pass.setVertexBuffer(0, selected.currentVertexBuffer()); pass.setVertexBuffer(1, pp.slice());
                                    pass.setIndexBuffer(ib, IndexType.SHORT); pass.drawIndexed(indexCount, 1, 0, selected.replayBaseVertex(), 0);
                                }
                                encoder.clearColorTexture(reactive, new Vector4f(0));
                                assertTrue(encoder.resolveSurfaceMotionV4(scene, ownership, null, flow, valid, noHand, pair, oldWorld, oldHud,
                                        merged, reactive, camera, disocclusion, nextWorld, presented, nextHud,
                                        new Matrix4f(), new Matrix4f(), new Matrix4f(), new Vector2f(), new Vector2f(),
                                        .8F, W, H, true, true, false, true));
                                encoder.submit(); device.waitForSubmittedGpuWork();
                                byte[] tags = readback(valid), pairs = readback(pair), xy = readback(flow);
                                byte[] react = readback(reactive), reject = readback(disocclusion), resolved = readback(merged);
                                int owned = 0, invalid = 0, exactPixels = 0, fullyRejected = 0, wrong = 0, falselyAcceptedA = 0;
                                boolean expectInvalid = oldAlpha == 0 && frame <= 2 && !bypass;
                                for (int p = 0; p < PIXELS; p++) {
                                    boolean covered = Byte.toUnsignedInt(rgba[p * 4]) == 255;
                                    if (covered) {
                                        owned++; int tag = Byte.toUnsignedInt(tags[p]);
                                        if (tag == 64) invalid++; if (tag == 255) exactPixels++;
                                        if (oldAlpha == 0 && frame <= 2 && tag == 255 && (!pairScene || p % W < W / 2)) falselyAcceptedA++;
                                        int rejected = Byte.toUnsignedInt(reject[p]), reactivity = Byte.toUnsignedInt(react[p]);
                                        if (rejected == 255 && reactivity == 255) fullyRejected++;
                                        ByteBuffer z = ByteBuffer.wrap(pairs).order(ByteOrder.nativeOrder());
                                        if (tag != (expectInvalid ? 64 : 255) || z.getFloat(p * 8) != (expectInvalid ? -1 : .5F)
                                                || z.getFloat(p * 8 + 4) != .5F || rejected != (expectInvalid ? 255 : 0)
                                                || reactivity != (expectInvalid ? 255 : 0)) wrong++;
                                    } else if (tags[p] != 0) wrong++;
                                    for (byte[] vectors : new byte[][]{xy, resolved}) {
                                        ByteBuffer v = ByteBuffer.wrap(vectors).order(ByteOrder.nativeOrder());
                                        if (Float.float16ToFloat(v.getShort(p * 4)) != 0 || Float.float16ToFloat(v.getShort(p * 4 + 2)) != 0) wrong++;
                                    }
                                }
                                String prefix = "frame-" + frame + (bypass ? "-guard-removed" : "-production");
                                Files.write(dir.resolve(prefix + ".r8"), tags); Files.write(dir.resolve(prefix + ".rg32f"), pairs);
                                Files.write(dir.resolve(prefix + "-reactive.r8"), react); Files.write(dir.resolve(prefix + "-disocclusion.r8"), reject);
                                Files.write(dir.resolve(prefix + "-motion.rg16f"), xy); Files.write(dir.resolve(prefix + "-resolved.rg16f"), resolved);
                                results.add(Map.of("frame", frame, "guardRemoved", bypass, "falselyAcceptedZeroContributionA", falselyAcceptedA,
                                        "sourceRedPixels", redPixels, "ownedPixels", owned, "invalidPixels", invalid, "exactPixels", exactPixels,
                                        "fullyRejectedPixels", fullyRejected, "expectedInvalid", expectInvalid, "wrongPixels", wrong));
                            }
                        }
                    }
                    if (oldAlpha == 0 && frame == 1) MetalPreviousVertexHistory.discardFrame();
                    else {MetalPreviousVertexHistory.commitSubmittedFrame(); acceptedDepth = actualDepth;}
                } finally {for (var buffer : frameBuffers) buffer.close();}
            }
            String result = new com.google.gson.Gson().toJson(Map.ofEntries(
                    Map.entry("shader", "particle"), Map.entry("pairScene", pairScene), Map.entry("oldVertexAlpha", oldAlpha),
                    Map.entry("modulatorAlpha", 1), Map.entry("sameOwnerAndGeneration", true), Map.entry("sameGeometryAndMaterial", true),
                    Map.entry("backgroundZ", .5F), Map.entry("sourceCommit", System.getenv().getOrDefault("METALLUM_SOURCE_SHA", "local")),
                    Map.entry("sourceFrames", sourceFrames), Map.entry("results", results)));
            Files.writeString(dir.resolve("result.json"), result);
            System.out.println("PARTICLE_VERTEX_ALPHA_RESULT " + result);
            for (var row : results) {
                assertEquals(W * H / 4, row.get("ownedPixels"));
                assertEquals(0, row.get("wrongPixels"), "Actual source and motion/V4 zero-contribution contract: " + row);
                if (Boolean.TRUE.equals(row.get("guardRemoved"))) {
                    assertEquals(W * H / 4, row.get("exactPixels"));
                    assertEquals(0, row.get("fullyRejectedPixels"), "Coincident background fools depth alone when the guard is removed");
                    assertEquals(pairScene ? W * H / 8 : W * H / 4, row.get("falselyAcceptedZeroContributionA"));
                }
            }
        }
    }

    private static ByteBuffer vertices(VertexFormat format, float halfExtent) {
        ByteBuffer out = bytes(4 * format.getVertexSize());
        float[][] corners = {{-1,-1},{1,-1},{1,1},{-1,1}};
        for (int i = 0; i < 4; i++) {
            int base = i * format.getVertexSize();
            out.putFloat(base, corners[i][0] * halfExtent).putFloat(base + 4, corners[i][1] * halfExtent).putFloat(base + 8, .5F);
            out.putInt(base + format.getElement("Color").offset(), 0xFFFFFFFF);
            int uv = base + format.getElement("UV0").offset(); out.putFloat(uv, .25F).putFloat(uv + 4, .5F);
            if (format.getElement("Normal") != null) out.put(base + format.getElement("Normal").offset() + 2, (byte)127);
        }
        return out;
    }

    private static ByteBuffer dynamic(float alpha) {
        ByteBuffer out = bytes(160); out.put(MetalPreviousVisibilityHistoryTest.dynamic(alpha)).flip(); return out;
    }

    private static ByteBuffer bytes(int size) {return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());}

    private MetalGpuTexture texture(String name, GpuFormat format, int usage) {
        return (MetalGpuTexture) device.createTexture(name, usage | GpuTexture.USAGE_RENDER_ATTACHMENT, format, W, H, 1, 1);
    }

    private byte[] readback(MetalGpuTexture texture) {
        int size = PIXELS * texture.pixelSize();
        try (MetalGpuBuffer buffer = (MetalGpuBuffer)device.createBuffer(() -> "visibility readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, size)) {
            encoder.copyTextureToBuffer(texture, buffer, 0, () -> {}, 0);
            encoder.submit(); device.waitForSubmittedGpuWork();
            byte[] result = new byte[size]; buffer.currentStorage().get(0, result); return result;
        }
    }
}
