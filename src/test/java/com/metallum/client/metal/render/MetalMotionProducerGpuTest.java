package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Actual packaged previous-position shaders -> production compiler/FFM -> independent raster centroids. */
@EnabledOnOs(OS.MAC)
final class MetalMotionProducerGpuTest {
    private static final int W = 64, H = 48;
    private MetalDevice device;
    private MetalCommandEncoder encoder;

    record Fixture(String name, String source, VertexFormat format, boolean alphaCutout,
                   boolean deformed, boolean alphaHole, boolean behind) {}

    record ProjectiveFixture(String name, boolean deformed, boolean previousBehind, boolean jitter) {}

    static Stream<ProjectiveFixture> projectiveFixtures() {
        return Stream.of(new ProjectiveFixture("projective_yaw", false, false, false),
                new ProjectiveFixture("projective_posed_jitter", true, false, true),
                new ProjectiveFixture("projective_partial_previous_behind", true, true, true));
    }

    static Stream<Fixture> fixtures() {
        return Stream.of(
                new Fixture("entity_rigid", "core/entity", DefaultVertexFormat.ENTITY, false, false, false, false),
                new Fixture("entity_posed_previous_vertices", "core/entity", DefaultVertexFormat.ENTITY, false, true, false, false),
                new Fixture("entity_behind_camera", "core/entity", DefaultVertexFormat.ENTITY, false, false, false, true),
                new Fixture("entity_cutout", "core/entity", DefaultVertexFormat.ENTITY, true, false, false, false),
                new Fixture("entity_cutout_hole", "core/entity", DefaultVertexFormat.ENTITY, true, false, true, false),
                new Fixture("particle_previous_sprite", "core/particle", DefaultVertexFormat.PARTICLE, false, false, false, false),
                new Fixture("particle_alpha_hole", "core/particle", DefaultVertexFormat.PARTICLE, false, false, true, false),
                new Fixture("leash_previous_vertices", "core/rendertype_leash", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP, false, true, false, false)
        );
    }

    @BeforeEach
    void setup() {
        ShaderSource sources = MetalShaderSourceAdapters.from((id, type) -> {
            String resource = "assets/" + id.getNamespace() + "/shaders/" + id.getPath()
                    + (type == ShaderType.VERTEX ? ".vsh" : ".fsh");
            try {
                return MetalMotionShaderCompilationTest.expandIncludes(resource, new HashSet<>());
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });
        device = new MetalDevice(sources, new GpuDebugOptions(2, true, true, true),
                MetalNativeBridge.metallum_create_system_default_device(), MemorySegment.NULL,
                "Motion producer contract", MemorySegment.NULL);
        encoder = device.commandEncoder();
    }

    @AfterEach
    void cleanup() {
        MetalFxManager.close();
        MetalEntityMotionPipeline.clear();
        if (device != null) device.close();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void previousPositionMotionMatchesIndependentlyRasterizedFrames(Fixture f) throws Exception {
        var resources = BindGroupLayout.builder()
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER).build();
        var source = RenderPipeline.builder().withLocation("motion_contract/" + f.name())
                .withVertexShader(f.source()).withFragmentShader(f.source())
                .withVertexBinding(0, f.format()).withBindGroupLayout(resources)
                .withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM,
                        ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false);
        if (f.alphaCutout()) source.withShaderDefine("ALPHA_CUTOUT", 0.5F);
        var pipeline = MetalEntityMotionPipeline.forPreviousPositions(source.build());
        ByteBuffer current = vertices(f, false), previous = vertices(f, true);
        ByteBuffer previousPositions = ByteBuffer.allocateDirect(6*12).order(ByteOrder.nativeOrder());
        int offset = f.format().getElement("Position").offset();
        for (int i = 0; i < 6; i++) {
            int p = i*f.format().getVertexSize()+offset;
            previousPositions.putFloat(previous.getFloat(p)).putFloat(previous.getFloat(p+4)).putFloat(previous.getFloat(p+8));
        }
        previousPositions.flip();
        ByteBuffer dynamic = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
        new Matrix4f().get(0, dynamic);
        // Actual 26.3 dynamictransforms.glsl: ModelViewMat, TextureMat, ColorModulator, ModelOffset.
        new Matrix4f().get(64, dynamic);
        for (int i = 0; i < 4; i++) dynamic.putFloat(128+i*4, 1);
        ByteBuffer projection = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        new Matrix4f().get(0, projection);
        ByteBuffer motion = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        MetalMotionContract.minecraftToMetalFxClip(new Matrix4f()).get(0, motion);
        MetalMotionContract.minecraftToMetalFxClip(new Matrix4f().m33(f.behind() ? -1 : 1)).get(64, motion);
        try (GpuBuffer cv = device.createBuffer(() -> "current posed", GpuBuffer.USAGE_VERTEX, current);
             GpuBuffer pv = device.createBuffer(() -> "previous posed", GpuBuffer.USAGE_VERTEX, previous);
             GpuBuffer pp = device.createBuffer(() -> "previous position stream", GpuBuffer.USAGE_VERTEX, previousPositions);
             GpuBuffer du = device.createBuffer(() -> "DynamicTransforms", GpuBuffer.USAGE_UNIFORM, dynamic);
             GpuBuffer pr = device.createBuffer(() -> "Projection", GpuBuffer.USAGE_UNIFORM, projection);
             GpuBuffer mo = device.createBuffer(() -> "MetallumMotion", GpuBuffer.USAGE_UNIFORM, motion);
             MetalGpuTexture sampled = texture("visibility", GpuFormat.RGBA8_UNORM, GpuTexture.USAGE_TEXTURE_BINDING);
             MetalGpuTexture flow = texture("motion", GpuFormat.RG16_FLOAT, GpuTexture.USAGE_COPY_SRC);
             MetalGpuTexture valid = texture("validity", GpuFormat.R8_UNORM, GpuTexture.USAGE_COPY_SRC);
             var sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                     FilterMode.NEAREST, FilterMode.NEAREST, 1, java.util.OptionalDouble.empty());
             var sampledView = device.createTextureView(sampled);
             var flowView = device.createTextureView(flow);
             var validView = device.createTextureView(valid)) {
            encoder.clearColorTexture(sampled, new Vector4f(1, 1, 1, f.alphaHole() ? 0 : 1));
            byte[] previousMask = null;
            for (int frame = 0; frame < 2; frame++) {
                var descriptor = RenderPassDescriptor.builder(() -> "Motion producer " + f.name())
                        .withColorAttachment(flowView, Optional.of(new Vector4f()))
                        .withColorAttachment(validView, Optional.of(new Vector4f()))
                        .withRenderArea(new RenderPass.RenderArea(0, 0, W, H)).build();
                var pass = (MetalRenderPass) encoder.createRenderPass(descriptor);
                pass.setPipeline(pipeline);
                pass.setUniform("Projection", pr.slice());
                pass.setUniform("DynamicTransforms", du.slice());
                pass.setUniform("MetallumMotion", mo.slice());
                pass.bindTexture("Sampler0", sampledView, sampler);
                pass.setVertexBuffer(0, (frame == 0 ? pv : cv).slice());
                pass.setVertexBuffer(1, pp.slice());
                pass.draw(6, 1, 0, 0);
                encoder.submitRenderPass(); encoder.submit(); device.waitForSubmittedGpuWork();
                if (frame == 0) previousMask = readback(valid);
            }
            byte[] currentMask = readback(valid), vectors = readback(flow);
            double[] before = centroid(previousMask), after = centroid(currentMask);
            int count = (int) after[2];
            double x = 0, y = 0, minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            ByteBuffer samples = ByteBuffer.wrap(vectors).order(ByteOrder.nativeOrder());
            for (int p = 0; p < W*H; p++) if (Byte.toUnsignedInt(currentMask[p]) > 127) {
                float vx = Float.float16ToFloat(samples.getShort(p*4));
                float vy = Float.float16ToFloat(samples.getShort(p*4+2));
                assertTrue(Float.isFinite(vx) && Float.isFinite(vy));
                x += vx*W/2; y += vy*H/2; minX = Math.min(minX,vx); maxX = Math.max(maxX,vx);
            }
            if (count > 0) { x /= count; y /= count; }
            Path dir = Path.of("build/agent-evidence/motion-producers/" + f.name());
            Files.createDirectories(dir);
            Files.write(dir.resolve("previous-validity.r8"), previousMask);
            Files.write(dir.resolve("current-validity.r8"), currentMask);
            Files.write(dir.resolve("motion.rg16f"), vectors);
            Files.writeString(dir.resolve("result.json"), "{\"fixture\":\""+f.name()+"\",\"width\":"+W
                    +",\"height\":"+H+",\"coveredPixels\":"+count+",\"motionPixels\":["+x+","+y
                    +"],\"rasterDisplacementPixels\":["+(before[0]-after[0])+","+(before[1]-after[1])+"]}");
            if (f.alphaHole() || f.behind()) {
                assertEquals(0, count, "No valid motion for discarded or behind-camera geometry");
            } else {
                assertTrue(count > 100 && before[2] > 100, "Both independently rendered frames need coverage");
                assertEquals(before[0]-after[0], x, 0.6, "X flow must point from actual current pixels to previous pixels");
                assertEquals(before[1]-after[1], y, 0.6, "Y flow must agree with actual raster orientation, including compiler Y flip");
                if (f.deformed()) assertTrue(maxX-minX > 0.04, "Per-vertex deformation must not collapse to rigid zero motion");
            }
        }
    }

    private MetalGpuTexture texture(String name, GpuFormat format, int usage) {
        return (MetalGpuTexture) device.createTexture(name, usage | GpuTexture.USAGE_RENDER_ATTACHMENT, format, W, H, 1, 1);
    }

    static Stream<String> surfaceResolveFixtures() {
        return Stream.of("object_approaching", "previous_occluder", "previous_surface_absent",
                "current_occluder", "owned_invalid", "previous_z_nan", "previous_z_outside",
                "nonfinite_motion", "hand_independent_depth", "hand_previous_absent",
                "world_revealed_after_hand", "history_reset", "camera_reprojection", "standard_z", "standard_z_hand",
                "previous_raster_jitter", "hud_ignores_world_jitter", "center_occluder_beats_dilation",
                "invalid_camera_keeps_object", "preserved_reactive", "compatible_depth_neighbor",
                "nearest_transparent_surface");
    }

    @ParameterizedTest(name = "surface resolve: {0}")
    @MethodSource("surfaceResolveFixtures")
    void surfaceDepthHistoryResolvesThroughShippingFfmWithoutTemporalSupport(String name) throws Exception {
        assertTrue(MetalNativeBridge.supportsSurfaceMotionV4(device.metalDeviceHandle()));
        MetalNativeBridge.metallum_metalfx_set_reactive_tuning(0, 0, 0, 0, 0, 1, 1);
        int inputUsage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC;
        int outputUsage = inputUsage | MetalGpuTexture.USAGE_SHADER_WRITE;
        float sceneZ=.2F, ownershipZ=.2F, handZ=0, beforeZ=.6F, currentZ=.2F;
        float previousWorld=.6F, previousHand=0, worldMask=1, handMask=0, baseReactive=0;
        float motionX=-.125F, motionY=.0625F, expectedX=motionX, expectedY=motionY;
        boolean history=true, reversed=true, reject=false, invalid=false, preserve=false;
        boolean hasHand=false;
        boolean oldCenterOccluder=false, oldNeighborMatch=false, jitterProbe=false;
        Matrix4f current=new Matrix4f(), inverse=new Matrix4f(), previous=new Matrix4f();
        Vector2f jitter=new Vector2f(), oldJitter=new Vector2f();
        switch (name) {
            case "previous_occluder", "center_occluder_beats_dilation" -> {
                previousWorld=.8F; reject=true; oldCenterOccluder=name.startsWith("center_");
            }
            case "previous_surface_absent" -> { previousWorld=.1F; reject=true; }
            case "current_occluder" -> { sceneZ=.8F; reject=invalid=true; }
            case "owned_invalid" -> { worldMask=.25F; beforeZ=-1; reject=invalid=true; }
            case "previous_z_nan" -> { beforeZ=Float.NaN; reject=invalid=true; }
            case "previous_z_outside" -> { beforeZ=1.1F; reject=invalid=true; }
            case "nonfinite_motion" -> { motionX=Float.NaN; reject=invalid=true; }
            case "hand_independent_depth", "hud_ignores_world_jitter", "hand_previous_absent" -> {
                handZ=.85F; currentZ=handZ; handMask=1; previousHand=beforeZ;
                hasHand=true;
                previousWorld=.95F;
                if (name.equals("hand_previous_absent")) { previousHand=0; reject=true; }
                if (name.equals("hud_ignores_world_jitter")) { oldJitter.set(7, -5); jitterProbe=true; }
            }
            case "world_revealed_after_hand" -> { previousHand=.9F; reject=true; }
            case "history_reset" -> { history=false; reject=true; }
            case "camera_reprojection" -> {
                worldMask=0; previous.translation(.125F, .0625F, 0);
                previousWorld=sceneZ; expectedX=.125F; expectedY=.0625F;
            }
            case "standard_z" -> { reversed=false; previousWorld=beforeZ=.4F; handZ=previousHand=1; }
            case "standard_z_hand" -> {
                reversed=false; hasHand=true; sceneZ=ownershipZ=.8F;
                handZ=currentZ=.15F; previousHand=beforeZ=.4F; handMask=1;
            }
            case "previous_raster_jitter" -> { oldJitter.set(1, -1); jitterProbe=true; }
            case "invalid_camera_keeps_object" -> previous.m33(-1);
            case "preserved_reactive" -> { preserve=true; baseReactive=.4F; }
            case "compatible_depth_neighbor" -> { previousWorld=.3F; oldNeighborMatch=true; }
            case "nearest_transparent_surface" -> { sceneZ=.1F; ownershipZ=currentZ=.4F; }
        }
        if (invalid) { expectedX=expectedY=0; }
        if (jitterProbe) MetalNativeBridge.metallum_metalfx_set_reactive_tuning(0, 0, 0, 0, 0, 1, 0);
        int probeX=W/2, probeY=H/2, probe=probeY*W+probeX;
        int lookupX=(int)(probeX+.5F+expectedX*W/2+(hasHand ? 0 : oldJitter.x-jitter.x));
        int lookupY=(int)(probeY+.5F+expectedY*H/2+(hasHand ? 0 : oldJitter.y-jitter.y));
        try (var scene=texture("source world depth",GpuFormat.D32_FLOAT,inputUsage);
             var ownership=texture("private ownership depth",GpuFormat.D32_FLOAT,inputUsage);
             var hud=texture("actual HUD depth",GpuFormat.D32_FLOAT,inputUsage);
             var source=texture("source XY",GpuFormat.RG16_FLOAT,outputUsage);
             var valid=texture("world ownership",GpuFormat.R8_UNORM,inputUsage);
             var hudValid=texture("HUD ownership",GpuFormat.R8_UNORM,inputUsage);
             var pair=texture("surface previous/current Z",GpuFormat.RG32_FLOAT,inputUsage);
             var oldWorld=texture("previous world raster",GpuFormat.R32_FLOAT,inputUsage);
             var oldHud=texture("previous HUD raster",GpuFormat.R32_FLOAT,inputUsage);
             var merged=texture("resolved XY",GpuFormat.RG16_FLOAT,outputUsage);
             var reactive=texture("resolved reactivity",GpuFormat.R8_UNORM,outputUsage);
             var camera=texture("camera diagnostic",GpuFormat.RG16_FLOAT,outputUsage);
             var disocclusion=texture("disocclusion diagnostic",GpuFormat.R8_UNORM,outputUsage);
             var nextWorld=texture("next world raster",GpuFormat.R32_FLOAT,outputUsage);
             var presented=texture("composite depth",GpuFormat.R32_FLOAT,outputUsage);
             var nextHud=texture("next HUD raster",GpuFormat.R32_FLOAT,outputUsage)) {
            writeFloatTexture(scene,sceneZ); writeFloatTexture(ownership,ownershipZ); writeFloatTexture(hud,handZ);
            ByteBuffer sourceBytes=ByteBuffer.allocateDirect(W*H*4).order(ByteOrder.nativeOrder());
            for(int p=0;p<W*H;p++) sourceBytes.putShort(Float.floatToFloat16(motionX)).putShort(Float.floatToFloat16(motionY));
            sourceBytes.flip(); encoder.writeToTexture(source,sourceBytes,0,0,0,0,W,H);
            encoder.clearColorTexture(valid,new Vector4f(worldMask,0,0,0));
            encoder.clearColorTexture(hudValid,new Vector4f(handMask,0,0,0));
            writeFloatTexture(pair,beforeZ,currentZ);
            writeFloatTexture(oldWorld,jitterProbe ? 0 : previousWorld);
            writeFloatTexture(oldHud,jitterProbe ? 0 : previousHand);
            if (jitterProbe) writeFloatPixel(hasHand ? oldHud : oldWorld,lookupX,lookupY,hasHand ? previousHand : previousWorld);
            if (oldCenterOccluder) writeFloatPixel(oldWorld,lookupX+1,lookupY,beforeZ);
            if (oldNeighborMatch) writeFloatPixel(oldWorld,lookupX+1,lookupY,beforeZ);
            encoder.clearColorTexture(reactive,new Vector4f(baseReactive,0,0,0));
            assertTrue(encoder.resolveSurfaceMotionV4(scene,ownership,hud,source,valid,hudValid,pair,oldWorld,oldHud,
                    merged,reactive,camera,disocclusion,nextWorld,presented,nextHud,current,inverse,previous,
                    jitter,oldJitter,.8F,W,H,history,reversed,preserve,true));
            encoder.submit(); device.waitForSubmittedGpuWork();
            byte[] xy=readback(merged), r=readback(reactive), d=readback(disocclusion);
            byte[] world=readback(nextWorld), hand=readback(nextHud), composite=readback(presented);
            Path dir=Path.of("build/agent-evidence/motion-producers/surface-v4/"+name);
            Files.createDirectories(dir);
            Files.write(dir.resolve("motion.rg16f"),xy); Files.write(dir.resolve("reactive.r8"),r);
            Files.write(dir.resolve("disocclusion.r8"),d); Files.write(dir.resolve("world-depth.r32f"),world);
            Files.write(dir.resolve("hand-depth.r32f"),hand); Files.write(dir.resolve("presentation-depth.r32f"),composite);
            ByteBuffer flow=ByteBuffer.wrap(xy).order(ByteOrder.nativeOrder());
            float x=Float.float16ToFloat(flow.getShort(probe*4)), y=Float.float16ToFloat(flow.getShort(probe*4+2));
            Files.writeString(dir.resolve("result.json"),"{\"fixture\":\""+name+"\",\"motionNdc\":["+x+","+y
                    +"],\"expectedNdc\":["+expectedX+","+expectedY+"],\"historyRejected\":"+(Byte.toUnsignedInt(d[probe])>127)
                    +",\"expectedRejection\":"+reject+",\"contractVersion\":4}");
            assertEquals(expectedX,x,1e-4,"Exact source XY or camera XY must survive the common resolve");
            assertEquals(expectedY,y,1e-4);
            assertEquals(reject ? 255 : 0,Byte.toUnsignedInt(d[probe]),"Only the matching previous surface can supply history");
            float expectedReactive=reject ? 1 : hasHand ? .8F : preserve ? baseReactive : 0;
            assertEquals(expectedReactive*255,Byte.toUnsignedInt(r[probe]),1);
            float expectedWorld=reversed ? Math.max(sceneZ,ownershipZ) : Math.min(sceneZ,ownershipZ);
            ByteBuffer worldValues=ByteBuffer.wrap(world).order(ByteOrder.nativeOrder());
            ByteBuffer handValues=ByteBuffer.wrap(hand).order(ByteOrder.nativeOrder());
            ByteBuffer compositeValues=ByteBuffer.wrap(composite).order(ByteOrder.nativeOrder());
            for(int p=0;p<W*H;p++) {
                assertEquals(expectedWorld,worldValues.getFloat(p*4),0);
                assertEquals(handZ,handValues.getFloat(p*4),0);
                assertEquals(hasHand ? handZ : expectedWorld,compositeValues.getFloat(p*4),0);
                assertTrue(Float.isFinite(Float.float16ToFloat(flow.getShort(p*4)))
                        && Float.isFinite(Float.float16ToFloat(flow.getShort(p*4+2))));
            }
        }
    }

    private void writeFloatTexture(MetalGpuTexture texture,float... channels) {
        ByteBuffer bytes=ByteBuffer.allocateDirect(W*H*channels.length*4).order(ByteOrder.nativeOrder());
        for(int p=0;p<W*H;p++) for(float value:channels) bytes.putFloat(value);
        bytes.flip(); encoder.writeToTexture(texture,bytes,0,0,0,0,W,H);
    }

    private void writeFloatPixel(MetalGpuTexture texture,int x,int y,float value) {
        ByteBuffer bytes=ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).putFloat(value);
        bytes.flip(); encoder.writeToTexture(texture,bytes,0,0,x,y,1,1);
    }

    @Test
    void particleIdentityRemappingPreservesSurvivorsAndMarksNewQuadsInvalidOnGpu() throws Exception {
        var source = RenderPipeline.builder().withLocation("motion_contract/particle_membership")
                .withVertexShader("core/particle").withFragmentShader("core/particle")
                .withVertexBinding(0, DefaultVertexFormat.PARTICLE).withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withCull(false).withColorTargetState(ColorTargetState.DEFAULT)
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("Projection", UniformType.UNIFORM_BUFFER)
                        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER).withUniform("Fog", UniformType.UNIFORM_BUFFER)
                        .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("Sampler2", UniformType.COMBINED_IMAGE_SAMPLER).build()).build();
        var sample = new MetalEntityMotionCapture.Sample(991, -9, new Matrix4f(), new Matrix4f(),
                FrameSynthesisContract.ProducerDomain.PARTICLES_WEATHER);
        var format = DefaultVertexFormat.PARTICLE;
        float[] before = particleQuadPositions(.5F, -.55F), current = particleQuadPositions(-.65F, 0, .65F);
        MetalPreviousVertexHistory.reset();
        try {
            for (int frame = 0; frame < 2; frame++) {
                MetalPreviousVertexHistory.beginFrame();
                var token = MetalPreviousVertexHistory.reserveDraw(sample, source);
                float[] positions = frame == 0 ? before : current;
                int quads = positions.length/12;
                MetalPreviousVertexHistory.stageSnapshot(token, new MetalPreviousVertexHistory.Signature(source.getLocation().toString(),
                        format.getElements(), format.getVertexSize(), PrimitiveTopology.QUADS, quads*4, quads*6), positions);
                MetalPreviousVertexHistory.stageParticleIdentities(token, frame == 0 ? java.util.List.of(11L,22L) : java.util.List.of(22L,33L,11L));
                if (frame == 0) MetalPreviousVertexHistory.commitSubmittedFrame();
            }
            var token = new MetalPreviousVertexHistory.DrawToken(new MetalPreviousVertexHistory.DrawKey(
                    new MetalPreviousVertexHistory.ObjectKey(991,-9),0), source.getLocation().toString());
            float[] previous = MetalPreviousVertexHistory.matchedPreviousPositions(token);
            assertNotNull(previous);
            ByteBuffer mesh = ByteBuffer.allocateDirect(12*format.getVertexSize()).order(ByteOrder.nativeOrder());
            ByteBuffer old = ByteBuffer.allocateDirect(previous.length/3*16).order(ByteOrder.nativeOrder());
            assertFalse(MetalPreviousVertexReplay.writePreviousPositions(previous,old));
            old.flip();
            for (int i = 0; i < 12; i++) {
                int p = i*format.getVertexSize();
                for (int c = 0; c < 3; c++) mesh.putFloat(p+4*c, current[i*3+c]);
                mesh.putInt(p+format.getElement("Color").offset(), -1);
            }
            ByteBuffer indices = ByteBuffer.allocateDirect(36).order(ByteOrder.nativeOrder());
            for (int q = 0; q < 3; q++) for (int corner : new int[]{0,1,2,2,3,0}) indices.putShort((short)(q*4+corner));
            indices.flip();
            ByteBuffer dynamic = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
            new Matrix4f().get(0,dynamic); new Matrix4f().get(64,dynamic);
            for (int i = 0; i < 4; i++) dynamic.putFloat(128+i*4,1);
            ByteBuffer projection = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()); new Matrix4f().get(0,projection);
            ByteBuffer fog = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
            fog.putFloat(16,1000).putFloat(20,2000).putFloat(24,1000).putFloat(28,2000);
            ByteBuffer motion = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
            new Matrix4f().scaling(1,-1,1).get(0,motion); new Matrix4f().scaling(1,-1,1).get(64,motion);
            try (var vb = device.createBuffer(() -> "current particle membership",GpuBuffer.USAGE_VERTEX,mesh);
                 var pb = device.createBuffer(() -> "ID-remapped previous particles",GpuBuffer.USAGE_VERTEX,old);
                 var ib = device.createBuffer(() -> "particle quad indices",GpuBuffer.USAGE_INDEX,indices);
                 var du = device.createBuffer(() -> "particle dynamic",GpuBuffer.USAGE_UNIFORM,dynamic);
                 var pr = device.createBuffer(() -> "particle projection",GpuBuffer.USAGE_UNIFORM,projection);
                 var fo = device.createBuffer(() -> "particle fog",GpuBuffer.USAGE_UNIFORM,fog);
                 var mo = device.createBuffer(() -> "particle motion",GpuBuffer.USAGE_UNIFORM,motion);
                 var color = texture("actual particle source",GpuFormat.RGBA8_UNORM,GpuTexture.USAGE_COPY_SRC);
                 var flow = texture("particle correspondence",GpuFormat.RG16_FLOAT,GpuTexture.USAGE_COPY_SRC);
                 var valid = texture("particle correspondence validity",GpuFormat.R8_UNORM,GpuTexture.USAGE_COPY_SRC);
                 var correspondence = texture("particle surface correspondence",GpuFormat.RG32_FLOAT,GpuTexture.USAGE_COPY_SRC);
                 var sampled = texture("particle sprite and lightmap",GpuFormat.RGBA8_UNORM,GpuTexture.USAGE_TEXTURE_BINDING);
                 var cv = device.createTextureView(color); var fv = device.createTextureView(flow);
                 var vv = device.createTextureView(valid); var sv = device.createTextureView(sampled); var zv = device.createTextureView(correspondence);
                 var sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,
                         FilterMode.NEAREST,FilterMode.NEAREST,1,java.util.OptionalDouble.empty())) {
                encoder.clearColorTexture(sampled,new Vector4f(1));
                try (var pass = encoder.createRenderPass(RenderPassDescriptor.builder(() -> "Original Minecraft particle color")
                        .withColorAttachment(cv,Optional.of(new Vector4f(0))).build())) {
                    pass.setPipeline(source); pass.setUniform("Projection",pr.slice()); pass.setUniform("DynamicTransforms",du.slice());
                    pass.setUniform("Fog",fo.slice()); pass.bindTexture("Sampler0",sv,sampler); pass.bindTexture("Sampler2",sv,sampler);
                    pass.setVertexBuffer(0,vb.slice()); pass.setIndexBuffer(ib,IndexType.SHORT); pass.drawIndexed(18,1,0,0,0);
                }
                try (var pass = encoder.createRenderPass(RenderPassDescriptor.builder(() -> "ID-remapped particle motion")
                        .withColorAttachment(fv,Optional.of(new Vector4f(0))).withColorAttachment(vv,Optional.of(new Vector4f(0)))
                        .withColorAttachment(zv,Optional.of(new Vector4f(-1))).build())) {
                    pass.setPipeline(MetalEntityMotionPipeline.forPreviousPositionsWithDepth(source));
                    pass.setUniform("Projection",pr.slice()); pass.setUniform("DynamicTransforms",du.slice()); pass.setUniform("MetallumMotion",mo.slice());
                    pass.bindTexture("Sampler0",sv,sampler); pass.setVertexBuffer(0,vb.slice()); pass.setVertexBuffer(1,pb.slice());
                    pass.setIndexBuffer(ib,IndexType.SHORT); pass.drawIndexed(18,1,0,0,0);
                }
                encoder.submit(); device.waitForSubmittedGpuWork();
                byte[] pixels = readback(color), mask = readback(valid), vectors = readback(flow);
                ByteBuffer values = ByteBuffer.wrap(vectors).order(ByteOrder.nativeOrder());
                Path dir = Path.of("build/agent-evidence/motion-producers/particle_id_membership_change"); Files.createDirectories(dir);
                Files.write(dir.resolve("source.rgba8"),pixels); Files.write(dir.resolve("validity.r8"),mask); Files.write(dir.resolve("motion.rg16f"),vectors);
                int[] counts = new int[3];
                for (int p = 0; p < W*H; p++) {
                    float x = Float.float16ToFloat(values.getShort(p*4)), y = Float.float16ToFloat(values.getShort(p*4+2));
                    assertTrue(Float.isFinite(x) && Float.isFinite(y), "invalid history must not export NaN flow");
                    if (Byte.toUnsignedInt(pixels[p*4+3]) < 128) continue;
                    int group = p%W < W/3 ? 0 : p%W >= W*2/3 ? 2 : 1;
                    counts[group]++;
                    assertEquals(group == 1 ? 64 : 255, Byte.toUnsignedInt(mask[p]));
                    assertEquals(group == 0 ? .1F : group == 2 ? -.15F : 0, x, .001);
                    assertEquals(0,y,.001);
                }
                for (int count : counts) assertTrue(count > 50, "all three real source quads must be visible");
                Files.writeString(dir.resolve("result.json"),"{\"sourcePixelsByParticle\":"+java.util.Arrays.toString(counts)
                        +",\"survivorsReordered\":true,\"newParticleInvalid\":true,\"finiteOutput\":true}");
            }
        } finally { MetalPreviousVertexHistory.reset(); }
    }

    private static float[] particleQuadPositions(float... centers) {
        float[] output = new float[centers.length*12];
        for (int q = 0; q < centers.length; q++) {
            float[] corners = {centers[q]-.125F,-.25F,.5F, centers[q]+.125F,-.25F,.5F,
                    centers[q]+.125F,.25F,.5F, centers[q]-.125F,.25F,.5F};
            System.arraycopy(corners,0,output,q*12,12);
        }
        return output;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("projectiveFixtures")
    void eachFragmentMatchesIndependentPerspectiveCorrespondence(ProjectiveFixture fixture) throws Exception {
        float[][] current = {{-.85F,-.5F,-1.4F},{.9F,-.5F,-4.6F},{-.1F,.85F,-2.4F}};
        float[][] previous = new float[3][3];
        for (int i=0; i<3; i++) {
            previous[i] = current[i].clone();
            if (fixture.deformed()) { previous[i][0] += i == 0 ? -.18F : .07F; previous[i][2] += i == 1 ? 1.1F : -.2F; }
        }
        if (fixture.previousBehind()) previous[0][2] = .95F;
        Matrix4f baseP = new Matrix4f().perspective((float)Math.toRadians(75), (float)W/H, 100, .05F, true);
        Matrix4f rasterP = new Matrix4f(baseP);
        if (fixture.jitter()) rasterP.m20(.012F).m21(-.009F);
        Matrix4f view = new Matrix4f().translate(.02F,-.015F,0).rotateY(-.08F);
        Matrix4f previousView = new Matrix4f().rotateY(.18F).translate(-.05F,.03F,0);
        var source = RenderPipeline.builder().withLocation("motion_contract/"+fixture.name())
                .withVertexShader("core/entity").withFragmentShader("core/entity")
                .withVertexBinding(0, DefaultVertexFormat.ENTITY).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
                .withColorTargetState(ColorTargetState.DEFAULT).withShaderDefine("NO_CARDINAL_LIGHTING")
                .withShaderDefine("NO_OVERLAY").withShaderDefine("EMISSIVE")
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("Projection", UniformType.UNIFORM_BUFFER)
                        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER).withUniform("Fog", UniformType.UNIFORM_BUFFER)
                        .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER).build()).build();
        ByteBuffer mesh = ByteBuffer.allocateDirect(3*DefaultVertexFormat.ENTITY.getVertexSize()).order(ByteOrder.nativeOrder());
        ByteBuffer old = ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder());
        for (int i=0; i<3; i++) {
            int offset = i*DefaultVertexFormat.ENTITY.getVertexSize();
            for (int c=0; c<3; c++) { mesh.putFloat(offset+4*c,current[i][c]); old.putFloat(previous[i][c]); }
            old.putFloat(1);
            mesh.putInt(offset+DefaultVertexFormat.ENTITY.getElement("Color").offset(), -1);
        }
        old.flip();
        ByteBuffer dynamic = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder()); view.get(0,dynamic);new Matrix4f().get(64,dynamic);
        for (int i=0;i<4;i++) dynamic.putFloat(128+i*4,1);
        ByteBuffer projection = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());rasterP.get(0,projection);
        ByteBuffer fog = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        fog.putFloat(16,1000).putFloat(20,2000).putFloat(24,1000).putFloat(28,2000);
        Matrix4f flip = new Matrix4f().scaling(1,-1,1);
        Matrix4f currentMvp = new Matrix4f(flip).mul(baseP).mul(view);
        Matrix4f previousMvp = new Matrix4f(flip).mul(baseP).mul(previousView);
        Matrix4f rasterMvp = new Matrix4f(flip).mul(rasterP).mul(view);
        var oracleVertex = net.minecraft.resources.Identifier.fromNamespaceAndPath("metallum", "oracle/surface_point_vertex");
        var oracleFragment = net.minecraft.resources.Identifier.fromNamespaceAndPath("metallum", "oracle/surface_point_fragment");
        var oracle = RenderPipeline.builder().withLocation("motion_contract/independent_surface_point")
                .withVertexShader(oracleVertex).withFragmentShader(oracleFragment)
                .withVertexBinding(0,DefaultVertexFormat.ENTITY).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
                .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA32_FLOAT,ColorTargetState.WRITE_ALL))
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("OracleRaster",UniformType.UNIFORM_BUFFER).build()).build();
        device.precompilePipeline(oracle,MetalShaderSourceAdapters.overlay(java.util.Map.of(
                oracleVertex,surfacePointVertexSource(),oracleFragment,surfacePointFragmentSource()),device.activeShaderSource()));
        ByteBuffer oracleProjection = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        new Matrix4f(rasterP).mul(view).get(0,oracleProjection);
        ByteBuffer motion = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        new Matrix4f(flip).mul(baseP).mul(new Matrix4f(rasterP).invert()).get(0,motion); previousMvp.get(64,motion);
        try (var vb=device.createBuffer(() -> "projective source",GpuBuffer.USAGE_VERTEX,mesh);
             var pb=device.createBuffer(() -> "projective previous",GpuBuffer.USAGE_VERTEX,old);
             var du=device.createBuffer(() -> "projective dynamic",GpuBuffer.USAGE_UNIFORM,dynamic);
             var pr=device.createBuffer(() -> "projective projection",GpuBuffer.USAGE_UNIFORM,projection);
             var fo=device.createBuffer(() -> "projective fog",GpuBuffer.USAGE_UNIFORM,fog);
             var mo=device.createBuffer(() -> "projective motion",GpuBuffer.USAGE_UNIFORM,motion);
             var op=device.createBuffer(() -> "independent point projection",GpuBuffer.USAGE_UNIFORM,oracleProjection);
             var color=texture("actual projective source",GpuFormat.RGBA8_UNORM,GpuTexture.USAGE_COPY_SRC);
             var flow=texture("projective motion",GpuFormat.RG16_FLOAT,GpuTexture.USAGE_COPY_SRC);
             var valid=texture("projective validity",GpuFormat.R8_UNORM,GpuTexture.USAGE_COPY_SRC);
             var correspondence=texture("previous and current surface Z",GpuFormat.RG32_FLOAT,GpuTexture.USAGE_COPY_SRC);
             var points=texture("independent current surface point",GpuFormat.RGBA32_FLOAT,GpuTexture.USAGE_COPY_SRC);
             var sampled=texture("projective texture",GpuFormat.RGBA8_UNORM,GpuTexture.USAGE_TEXTURE_BINDING);
             var cv=device.createTextureView(color);var fv=device.createTextureView(flow);var vv=device.createTextureView(valid);
             var zv=device.createTextureView(correspondence);var sv=device.createTextureView(sampled);var ov=device.createTextureView(points);
             var sampler=device.createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,FilterMode.NEAREST,FilterMode.NEAREST,1,java.util.OptionalDouble.empty())) {
            encoder.clearColorTexture(sampled,new Vector4f(1));
            try (var pass=encoder.createRenderPass(RenderPassDescriptor.builder(() -> "Actual projective Minecraft color")
                    .withColorAttachment(cv,Optional.of(new Vector4f(0))).build())) {
                pass.setPipeline(source);pass.setUniform("Projection",pr.slice());pass.setUniform("DynamicTransforms",du.slice());
                pass.setUniform("Fog",fo.slice());pass.bindTexture("Sampler0",sv,sampler);pass.setVertexBuffer(0,vb.slice());pass.draw(3,1,0,0);
            }
            try (var pass=encoder.createRenderPass(RenderPassDescriptor.builder(() -> "Independent surface position oracle")
                    .withColorAttachment(ov,Optional.of(new Vector4f(0))).build())) {
                pass.setPipeline(oracle);pass.setUniform("OracleRaster",op.slice());pass.setVertexBuffer(0,vb.slice());pass.draw(3,1,0,0);
            }
            try (var pass=encoder.createRenderPass(RenderPassDescriptor.builder(() -> "Projective motion contract")
                    .withColorAttachment(fv,Optional.of(new Vector4f(0))).withColorAttachment(vv,Optional.of(new Vector4f(0)))
                    .withColorAttachment(zv,Optional.of(new Vector4f(-1))).build())) {
                pass.setPipeline(MetalEntityMotionPipeline.forPreviousPositionsWithDepth(source));pass.setUniform("Projection",pr.slice());
                pass.setUniform("DynamicTransforms",du.slice());pass.setUniform("MetallumMotion",mo.slice());pass.bindTexture("Sampler0",sv,sampler);
                pass.setVertexBuffer(0,vb.slice());pass.setVertexBuffer(1,pb.slice());pass.draw(3,1,0,0);
            }
            encoder.submit();device.waitForSubmittedGpuWork();
            byte[] pixels=readback(color),mask=readback(valid),vectors=readback(flow),depthPairs=readback(correspondence);
            byte[] surfacePoints=readback(points);
            ByteBuffer values=ByteBuffer.wrap(vectors).order(ByteOrder.nativeOrder());
            ByteBuffer zs=ByteBuffer.wrap(depthPairs).order(ByteOrder.nativeOrder());
            ByteBuffer ps=ByteBuffer.wrap(surfacePoints).order(ByteOrder.nativeOrder());
            Vector4f[] rasterClip=project(rasterMvp,current),currentClip=project(currentMvp,current),previousClip=project(previousMvp,previous);
            int covered=0,expectedValid=0,invalid=0,wrongValidity=0,wrongDepth=0,wrongOwnership=0;
            double maxError=0,maxLinearError=0,maxPrecisionExcess=0,maxDepthError=0,maxAnalyticDepthError=0,maxOraclePixelError=0;
            for (int p=0;p<W*H;p++) {
                if (Byte.toUnsignedInt(pixels[p*4+3])<128) {
                    if (Byte.toUnsignedInt(mask[p]) != 0 || zs.getFloat(p*8) != -1 || zs.getFloat(p*8+4) != -1) wrongOwnership++;
                    continue;
                }
                covered++;
                double[] screen=barycentric(2*(p%W+.5)/W-1,1-2*(p/W+.5)/H,rasterClip);
                double[] weights=new double[3];double sum=0;
                for(int i=0;i<3;i++){weights[i]=screen[i]/rasterClip[i].w;sum+=weights[i];}
                for(int i=0;i<3;i++)weights[i]/=sum;
                double[] c=interpolate(currentClip,weights),b=interpolate(previousClip,weights);
                double x=b[0]/b[3]-c[0]/c[3],y=c[1]/c[3]-b[1]/b[3];
                boolean expected=c[3]>1E-6&&b[3]>1E-6&&Double.isFinite(x)&&Double.isFinite(y)&&Math.abs(x)<=32&&Math.abs(y)<=32
                        && Math.abs(b[0]/b[3])<=1 && Math.abs(b[1]/b[3])<=1 && b[2]/b[3]>=0 && b[2]/b[3]<=1;
                boolean actual=Byte.toUnsignedInt(mask[p])>127;
                if(expected!=actual)wrongValidity++;
                if (Byte.toUnsignedInt(mask[p]) != (expected ? 255 : 64)) wrongOwnership++;
                double analyticDepthError = Math.max(Math.abs(zs.getFloat(p*8)-(expected ? b[2]/b[3] : -1)),
                        Math.abs(zs.getFloat(p*8+4)-c[2]/c[3]));
                maxAnalyticDepthError = Math.max(maxAnalyticDepthError,analyticDepthError);
                // The GPU rasterizer quantizes coverage/interpolation. Recover its
                // actual surface point with an independent position-only color pass;
                // map that point through the CPU deformation and previous MVP for Z.
                Vector4f surface = new Vector4f(ps.getFloat(p*16),ps.getFloat(p*16+4),ps.getFloat(p*16+8),1);
                assertEquals(1,ps.getFloat(p*16+12),0,"the independent point pass must cover every source pixel");
                double[] oracleWeights = surfaceWeights(surface,current);
                double[] oldAtSurface = interpolate(previousClip,oracleWeights);
                Vector4f sourceAtSurface = rasterMvp.transform(new Vector4f(surface));
                maxOraclePixelError = Math.max(maxOraclePixelError,Math.max(
                        Math.abs((sourceAtSurface.x/sourceAtSurface.w+1)*W/2-(p%W+.5)),
                        Math.abs((1-sourceAtSurface.y/sourceAtSurface.w)*H/2-(p/W+.5))));
                double depthError = Math.max(Math.abs(zs.getFloat(p*8)-(expected ? oldAtSurface[2]/oldAtSurface[3] : -1)),
                        Math.abs(zs.getFloat(p*8+4)-c[2]/c[3]));
                if (!Double.isFinite(depthError) || depthError > 3e-5) wrongDepth++;
                maxDepthError = Math.max(maxDepthError, depthError);
                if(!expected){invalid++;continue;}
                expectedValid++;
                if (actual) {
                    double errorX = Math.abs(x-Float.float16ToFloat(values.getShort(p*4)))*W/2;
                    double errorY = Math.abs(y-Float.float16ToFloat(values.getShort(p*4+2)))*H/2;
                    maxError = Math.max(maxError, Math.max(errorX, errorY));
                    maxPrecisionExcess = Math.max(maxPrecisionExcess, Math.max(
                            errorX-halfRoundingBudgetPixels(x,W), errorY-halfRoundingBudgetPixels(y,H)));
                }
                if(!fixture.previousBehind()) {
                    double lx=0,ly=0;
                    for(int i=0;i<3;i++){lx+=screen[i]*(previousClip[i].x/previousClip[i].w-currentClip[i].x/currentClip[i].w);
                        ly+=screen[i]*(currentClip[i].y/currentClip[i].w-previousClip[i].y/previousClip[i].w);}
                    maxLinearError=Math.max(maxLinearError,Math.max(Math.abs(x-lx)*W/2,Math.abs(y-ly)*H/2));
                }
            }
            Path dir=Path.of("build/agent-evidence/motion-producers/"+fixture.name());Files.createDirectories(dir);
            Files.write(dir.resolve("source.rgba8"),pixels);Files.write(dir.resolve("validity.r8"),mask);Files.write(dir.resolve("motion.rg16f"),vectors);
            Files.write(dir.resolve("surface-depth.rg32f"),depthPairs);
            Files.write(dir.resolve("independent-surface-point.rgba32f"),surfacePoints);
            Files.writeString(dir.resolve("result.json"),"{\"fixture\":\""+fixture.name()+"\",\"width\":"+W+",\"height\":"+H
                    +",\"sourcePixels\":"+covered+",\"expectedValidPixels\":"+expectedValid+",\"expectedInvalidPixels\":"+invalid
                    +",\"wrongValidityPixels\":"+wrongValidity+",\"wrongOwnershipPixels\":"+wrongOwnership
                    +",\"wrongDepthPixels\":"+wrongDepth+",\"maxDepthError\":"+maxDepthError
                    +",\"maxAnalyticDepthError\":"+maxAnalyticDepthError+",\"maxOraclePixelError\":"+maxOraclePixelError+",\"maxErrorPixels\":"+maxError
                    +",\"maxErrorBeyondHalfPrecisionPixels\":"+maxPrecisionExcess+",\"oldLinearErrorPixels\":"+maxLinearError+"}");
            assertTrue(covered>100&&expectedValid>50);
            if(fixture.previousBehind())assertTrue(invalid>10,"the fixture must cross previous camera w=0");
            else assertTrue(maxLinearError>.2,"the independent oracle must detect old per-vertex NDC interpolation");
            assertEquals(0,wrongValidity,"validity is evaluated per fragment, not from a provoking vertex");
            assertEquals(0,wrongOwnership,"invalid owned history and untouched background must remain distinct");
            assertEquals(0,wrongDepth,"previous Z belongs to this deformed surface point, and current Z to its source raster");
            assertTrue(maxOraclePixelError<.02,"the independent surface point must project to the actual source pixel");
            assertTrue(maxPrecisionExcess<.02,
                    "each pixel must match the independent surface point within RG16F rounding + .02 pixel: "+maxPrecisionExcess);
        }
    }

    static String surfacePointVertexSource() {
        return """
                #version 330
                #extension GL_ARB_separate_shader_objects : require
                layout(location=0) in vec3 Position;
                layout(std140) uniform OracleRaster { mat4 RasterMvp; };
                layout(location=0) out vec3 surfacePoint;
                void main() { gl_Position = RasterMvp * vec4(Position,1.0); surfacePoint = Position; }
                """;
    }

    static String surfacePointFragmentSource() {
        return """
                #version 330
                #extension GL_ARB_separate_shader_objects : require
                layout(location=0) in vec3 surfacePoint;
                layout(location=0) out vec4 point;
                void main() { point = vec4(surfacePoint,1.0); }
                """;
    }

    private static double[] surfaceWeights(Vector4f point,float[][] triangle) {
        double[] edgeA=new double[3],edgeB=new double[3],offset=new double[]{point.x,point.y,point.z};
        double aa=0,ab=0,bb=0,pa=0,pb=0;
        for(int i=0;i<3;i++) {
            edgeA[i]=triangle[1][i]-triangle[0][i]; edgeB[i]=triangle[2][i]-triangle[0][i]; offset[i]-=triangle[0][i];
            aa+=edgeA[i]*edgeA[i]; ab+=edgeA[i]*edgeB[i]; bb+=edgeB[i]*edgeB[i]; pa+=offset[i]*edgeA[i]; pb+=offset[i]*edgeB[i];
        }
        double denominator=aa*bb-ab*ab,a=(bb*pa-ab*pb)/denominator,b=(aa*pb-ab*pa)/denominator;
        return new double[]{1-a-b,a,b};
    }

    private static double halfRoundingBudgetPixels(double value, int extent) {
        // Near previous w=0, valid vectors can approach 32 NDC. A fixed .1-pixel
        // threshold would reject correct RG16F quantization there (up to .25 pixel).
        float roundedMagnitude = Math.abs(Float.float16ToFloat(Float.floatToFloat16((float)value)));
        int bits = Short.toUnsignedInt(Float.floatToFloat16(roundedMagnitude));
        float nextMagnitude = Float.float16ToFloat((short)(bits+1));
        return (nextMagnitude-roundedMagnitude)*.5*extent/2;
    }

    private static Vector4f[] project(Matrix4f matrix,float[][] positions) {
        Vector4f[] out=new Vector4f[3];for(int i=0;i<3;i++)out[i]=matrix.transform(new Vector4f(positions[i][0],positions[i][1],positions[i][2],1));return out;
    }

    private static double[] interpolate(Vector4f[] values,double[] weights) {
        double[] out=new double[4];for(int i=0;i<3;i++){out[0]+=weights[i]*values[i].x;out[1]+=weights[i]*values[i].y;out[2]+=weights[i]*values[i].z;out[3]+=weights[i]*values[i].w;}return out;
    }

    private static double[] barycentric(double x,double y,Vector4f[] clip) {
        double ax=clip[0].x/clip[0].w,ay=clip[0].y/clip[0].w,bx=clip[1].x/clip[1].w,by=clip[1].y/clip[1].w,cx=clip[2].x/clip[2].w,cy=clip[2].y/clip[2].w;
        double d=(by-cy)*(ax-cx)+(cx-bx)*(ay-cy),a=((by-cy)*(x-cx)+(cx-bx)*(y-cy))/d,b=((cy-ay)*(x-cx)+(ax-cx)*(y-cy))/d;return new double[]{a,b,1-a-b};
    }

    @Test
    void transparentOwnershipIsOrderIndependentAndLeavesSceneDepthUntouched() throws Exception {
        var resources = BindGroupLayout.builder().withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER).withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER).build();
        var source = RenderPipeline.builder().withLocation("motion_contract/oit_ownership")
                .withVertexShader("core/entity").withFragmentShader("core/entity")
                .withVertexBinding(0, DefaultVertexFormat.ENTITY).withBindGroupLayout(resources)
                .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
                .withShaderDefine("OIT_ACCUMULATE").withShaderDefine("ALPHA_CUTOUT", .1F)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
        var pipeline = MetalEntityMotionPipeline.forPreviousPositions(source);
        var fixture = new Fixture("ownership", "core/entity", DefaultVertexFormat.ENTITY, true, false, false, false);
        ByteBuffer near = vertices(fixture, false), far = vertices(fixture, false);
        ByteBuffer nearPrevious = ByteBuffer.allocateDirect(72).order(ByteOrder.nativeOrder());
        ByteBuffer farPrevious = ByteBuffer.allocateDirect(72).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 6; i++) {
            int p = i * DefaultVertexFormat.ENTITY.getVertexSize();
            near.putFloat(p+8, .8F); far.putFloat(p+8, .3F);
            nearPrevious.putFloat(near.getFloat(p)-.125F).putFloat(near.getFloat(p+4)).putFloat(.8F);
            farPrevious.putFloat(far.getFloat(p)+.25F).putFloat(far.getFloat(p+4)).putFloat(.3F);
        }
        nearPrevious.flip(); farPrevious.flip();
        ByteBuffer dynamic = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
        new Matrix4f().get(0, dynamic); new Matrix4f().get(64, dynamic);
        for (int i=0; i<4; i++) dynamic.putFloat(128+4*i, 1);
        ByteBuffer projection = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        new Matrix4f().get(0, projection);
        ByteBuffer motion = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        MetalMotionContract.minecraftToMetalFxClip(new Matrix4f()).get(0, motion);
        MetalMotionContract.minecraftToMetalFxClip(new Matrix4f()).get(64, motion);
        int copy = GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;
        try (var n = device.createBuffer(() -> "near", GpuBuffer.USAGE_VERTEX, near);
             var f = device.createBuffer(() -> "far", GpuBuffer.USAGE_VERTEX, far);
             var np = device.createBuffer(() -> "near previous", GpuBuffer.USAGE_VERTEX, nearPrevious);
             var fp = device.createBuffer(() -> "far previous", GpuBuffer.USAGE_VERTEX, farPrevious);
             var du = device.createBuffer(() -> "dynamic", GpuBuffer.USAGE_UNIFORM, dynamic);
             var pr = device.createBuffer(() -> "projection", GpuBuffer.USAGE_UNIFORM, projection);
             var mo = device.createBuffer(() -> "motion", GpuBuffer.USAGE_UNIFORM, motion);
             var scene = texture("source scene depth", GpuFormat.D32_FLOAT, copy);
             var ownership = texture("private motion depth", GpuFormat.D32_FLOAT, copy);
             var flow = texture("motion", GpuFormat.RG16_FLOAT, copy);
             var valid = texture("validity", GpuFormat.R8_UNORM, copy);
             var sampled = texture("alpha", GpuFormat.RGBA8_UNORM, GpuTexture.USAGE_TEXTURE_BINDING);
             var ov = device.createTextureView(ownership); var mv = device.createTextureView(flow);
             var vv = device.createTextureView(valid); var sv = device.createTextureView(sampled);
             var sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                     FilterMode.NEAREST, FilterMode.NEAREST, 1, java.util.OptionalDouble.empty())) {
            encoder.clearColorTexture(sampled, new Vector4f(1));
            for (boolean reversed : new boolean[]{false, true}) {
                encoder.clearDepthTexture(scene, .1);
                encoder.copyTextureToTexture(scene, ownership, 0, 0, 0, 0, 0, W, H);
                try (var pass = encoder.createRenderPass(RenderPassDescriptor.builder(() -> "transparent motion ownership")
                        .withColorAttachment(mv, Optional.of(new Vector4f()))
                        .withColorAttachment(vv, Optional.of(new Vector4f())).withDepthAttachment(ov).build())) {
                    pass.setPipeline(pipeline); pass.setUniform("Projection", pr.slice());
                    pass.setUniform("DynamicTransforms", du.slice()); pass.setUniform("MetallumMotion", mo.slice());
                    pass.bindTexture("Sampler0", sv, sampler);
                    for (boolean drawNear : new boolean[]{reversed, !reversed}) {
                        pass.setVertexBuffer(0, (drawNear ? n : f).slice());
                        pass.setVertexBuffer(1, (drawNear ? np : fp).slice());
                        pass.draw(6, 1, 0, 0);
                    }
                }
                encoder.submit(); device.waitForSubmittedGpuWork();
                byte[] mask = readback(valid), vectors = readback(flow), sceneBytes = readback(scene);
                ByteBuffer values = ByteBuffer.wrap(vectors).order(ByteOrder.nativeOrder());
                ByteBuffer depth = ByteBuffer.wrap(sceneBytes).order(ByteOrder.nativeOrder());
                int covered = 0;
                for (int p=0; p<W*H; p++) {
                    assertEquals(.1F, depth.getFloat(p*4), 1E-6, "motion must not mutate scene depth");
                    if (Byte.toUnsignedInt(mask[p]) < 128) continue;
                    covered++;
                    assertEquals(-.125F, Float.float16ToFloat(values.getShort(p*4)), .001,
                            "nearest transparent surface owns flow for either source submission order");
                }
                assertTrue(covered > 100);
                Path dir = Path.of("build/agent-evidence/motion-producers/transparent_ownership_" + reversed);
                Files.createDirectories(dir); Files.write(dir.resolve("motion.rg16f"), vectors);
                Files.write(dir.resolve("validity.r8"), mask); Files.write(dir.resolve("source-depth.d32"), sceneBytes);
                Files.writeString(dir.resolve("result.json"), "{\"coveredPixels\":"+covered+",\"sourceDepthUnchanged\":true,\"nearestMotionX\":-0.125}");
            }
        }
    }

    private static ByteBuffer vertices(Fixture f, boolean previous) {
        float[][] xy = {{-.4F,-.4F},{.4F,-.4F},{-.4F,.4F},{-.4F,.4F},{.4F,-.4F},{.4F,.4F}};
        int stride = f.format().getVertexSize();
        ByteBuffer out = ByteBuffer.allocateDirect(6*stride).order(ByteOrder.nativeOrder());
        for (int i=0;i<6;i++) {
            float x=xy[i][0],y=xy[i][1];
            if (previous) { x=x*(f.deformed() ? 0.8F : 1)-0.125F; y=y*(f.deformed() ? 1.2F : 1)+0.25F; }
            int p=i*stride+f.format().getElement("Position").offset();
            out.putFloat(p,x).putFloat(p+4,y).putFloat(p+8,.5F);
            var color=f.format().getElement("Color");
            if (color != null) out.putInt(i*stride+color.offset(),0xFFFFFFFF);
        }
        return out;
    }

    private byte[] readback(MetalGpuTexture texture) {
        int size=W*H*texture.pixelSize();
        try (MetalGpuBuffer buffer=(MetalGpuBuffer) device.createBuffer(() -> "motion readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,size)) {
            encoder.copyTextureToBuffer(texture,buffer,0,() -> {},0);
            encoder.submit();device.waitForSubmittedGpuWork();
            byte[] bytes=new byte[size];buffer.currentStorage().get(0,bytes);return bytes;
        }
    }

    private static double[] centroid(byte[] mask) {
        double x=0,y=0,count=0;
        for (int p=0;p<mask.length;p++) if (Byte.toUnsignedInt(mask[p])>127) { x+=p%W+.5;y+=p/W+.5;count++; }
        return count==0 ? new double[]{0,0,0} : new double[]{x/count,y/count,count};
    }
}
