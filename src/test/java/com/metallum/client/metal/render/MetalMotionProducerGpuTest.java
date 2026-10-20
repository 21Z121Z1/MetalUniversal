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
                new Fixture("leash_previous_vertices", "core/rendertype_leash", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP, false, true, false, false),
                new Fixture("lightning_previous_geometry", "core/rendertype_lightning", DefaultVertexFormat.POSITION_COLOR, false, true, false, false)
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
        MetalPreviousVertexHistory.discardFrame();
        MetalPreviousVertexHistory.reset();
        MetalParticleBatchMotion.reset();
        MetalEntityMotionPipeline.clear();
        if (device != null) device.close();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void previousPositionMotionMatchesIndependentlyRasterizedFrames(Fixture f) throws Exception {
        var resources = BindGroupLayout.builder()
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withUniform("Fog",UniformType.UNIFORM_BUFFER)
                .withUniform("Sampler2",UniformType.COMBINED_IMAGE_SAMPLER)
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
        ByteBuffer fog=ByteBuffer.allocateDirect(40).order(ByteOrder.nativeOrder());
        fog.putFloat(16,1000).putFloat(20,2000).putFloat(24,1000).putFloat(28,2000);
        try (GpuBuffer cv = device.createBuffer(() -> "current posed", GpuBuffer.USAGE_VERTEX, current);
             GpuBuffer pv = device.createBuffer(() -> "previous posed", GpuBuffer.USAGE_VERTEX, previous);
             GpuBuffer pp = device.createBuffer(() -> "previous position stream", GpuBuffer.USAGE_VERTEX, previousPositions);
             GpuBuffer du = device.createBuffer(() -> "DynamicTransforms", GpuBuffer.USAGE_UNIFORM, dynamic);
             GpuBuffer pr = device.createBuffer(() -> "Projection", GpuBuffer.USAGE_UNIFORM, projection);
             GpuBuffer mo = device.createBuffer(() -> "MetallumMotion", GpuBuffer.USAGE_UNIFORM, motion);
             GpuBuffer fo = device.createBuffer(() -> "source geometry fog",GpuBuffer.USAGE_UNIFORM,fog);
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
                pass.setUniform("Fog",fo.slice());
                pass.bindTexture("Sampler0", sampledView, sampler);
                pass.bindTexture("Sampler2",sampledView,sampler);
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
                "nearest_transparent_surface", "reactive_material_keeps_correspondence",
                "hand_without_source_depth", "invalid_hand_without_source_depth",
                "standard_z_hand_without_source_depth", "actual_hand_occludes_transparent_hand",
                "overlay_behind_scene", "invalid_overlay_behind_scene", "reactive_overlay_behind_scene",
                "standard_z_overlay_behind_scene", "overlay_world_behind_hand", "unknown_wire_tag",
                "overlay_ignores_nonfinite_unused_scene_depth");
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
        boolean hasHand=false, reactiveMaterial=false;
        Float expectedHandOverride=null;
        Float expectedWorldOverride=null;
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
            case "reactive_material_keeps_correspondence" -> { worldMask=.75F; reactiveMaterial=true; }
            case "hand_without_source_depth", "invalid_hand_without_source_depth" -> {
                currentZ=.85F; handMask=1; previousHand=beforeZ; hasHand=true;
                previousWorld=.95F; expectedHandOverride=.85F;
                if (name.startsWith("invalid_")) { handMask=.25F; beforeZ=-1; reject=invalid=true; }
            }
            case "standard_z_hand_without_source_depth" -> {
                reversed=false; hasHand=true; handZ=previousHand=1; sceneZ=ownershipZ=.8F;
                currentZ=.15F; previousHand=beforeZ=.4F; handMask=1; expectedHandOverride=.15F;
            }
            case "actual_hand_occludes_transparent_hand" -> {
                handZ=.95F; currentZ=.85F; handMask=1; previousHand=beforeZ; hasHand=true;
                reject=invalid=true;
            }
            case "overlay_behind_scene", "invalid_overlay_behind_scene", "reactive_overlay_behind_scene",
                 "overlay_ignores_nonfinite_unused_scene_depth" -> {
                sceneZ=.8F;worldMask=223F/255;expectedWorldOverride=.2F;
                if(name.startsWith("invalid_")) {worldMask=96F/255;beforeZ=-1;reject=invalid=true;}
                if(name.startsWith("reactive_")) {worldMask=159F/255;reactiveMaterial=true;}
                if(name.contains("nonfinite"))sceneZ=Float.NaN;
            }
            case "standard_z_overlay_behind_scene" -> {
                reversed=false;handZ=previousHand=1;sceneZ=.2F;ownershipZ=currentZ=.8F;
                worldMask=223F/255;expectedWorldOverride=.8F;
            }
            case "overlay_world_behind_hand" -> {
                handZ=currentZ=.85F;handMask=1;previousHand=beforeZ;hasHand=true;
                sceneZ=.7F;ownershipZ=.1F;worldMask=223F/255;expectedWorldOverride=.1F;
            }
            case "unknown_wire_tag" -> {worldMask=128F/255;reject=invalid=true;}
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
            float expectedReactive=reject || reactiveMaterial ? 1 : hasHand ? .8F : preserve ? baseReactive : 0;
            assertEquals(expectedReactive*255,Byte.toUnsignedInt(r[probe]),1);
            float expectedWorld=expectedWorldOverride!=null ? expectedWorldOverride
                    : reversed ? Math.max(sceneZ,ownershipZ) : Math.min(sceneZ,ownershipZ);
            ByteBuffer worldValues=ByteBuffer.wrap(world).order(ByteOrder.nativeOrder());
            ByteBuffer handValues=ByteBuffer.wrap(hand).order(ByteOrder.nativeOrder());
            ByteBuffer compositeValues=ByteBuffer.wrap(composite).order(ByteOrder.nativeOrder());
            float expectedHand=expectedHandOverride == null ? handZ : expectedHandOverride;
            for(int p=0;p<W*H;p++) {
                assertEquals(expectedWorld,worldValues.getFloat(p*4),0);
                assertEquals(expectedHand,handValues.getFloat(p*4),0);
                assertEquals(hasHand ? expectedHand : expectedWorld,compositeValues.getFloat(p*4),0);
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

    static Stream<Fixture> currentOwnershipFixtures() {
        return Stream.of(
                new Fixture("entity_first_appearance","core/entity",DefaultVertexFormat.ENTITY,true,false,false,false),
                new Fixture("entity_discarded_first_appearance","core/entity",DefaultVertexFormat.ENTITY,true,false,true,false),
                new Fixture("particle_first_appearance","core/particle",DefaultVertexFormat.PARTICLE,false,false,false,false),
                new Fixture("leash_first_appearance","core/rendertype_leash",DefaultVertexFormat.POSITION_COLOR_LIGHTMAP,false,false,false,false));
    }

    @ParameterizedTest(name = "current ownership: {0}")
    @MethodSource("currentOwnershipFixtures")
    void currentGeometryWithoutHistoryWritesOwnershipAndDepthWithoutInventingMotion(Fixture f) throws Exception {
        var sourceBuilder=RenderPipeline.builder().withLocation("motion_contract/"+f.name())
                .withVertexShader(f.source()).withFragmentShader(f.source()).withVertexBinding(0,f.format())
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("Projection",UniformType.UNIFORM_BUFFER)
                        .withUniform("DynamicTransforms",UniformType.UNIFORM_BUFFER)
                        .withUniform("Sampler2",UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("Sampler0",UniformType.COMBINED_IMAGE_SAMPLER).build());
        if(f.alphaCutout()) sourceBuilder.withShaderDefine("ALPHA_CUTOUT",.5F);
        var source=sourceBuilder.build();
        var sample=new MetalEntityMotionCapture.Sample(808,-22,new Matrix4f(),null);
        ByteBuffer vertices=vertices(f,false), indices=ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder());
        for(short i=0;i<6;i++) indices.putShort(i); indices.flip();
        float[] positions=MetalPreviousVertexHistory.extractPositions(f.format(),java.util.List.of(vertices),6);
        MetalPreviousVertexHistory.beginFrame();
        var token=MetalPreviousVertexHistory.reserveDraw(sample,source);
        MetalPreviousVertexHistory.stageSnapshot(token,new MetalPreviousVertexHistory.Signature(token.pipelineKey(),
                f.format().getElements(),f.format().getVertexSize(),PrimitiveTopology.TRIANGLES,6,6),positions);
        MetalPreviousVertexHistory.stageDrawTransform(token,new MetalPreviousVertexHistory.DrawTransform(new Matrix4f(),new Matrix4f()));
        assertNull(MetalPreviousVertexHistory.matchedPreviousPositions(token));
        assertNull(MetalPreviousVertexReplay.worldTransforms(token));
        var transforms=MetalPreviousVertexReplay.currentOnlyTransforms(token,false); assertNotNull(transforms);
        ByteBuffer dynamic=ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
        new Matrix4f().get(0,dynamic); new Matrix4f().get(64,dynamic);
        for(int i=0;i<4;i++) dynamic.putFloat(128+i*4,1);
        ByteBuffer projection=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()); new Matrix4f().get(0,projection);
        ByteBuffer motion=ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        transforms.currentUnjitteredFromRaster().get(0,motion); transforms.previousFromPosition().get(64,motion);
        try(var current=device.createBuffer(()->"new source geometry",GpuBuffer.USAGE_VERTEX,vertices);
            var index=device.createBuffer(()->"new source indices",GpuBuffer.USAGE_INDEX,indices);
            var du=device.createBuffer(()->"source dynamic",GpuBuffer.USAGE_UNIFORM,dynamic);
            var pr=device.createBuffer(()->"source projection",GpuBuffer.USAGE_UNIFORM,projection);
            var mo=device.createBuffer(()->"explicit invalid history",GpuBuffer.USAGE_UNIFORM,motion);
            var sampled=texture("current source material",GpuFormat.RGBA8_UNORM,GpuTexture.USAGE_TEXTURE_BINDING);
            var flow=texture("current source XY",GpuFormat.RG16_FLOAT,GpuTexture.USAGE_COPY_SRC);
            var valid=texture("current source ownership",GpuFormat.R8_UNORM,GpuTexture.USAGE_COPY_SRC);
            var pair=texture("current source depth",GpuFormat.RG32_FLOAT,GpuTexture.USAGE_COPY_SRC);
            var privateDepth=texture("current ownership raster Z",GpuFormat.D32_FLOAT,GpuTexture.USAGE_COPY_SRC);
            var privateDepthView=device.createTextureView(privateDepth);
            var sampler=device.createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,FilterMode.NEAREST,FilterMode.NEAREST,1,java.util.OptionalDouble.empty());
            var sampledView=device.createTextureView(sampled);var flowView=device.createTextureView(flow);
            var validView=device.createTextureView(valid);var pairView=device.createTextureView(pair)) {
            var info=new net.minecraft.client.renderer.StagedVertexBuffer.ExecuteInfo(current,index,IndexType.SHORT,0,0,6,PrimitiveTopology.TRIANGLES);
            var plan=MetalPreviousVertexReplay.currentOnlyPlan(source,info,token); assertNotNull(plan);
            ByteBuffer history=ByteBuffer.allocateDirect(6*16).order(ByteOrder.nativeOrder());
            assertFalse(MetalPreviousVertexReplay.writePreviousPositions(plan.previousPositions(),history)); history.flip();
            try(var previous=device.createBuffer(()->"finite invalid vertex history",GpuBuffer.USAGE_VERTEX,history)) {
                encoder.clearColorTexture(sampled,new Vector4f(1,1,1,f.alphaHole()?0:1));
                try(var pass=encoder.createRenderPass(RenderPassDescriptor.builder(()->"Owned current surface without history")
                        .withDepthAttachment(privateDepthView,java.util.OptionalDouble.of(0))
                        .withColorAttachment(flowView,Optional.of(new Vector4f(0)))
                        .withColorAttachment(validView,Optional.of(new Vector4f(0)))
                        .withColorAttachment(pairView,Optional.of(new Vector4f(-1)))
                        .withRenderArea(new RenderPass.RenderArea(0,0,W,H)).build())) {
                    pass.setPipeline(MetalEntityMotionPipeline.forPreviousPositionsWithDepth(source));
                    pass.setUniform("Projection",pr.slice());pass.setUniform("DynamicTransforms",du.slice());pass.setUniform("MetallumMotion",mo.slice());
                    pass.bindTexture("Sampler0",sampledView,sampler);pass.setVertexBuffer(0,plan.currentVertexBuffer());
                    pass.bindTexture("Sampler2",sampledView,sampler);
                    pass.setVertexBuffer(1,previous.slice());pass.setIndexBuffer(index,IndexType.SHORT);pass.drawIndexed(6,1,0,plan.replayBaseVertex(),0);
                }
                encoder.submit();device.waitForSubmittedGpuWork();
                byte[] mask=readback(valid), xy=readback(flow), depth=readback(pair);
                ByteBuffer pairs=ByteBuffer.wrap(depth).order(ByteOrder.nativeOrder());
                ByteBuffer vectors=ByteBuffer.wrap(xy).order(ByteOrder.nativeOrder());int covered=0;
                for(int p=0;p<W*H;p++) {
                    int value=Byte.toUnsignedInt(mask[p]);
                    if(value>0) {covered++;assertEquals(96,value,1);assertEquals(-1,pairs.getFloat(p*8),0);assertEquals(.5F,pairs.getFloat(p*8+4),1e-6);}
                    else {assertEquals(-1,pairs.getFloat(p*8),0);assertEquals(-1,pairs.getFloat(p*8+4),0);}
                    assertEquals(0,Float.float16ToFloat(vectors.getShort(p*4)),0);assertEquals(0,Float.float16ToFloat(vectors.getShort(p*4+2)),0);
                }
                Path dir=Path.of("build/agent-evidence/motion-producers/current-ownership/"+f.name());Files.createDirectories(dir);
                Files.write(dir.resolve("ownership.r8"),mask);Files.write(dir.resolve("motion.rg16f"),xy);Files.write(dir.resolve("correspondence.rg32f"),depth);
                Files.writeString(dir.resolve("result.json"),"{\"ownedInvalidPixels\":"+covered+",\"historyInvented\":false}");
                if(f.alphaHole())assertEquals(0,covered);else assertTrue(covered>100);
            }
        }
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
        Object layer = new Object(), unrelatedLayer = new Object();
        var format = DefaultVertexFormat.PARTICLE;
        float[] before = particleQuadPositions(.5F, -.55F), current = particleQuadPositions(-.65F, 0, .65F);
        MetalPreviousVertexHistory.reset();
        MetalParticleBatchMotion.reset();
        try {
            MetalPreviousVertexHistory.DrawToken token = null;
            long acceptedId = 0, acceptedGeneration = 0;
            for (int frame = 0; frame < 2; frame++) {
                MetalParticleBatchMotion.beginFrame();
                if (frame == 1) MetalParticleBatchMotion.beginLayerBatch(unrelatedLayer,
                        new MetalParticleBatchMotion.LayerSignature(false,"minecraft:blocks",source.getLocation().toString(),
                                "minecraft:core/particle",java.util.List.of(91L)));
                var ids = frame == 0 ? java.util.List.of(11L,22L) : java.util.List.of(22L,33L,11L);
                var sample = MetalParticleBatchMotion.beginLayerBatch(layer,new MetalParticleBatchMotion.LayerSignature(
                        false,"minecraft:particles",source.getLocation().toString(),"minecraft:core/particle",ids));
                if (frame == 0) { acceptedId=sample.objectId(); acceptedGeneration=sample.generation(); }
                else {
                    assertTrue(sample.hasPrevious()); assertEquals(acceptedId,sample.objectId());
                    assertEquals(acceptedGeneration,sample.generation(),"An unrelated layer must not reset the particle stream");
                }
                MetalPreviousVertexHistory.beginFrame();
                token = MetalPreviousVertexHistory.reserveDraw(sample, source);
                float[] positions = frame == 0 ? before : current;
                int quads = positions.length/12;
                MetalPreviousVertexHistory.stageSnapshot(token, new MetalPreviousVertexHistory.Signature(source.getLocation().toString(),
                        format.getElements(), format.getVertexSize(), PrimitiveTopology.QUADS, quads*4, quads*6), positions);
                MetalPreviousVertexHistory.stageParticleIdentities(token, ids);
                if (frame == 0) { MetalPreviousVertexHistory.commitSubmittedFrame(); MetalParticleBatchMotion.commitSubmittedFrame(); }
            }
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
                 var privateDepth = texture("particle ownership raster Z",GpuFormat.D32_FLOAT,GpuTexture.USAGE_COPY_SRC);
                 var privateDepthView = device.createTextureView(privateDepth);
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
                        .withDepthAttachment(privateDepthView,java.util.OptionalDouble.of(0))
                        .withColorAttachment(fv,Optional.of(new Vector4f(0))).withColorAttachment(vv,Optional.of(new Vector4f(0)))
                        .withColorAttachment(zv,Optional.of(new Vector4f(-1))).build())) {
                    pass.setPipeline(MetalEntityMotionPipeline.forPreviousPositionsWithDepth(source));
                    pass.setUniform("Projection",pr.slice()); pass.setUniform("DynamicTransforms",du.slice()); pass.setUniform("MetallumMotion",mo.slice());
                    pass.bindTexture("Sampler0",sv,sampler); pass.setVertexBuffer(0,vb.slice()); pass.setVertexBuffer(1,pb.slice());
                    pass.bindTexture("Sampler2",sv,sampler);
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
                    assertEquals(group == 1 ? 96 : 223, Byte.toUnsignedInt(mask[p]));
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
             var privateDepth=texture("projective ownership raster Z",GpuFormat.D32_FLOAT,GpuTexture.USAGE_COPY_SRC);
             var privateDepthView=device.createTextureView(privateDepth);
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
                    .withDepthAttachment(privateDepthView,java.util.OptionalDouble.of(0))
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
                if (Byte.toUnsignedInt(mask[p]) != (expected ? 223 : 96)) wrongOwnership++;
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

    static double halfRoundingBudgetPixels(double value, int extent) {
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

    static Stream<String> shadowVisibilityFixtures() {
        return Stream.of("ordinary_shadow","uv_clamp_low","uv_clamp_high","interpolated_alpha",
                "modulator_zero","transparent_texel");
    }

    @ParameterizedTest(name = "actual shadow visibility: {0}")
    @MethodSource("shadowVisibilityFixtures")
    void shadowMotionCoverageMatchesActualMinecraftFragmentVisibility(String name) throws Exception {
        var source=RenderPipeline.builder().withLocation("motion_contract/shadow_visibility/"+name)
                .withVertexShader("core/rendertype_entity_shadow").withFragmentShader("core/rendertype_entity_shadow")
                .withVertexBinding(0,DefaultVertexFormat.ENTITY).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
                .withColorTargetState(0,new ColorTargetState(Optional.empty(),GpuFormat.RGBA32_FLOAT,ColorTargetState.WRITE_ALL))
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("Projection",UniformType.UNIFORM_BUFFER)
                        .withUniform("DynamicTransforms",UniformType.UNIFORM_BUFFER).withUniform("Fog",UniformType.UNIFORM_BUFFER)
                        .withUniform("Sampler0",UniformType.COMBINED_IMAGE_SAMPLER).build()).build();
        var f=new Fixture(name,"core/rendertype_entity_shadow",DefaultVertexFormat.ENTITY,false,false,false,false);
        ByteBuffer mesh=vertices(f,false);
        float uv=name.equals("uv_clamp_low") ? -.25F : name.equals("uv_clamp_high") ? 1.75F
                : name.equals("transparent_texel") ? .75F : .25F;
        for(int i=0;i<6;i++) {
            int offset=i*DefaultVertexFormat.ENTITY.getVertexSize();
            mesh.putFloat(offset+DefaultVertexFormat.ENTITY.getElement("UV0").offset(),uv);
            mesh.putFloat(offset+DefaultVertexFormat.ENTITY.getElement("UV0").offset()+4,.5F);
            if(name.equals("interpolated_alpha")) mesh.putInt(offset+DefaultVertexFormat.ENTITY.getElement("Color").offset(),
                    new int[]{0x00FFFFFF,-1,0x00FFFFFF,0x00FFFFFF,-1,-1}[i]);
        }
        ByteBuffer old=ByteBuffer.allocateDirect(6*16).order(ByteOrder.nativeOrder());
        assertTrue(MetalPreviousVertexReplay.writePreviousPositions(MetalPreviousVertexHistory.extractPositions(
                DefaultVertexFormat.ENTITY,java.util.List.of(mesh),6),old));old.flip();
        ByteBuffer dynamic=ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
        new Matrix4f().get(0,dynamic);new Matrix4f().get(64,dynamic);
        for(int i=0;i<4;i++)dynamic.putFloat(128+i*4,name.equals("modulator_zero")&&i==3 ? 0 : 1);
        ByteBuffer projection=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());new Matrix4f().get(0,projection);
        ByteBuffer fog=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        fog.putFloat(16,1000).putFloat(20,2000).putFloat(24,1000).putFloat(28,2000);
        ByteBuffer motion=ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        new Matrix4f().scaling(1,-1,1).get(0,motion);new Matrix4f().scaling(1,-1,1).get(64,motion);
        ByteBuffer sprite=ByteBuffer.allocateDirect(W*H*4).order(ByteOrder.nativeOrder());
        for(int y=0;y<H;y++)for(int x=0;x<W;x++)sprite.putInt(x<W/2 ? -1 : 0x00FFFFFF);sprite.flip();
        try(var vb=device.createBuffer(()->"actual shadow vertices",GpuBuffer.USAGE_VERTEX,mesh);
            var pb=device.createBuffer(()->"previous shadow vertices",GpuBuffer.USAGE_VERTEX,old);
            var du=device.createBuffer(()->"shadow dynamic",GpuBuffer.USAGE_UNIFORM,dynamic);
            var pr=device.createBuffer(()->"shadow projection",GpuBuffer.USAGE_UNIFORM,projection);
            var fo=device.createBuffer(()->"shadow fog",GpuBuffer.USAGE_UNIFORM,fog);
            var mo=device.createBuffer(()->"shadow motion",GpuBuffer.USAGE_UNIFORM,motion);
            var sampled=texture("shadow alpha edge",GpuFormat.RGBA8_UNORM,GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_DST);
            var color=texture("actual shadow contribution",GpuFormat.RGBA32_FLOAT,GpuTexture.USAGE_COPY_SRC);
            var flow=texture("shadow XY",GpuFormat.RG16_FLOAT,GpuTexture.USAGE_COPY_SRC);
            var valid=texture("shadow ownership",GpuFormat.R8_UNORM,GpuTexture.USAGE_COPY_SRC);
            var pair=texture("shadow surface depth",GpuFormat.RG32_FLOAT,GpuTexture.USAGE_COPY_SRC);
            var privateDepth=texture("shadow ownership raster Z",GpuFormat.D32_FLOAT,GpuTexture.USAGE_COPY_SRC);
            var privateDepthView=device.createTextureView(privateDepth);
            var sv=device.createTextureView(sampled);var cv=device.createTextureView(color);
            var fv=device.createTextureView(flow);var vv=device.createTextureView(valid);var zv=device.createTextureView(pair);
            var sampler=device.createSampler(AddressMode.REPEAT,AddressMode.REPEAT,FilterMode.NEAREST,FilterMode.NEAREST,1,java.util.OptionalDouble.empty())) {
            encoder.writeToTexture(sampled,sprite,0,0,0,0,W,H);
            try(var pass=encoder.createRenderPass(RenderPassDescriptor.builder(()->"Original Minecraft shadow visibility")
                    .withColorAttachment(cv,Optional.of(new Vector4f(0))).withRenderArea(new RenderPass.RenderArea(0,0,W,H)).build())) {
                pass.setPipeline(source);pass.setUniform("Projection",pr.slice());pass.setUniform("DynamicTransforms",du.slice());
                pass.setUniform("Fog",fo.slice());pass.bindTexture("Sampler0",sv,sampler);pass.setVertexBuffer(0,vb.slice());pass.draw(6,1,0,0);
            }
            try(var pass=encoder.createRenderPass(RenderPassDescriptor.builder(()->"Exact shadow visibility replay")
                    .withDepthAttachment(privateDepthView,java.util.OptionalDouble.of(0))
                    .withColorAttachment(fv,Optional.of(new Vector4f(0))).withColorAttachment(vv,Optional.of(new Vector4f(0)))
                    .withColorAttachment(zv,Optional.of(new Vector4f(-1))).withRenderArea(new RenderPass.RenderArea(0,0,W,H)).build())) {
                pass.setPipeline(MetalEntityMotionPipeline.forPreviousPositionsWithDepth(source));
                pass.setUniform("Projection",pr.slice());pass.setUniform("DynamicTransforms",du.slice());pass.setUniform("MetallumMotion",mo.slice());
                pass.bindTexture("Sampler0",sv,sampler);pass.setVertexBuffer(0,vb.slice());pass.setVertexBuffer(1,pb.slice());pass.draw(6,1,0,0);
            }
            encoder.submit();device.waitForSubmittedGpuWork();
            byte[] rgba=readback(color),mask=readback(valid),xy=readback(flow),z=readback(pair);
            ByteBuffer reference=ByteBuffer.wrap(rgba).order(ByteOrder.nativeOrder());int visible=0,mismatched=0;
            for(int p=0;p<W*H;p++) {
                boolean contributes=reference.getFloat(p*16+12)>0;
                if(contributes)visible++;
                if(contributes!=(Byte.toUnsignedInt(mask[p])>127))mismatched++;
            }
            Path dir=Path.of("build/agent-evidence/motion-producers/shadow-visibility/"+name);Files.createDirectories(dir);
            Files.write(dir.resolve("actual-source.rgba32f"),rgba);Files.write(dir.resolve("motion-ownership.r8"),mask);
            Files.write(dir.resolve("motion.rg16f"),xy);Files.write(dir.resolve("correspondence.rg32f"),z);
            Files.writeString(dir.resolve("result.json"),"{\"visibleSourcePixels\":"+visible+",\"coverageMismatches\":"+mismatched+"}");
            assertEquals(0,mismatched,"Motion coverage must follow actual shadow UV clamp and interpolated alpha/modulator");
            if(name.equals("modulator_zero")||name.equals("transparent_texel"))assertEquals(0,visible);
            else assertTrue(visible>100);
        }
    }

    static Stream<String> materialVisibilityFixtures() {
        return Stream.of("entity_glint_zero_base_alpha", "item_glint_zero_base_alpha",
                "item_special_glint_zero_base_alpha", "entity_glint_cutout_first", "item_special_glint_cutout_first",
                "dissolve_replaces_alpha", "dissolve_hidden", "dissolve_before_modulator", "dissolve_cutout_first",
                "entity_additive_cutout", "item_additive_flag_does_not_clamp", "zero_alpha_blended", "zero_alpha_opaque",
                "entity_texture_matrix_visibility", "item_ignores_entity_texture_matrix", "per_face_dissolve",
                "entity_glint_zero_modulator", "entity_depth_bias_self", "entity_slope_depth_bias_self", "entity_projective_self_depth",
                "entity_negative_slope_depth_bias_self", "entity_projective_slope_depth_bias_self",
                "block_cutout_after_vertex_alpha", "block_cutout_after_modulator", "block_alpha_composite_zero",
                "block_opaque_zero_alpha", "block_visible_offset_and_alpha",
                "lightning_visible", "lightning_vertex_zero_alpha", "lightning_modulator_zero",
                "lightning_fully_fogged", "lightning_fog_partial", "lightning_oit_additive_zero_alpha",
                "lightning_oit_additive_visible", "entity_oit_additive_zero_alpha", "entity_oit_additive_visible",
                "particle_lightmap_zero_alpha", "particle_lightmap_below_cutout", "particle_lightmap_visible");
    }

    @ParameterizedTest(name = "actual material visibility: {0}")
    @MethodSource("materialVisibilityFixtures")
    void materialMotionMatchesActualMinecraftColorAndFragmentSurvival(String name) throws Exception {
        boolean item=name.startsWith("item_"), special=name.contains("special"), glint=name.contains("glint");
        boolean block=name.startsWith("block_"),lightning=name.startsWith("lightning_");
        boolean particle=name.startsWith("particle_");
        boolean oitAdditive=name.contains("oit_additive");
        boolean dissolve=name.contains("dissolve"), matrix=name.contains("texture_matrix");
        boolean blended=!name.equals("zero_alpha_opaque")&&!name.equals("block_opaque_zero_alpha"), perFace=name.equals("per_face_dissolve");
        boolean biased=name.contains("depth_bias"), sloped=name.contains("slope_depth_bias");
        boolean projective=name.contains("projective");
        float slopeBias=sloped ? name.contains("negative") ? -2 : 2 : 0;
        float constantBias=biased ? name.contains("negative") ? -10 : 3 : 0;
        float texAlpha=1, vertexAlpha=1, modAlpha=1, maskAlpha=.5F, cutout=-1, expectedAlpha=1;
        float lightmapAlpha=1;
        boolean visible=true;
        switch(name) {
            case "entity_glint_zero_base_alpha", "item_glint_zero_base_alpha", "item_special_glint_zero_base_alpha" -> {
                texAlpha=0; expectedAlpha=.6F;
            }
            case "entity_glint_cutout_first", "item_special_glint_cutout_first" -> { texAlpha=0;cutout=.1F;visible=false; }
            case "dissolve_replaces_alpha" -> { vertexAlpha=.6F;modAlpha=.5F;expectedAlpha=.5F; }
            case "dissolve_hidden" -> { vertexAlpha=.4F;visible=false; }
            case "dissolve_before_modulator" -> { vertexAlpha=.6F;modAlpha=.1F;expectedAlpha=.1F; }
            case "dissolve_cutout_first" -> { texAlpha=0;cutout=.1F;visible=false; }
            case "entity_additive_cutout" -> { cutout=1;visible=false; }
            case "item_additive_flag_does_not_clamp" -> cutout=1;
            case "zero_alpha_blended" -> { vertexAlpha=0;visible=false; }
            case "zero_alpha_opaque" -> { vertexAlpha=0;expectedAlpha=0; }
            case "item_ignores_entity_texture_matrix" -> visible=false;
            case "per_face_dissolve" -> vertexAlpha=.6F;
            case "entity_glint_zero_modulator" -> { modAlpha=0;expectedAlpha=.6F; }
            case "block_cutout_after_vertex_alpha" -> {vertexAlpha=.05F;cutout=.1F;visible=false;}
            case "block_cutout_after_modulator" -> {modAlpha=.05F;cutout=.1F;visible=false;}
            case "block_alpha_composite_zero" -> {vertexAlpha=0;visible=false;}
            case "block_opaque_zero_alpha" -> {vertexAlpha=0;expectedAlpha=0;}
            case "block_visible_offset_and_alpha" -> {vertexAlpha=.6F;modAlpha=.5F;cutout=.1F;expectedAlpha=.3F;}
            case "lightning_vertex_zero_alpha", "lightning_oit_additive_zero_alpha", "entity_oit_additive_zero_alpha" -> {vertexAlpha=0;visible=false;}
            case "lightning_oit_additive_visible", "entity_oit_additive_visible" -> expectedAlpha=0;
            case "lightning_modulator_zero" -> {modAlpha=0;visible=false;}
            case "lightning_fully_fogged" -> {expectedAlpha=0;visible=false;}
            case "lightning_fog_partial" -> expectedAlpha=1-(float)Math.sqrt(.4F*.4F*2+.5F*.5F);
            case "particle_lightmap_zero_alpha" -> {lightmapAlpha=0;visible=false;}
            case "particle_lightmap_below_cutout" -> {lightmapAlpha=.05F;visible=false;}
            case "particle_lightmap_visible" -> {lightmapAlpha=.5F;expectedAlpha=128F/255;}
        }
        var format=particle ? DefaultVertexFormat.PARTICLE : lightning ? DefaultVertexFormat.POSITION_COLOR : block ? DefaultVertexFormat.BLOCK : special ? DefaultVertexFormat.ENTITY_GLINT_SPECIAL : DefaultVertexFormat.ENTITY;
        String shader=particle ? "core/particle" : lightning ? "core/rendertype_lightning" : block ? "core/block" : item ? "core/item" : "core/entity";
        float offsetX=name.equals("block_visible_offset_and_alpha") ? .125F : 0;
        float offsetY=name.equals("block_visible_offset_and_alpha") ? -.05F : 0;
        float offsetZ=name.equals("block_visible_offset_and_alpha") ? -.1F : 0;
        var resources=BindGroupLayout.builder().withUniform("Projection",UniformType.UNIFORM_BUFFER)
                .withUniform("DynamicTransforms",UniformType.UNIFORM_BUFFER).withUniform("Fog",UniformType.UNIFORM_BUFFER)
                .withUniform("Lighting",UniformType.UNIFORM_BUFFER).withUniform("Globals",UniformType.UNIFORM_BUFFER)
                .withUniform("Sampler0",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("Sampler1",UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("Sampler2",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("GlintSampler",UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("DepthBoundsSampler",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("Coeff0",UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("DissolveMaskSampler",UniformType.COMBINED_IMAGE_SAMPLER).build();
        var builder=RenderPipeline.builder().withLocation("motion_contract/material_source/"+name)
                .withVertexShader(shader).withFragmentShader(shader).withVertexBinding(0,format).withBindGroupLayout(resources)
                .withShaderDefine("NO_OVERLAY").withShaderDefine("EMISSIVE").withShaderDefine("NO_CARDINAL_LIGHTING")
                .withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,true,slopeBias,constantBias))
                .withColorTargetState(0,new ColorTargetState(Optional.empty(),GpuFormat.RGBA32_FLOAT,ColorTargetState.WRITE_ALL));
        if(glint)builder.withShaderDefine("GLINT");
        if(special)builder.withShaderDefine("GLINT_SPECIAL");
        if(dissolve)builder.withShaderDefine("DISSOLVE");
        if(matrix)builder.withShaderDefine("APPLY_TEXTURE_MATRIX");
        if(perFace)builder.withShaderDefine("PER_FACE_LIGHTING");
        if(name.contains("additive"))builder.withShaderDefine("OIT_ADDITIVE");
        if(oitAdditive)builder.withShaderDefine("OIT").withShaderDefine("OIT_ACCUMULATE")
                .withShaderDefine("OIT_COEFF_COUNT",4).withShaderDefine("OIT_WAVELET_RANK",1);
        if(cutout>=0)builder.withShaderDefine("ALPHA_CUTOUT",cutout);
        // The independent oracle stores actual source RGBA without blending and source fragment survival in D32.
        // The motion variant separately receives the intended color blend semantics.
        var original=builder.build();
        var intended=builder.withLocation("motion_contract/material_motion/"+name)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,biased,slopeBias,constantBias))
                .withColorTargetState(0,new ColorTargetState(blended ? Optional.of(oitAdditive ? BlendFunction.ADDITIVE : BlendFunction.TRANSLUCENT) : Optional.empty(),
                        GpuFormat.RGBA32_FLOAT,ColorTargetState.WRITE_ALL)).build();
        var f=new Fixture(name,shader,format,false,false,false,false);
        ByteBuffer mesh=vertices(f,false);
        for(int i=0;i<6;i++) {
            int base=i*format.getVertexSize();
            mesh.putInt(base+format.getElement("Color").offset(),0x00FFFFFF|Math.round(vertexAlpha*255)<<24);
            if(format.getElement("UV0")!=null) {int uv=base+format.getElement("UV0").offset();mesh.putFloat(uv,.25F).putFloat(uv+4,.5F);}
            if(format.getElement("Normal")!=null)mesh.put(base+format.getElement("Normal").offset()+2,(byte)127);
            if(sloped)mesh.putFloat(base+8,.5F+.1F*mesh.getFloat(base));
            if(special) {int extra=base+format.getElement("UV3").offset();mesh.putFloat(extra,.5F).putFloat(extra+4,.5F);}
        }
        ByteBuffer old=ByteBuffer.allocateDirect(6*16).order(ByteOrder.nativeOrder());
        float[] oldPositions=MetalPreviousVertexHistory.extractPositions(format,java.util.List.of(mesh),6);
        if(block)for(int i=0;i<oldPositions.length;i+=3) {oldPositions[i]+=offsetX;oldPositions[i+1]+=offsetY;oldPositions[i+2]+=offsetZ;}
        assertTrue(MetalPreviousVertexReplay.writePreviousPositions(oldPositions,old));old.flip();
        Matrix4f modelView=projective ? new Matrix4f().translate(.1F,-.05F,-2).rotateY(.2F).rotateZ(.1F) : new Matrix4f();
        Matrix4f projectionMatrix=projective ? new Matrix4f().perspective((float)Math.toRadians(70),(float)W/H,.1F,100,true) : new Matrix4f();
        ByteBuffer dynamic=ByteBuffer.allocateDirect(160).order(ByteOrder.nativeOrder());modelView.get(0,dynamic);
        new Matrix4f().translation(matrix ? .5F : 0,0,0).get(64,dynamic);
        for(int i=0;i<4;i++)dynamic.putFloat(128+i*4,i==3 ? modAlpha : 1);
        dynamic.putFloat(144,offsetX).putFloat(148,offsetY).putFloat(152,offsetZ);
        ByteBuffer projection=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());projectionMatrix.get(0,projection);
        ByteBuffer fog=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        fog.putFloat(16,1000).putFloat(20,2000).putFloat(24,1000).putFloat(28,2000);
        if(name.equals("lightning_fully_fogged"))fog.putFloat(16,0).putFloat(20,.1F);
        if(name.equals("lightning_fog_partial"))fog.putFloat(16,0).putFloat(20,1);
        ByteBuffer lighting=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());lighting.putFloat(8,1).putFloat(24,1);
        ByteBuffer globals=ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder());globals.putFloat(12,.6F).putFloat(32,W).putFloat(36,H);
        ByteBuffer motion=ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        new Matrix4f().scaling(1,-1,1).get(0,motion);
        new Matrix4f().scaling(1,-1,1).mul(projectionMatrix).mul(modelView).get(64,motion);
        int copy=GpuTexture.USAGE_COPY_SRC|GpuTexture.USAGE_COPY_DST;
        ByteBuffer raster=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        new MetalPreviousVertexReplay.RasterState(W,H,slopeBias,constantBias).write(raster);raster.flip();
        try(var vb=device.createBuffer(()->"actual material mesh",GpuBuffer.USAGE_VERTEX,mesh);
            var pb=device.createBuffer(()->"previous material positions",GpuBuffer.USAGE_VERTEX,old);
            var du=device.createBuffer(()->"material dynamic",GpuBuffer.USAGE_UNIFORM,dynamic);
            var pr=device.createBuffer(()->"material projection",GpuBuffer.USAGE_UNIFORM,projection);
            var fo=device.createBuffer(()->"material fog",GpuBuffer.USAGE_UNIFORM,fog);
            var li=device.createBuffer(()->"material lighting",GpuBuffer.USAGE_UNIFORM,lighting);
            var gl=device.createBuffer(()->"actual glint globals",GpuBuffer.USAGE_UNIFORM,globals);
            var mo=device.createBuffer(()->"material motion",GpuBuffer.USAGE_UNIFORM,motion);
            var ra=device.createBuffer(()->"actual previous raster bias",GpuBuffer.USAGE_UNIFORM,raster);
            var sampled=texture("material alpha",GpuFormat.RGBA8_UNORM,GpuTexture.USAGE_TEXTURE_BINDING|copy);
            var white=texture("actual lightmap overlay glint",GpuFormat.RGBA8_UNORM,GpuTexture.USAGE_TEXTURE_BINDING);
            var dissolveMask=texture("actual dissolve mask",GpuFormat.RGBA8_UNORM,GpuTexture.USAGE_TEXTURE_BINDING);
            var bounds=texture("actual OIT depth bounds",GpuFormat.RGBA32_FLOAT,GpuTexture.USAGE_TEXTURE_BINDING);
            var coefficients=texture("actual OIT zero absorbance",GpuFormat.RGBA32_FLOAT,GpuTexture.USAGE_TEXTURE_BINDING);
            var color=texture("actual source color",GpuFormat.RGBA32_FLOAT,copy);
            var survival=texture("source fragment survival",GpuFormat.D32_FLOAT,copy|GpuTexture.USAGE_TEXTURE_BINDING);
            var ownership=texture("private motion depth",GpuFormat.D32_FLOAT,copy|GpuTexture.USAGE_TEXTURE_BINDING);
            var flow=texture("material XY",GpuFormat.RG16_FLOAT,copy|GpuTexture.USAGE_TEXTURE_BINDING);
            var valid=texture("material ownership",GpuFormat.R8_UNORM,copy|GpuTexture.USAGE_TEXTURE_BINDING);
            var pair=texture("material depth correspondence",GpuFormat.RG32_FLOAT,copy|GpuTexture.USAGE_TEXTURE_BINDING);
            var sv=device.createTextureView(sampled);var wv=device.createTextureView(white);var dv=device.createTextureView(dissolveMask);
            var bv=device.createTextureView(bounds);var av=device.createTextureView(coefficients);
            var cv=device.createTextureView(color);var sd=device.createTextureView(survival);var od=device.createTextureView(ownership);
            var fv=device.createTextureView(flow);var vv=device.createTextureView(valid);var zv=device.createTextureView(pair);
            var sampler=device.createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,FilterMode.NEAREST,FilterMode.NEAREST,1,java.util.OptionalDouble.empty())) {
            encoder.clearColorTexture(white,new Vector4f(1,1,1,lightmapAlpha));encoder.clearColorTexture(dissolveMask,new Vector4f(1,1,1,maskAlpha));
            encoder.clearColorTexture(bounds,new Vector4f(0,100,0,0));encoder.clearColorTexture(coefficients,new Vector4f(0));
            if(matrix) {
                ByteBuffer pattern=ByteBuffer.allocateDirect(W*H*4).order(ByteOrder.nativeOrder());
                for(int y=0;y<H;y++)for(int x=0;x<W;x++)pattern.putInt(x<W/2 ? 0x00FFFFFF : -1);pattern.flip();
                encoder.writeToTexture(sampled,pattern,0,0,0,0,W,H);
            } else encoder.clearColorTexture(sampled,new Vector4f(1,1,1,texAlpha));
            for(int phase=0;phase<2;phase++) {
                var descriptor=RenderPassDescriptor.builder(()->"Source material and exact motion")
                        .withColorAttachment(phase==0 ? cv : fv,Optional.of(new Vector4f(0)))
                        .withDepthAttachment(phase==0 ? sd : od,java.util.OptionalDouble.of(0))
                        .withRenderArea(new RenderPass.RenderArea(0,0,W,H));
                if(phase==1)descriptor.withColorAttachment(vv,Optional.of(new Vector4f(0)))
                        .withColorAttachment(zv,Optional.of(new Vector4f(-1)));
                try(var pass=encoder.createRenderPass(descriptor.build())) {
                    pass.setPipeline(phase==0 ? original : MetalEntityMotionPipeline.forPreviousPositionsWithDepth(intended));
                    pass.setUniform("Projection",pr.slice());pass.setUniform("DynamicTransforms",du.slice());
                    pass.setUniform("Fog",fo.slice());pass.setUniform("Lighting",li.slice());pass.setUniform("Globals",gl.slice());
                    pass.bindTexture("Sampler0",sv,sampler);pass.bindTexture("Sampler1",wv,sampler);pass.bindTexture("Sampler2",wv,sampler);
                    pass.bindTexture("GlintSampler",wv,sampler);pass.bindTexture("DissolveMaskSampler",dv,sampler);
                    pass.bindTexture("DepthBoundsSampler",bv,sampler);pass.bindTexture("Coeff0",av,sampler);
                    pass.setVertexBuffer(0,vb.slice());
                    if(phase==1) {pass.setUniform("MetallumMotion",mo.slice());pass.setVertexBuffer(1,pb.slice());}
                    if(phase==1 && biased)pass.setUniform("MetallumSurface",ra.slice());
                    if(phase==1)pass.bindTexture("MetallumSourceDepth",sd,sampler);
                    pass.draw(6,1,0,0);
                }
            }
            encoder.submit();device.waitForSubmittedGpuWork();
            byte[] rgba=readback(color),depth=readback(survival),mask=readback(valid),xy=readback(flow),z=readback(pair);
            ByteBuffer reference=ByteBuffer.wrap(rgba).order(ByteOrder.nativeOrder()),survived=ByteBuffer.wrap(depth).order(ByteOrder.nativeOrder());
            ByteBuffer vectors=ByteBuffer.wrap(xy).order(ByteOrder.nativeOrder()),depths=ByteBuffer.wrap(z).order(ByteOrder.nativeOrder());
            int count=0,mismatches=0,wrongAlpha=0,wrongTag=0,wrongMotion=0,wrongDepth=0;
            boolean reactive=glint||dissolve||matrix||lightning;
            for(int p=0;p<W*H;p++) {
                boolean contributes=survived.getFloat(p*4)>0 && (!blended || (oitAdditive
                        ? reference.getFloat(p*16)>0 || reference.getFloat(p*16+4)>0 || reference.getFloat(p*16+8)>0
                        : reference.getFloat(p*16+12)>0));
                if(contributes!=(Byte.toUnsignedInt(mask[p])>31))mismatches++;
                if(!contributes)continue;
                count++;
                float alpha=reference.getFloat(p*16+12),x=Float.float16ToFloat(vectors.getShort(p*4));
                float y=Float.float16ToFloat(vectors.getShort(p*4+2)),before=depths.getFloat(p*8),now=depths.getFloat(p*8+4);
                if(!Float.isFinite(alpha)||Math.abs(alpha-expectedAlpha)>1e-5)wrongAlpha++;
                if(Byte.toUnsignedInt(mask[p])!=(reactive ? 191 : 255))wrongTag++;
                if(!Float.isFinite(x)||!Float.isFinite(y)||Math.abs(x)>1e-6||Math.abs(y)>1e-6)wrongMotion++;
                float expectedBefore=survived.getFloat(p*4);
                float expectedNow=survived.getFloat(p*4);
                if(!Float.isFinite(before)||!Float.isFinite(now)||Math.abs(before-expectedBefore)>3e-6||Math.abs(now-expectedNow)>3e-6)wrongDepth++;
            }
            Path dir=Path.of("build/agent-evidence/motion-producers/material-visibility/"+name);Files.createDirectories(dir);
            Files.write(dir.resolve("actual-source.rgba32f"),rgba);Files.write(dir.resolve("actual-fragment-survival.d32f"),depth);
            Files.write(dir.resolve("motion-ownership.r8"),mask);Files.write(dir.resolve("motion.rg16f"),xy);Files.write(dir.resolve("correspondence.rg32f"),z);
            Files.writeString(dir.resolve("result.json"),"{\"visibleSourcePixels\":"+count+",\"coverageMismatches\":"+mismatches
                    +",\"reactiveMaterial\":"+reactive+",\"specialVertexLayout\":"+special+",\"wrongAlphaPixels\":"+wrongAlpha
                    +",\"wrongTagPixels\":"+wrongTag+",\"wrongMotionPixels\":"+wrongMotion+",\"wrongDepthPixels\":"+wrongDepth+"}");
            assertEquals(0,mismatches,"Motion must follow original fragment survival and the actual alpha blend contribution");
            assertEquals(0,wrongAlpha,"Independent original material alpha");
            assertEquals(0,wrongTag,"Reactive material tags must preserve exact geometry");
            assertEquals(0,wrongMotion,"An unchanged exact surface must retain zero XY");
            assertEquals(0,wrongDepth,"Unchanged geometry must reproduce actual original raster Z including depth bias");
            if(visible)assertTrue(count>100);else assertEquals(0,count);
            if(sloped && !projective)assertStationaryBiasedSurfaceHistory(survival,ownership,flow,valid,pair,depth,dir);
        }
    }

    /** A real biased source raster must survive the native previous-surface rejection test. */
    private void assertStationaryBiasedSurfaceHistory(MetalGpuTexture scene,MetalGpuTexture ownership,
            MetalGpuTexture source,MetalGpuTexture valid,MetalGpuTexture pair,byte[] actualDepth,Path dir) throws Exception {
        int input=GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_SRC|GpuTexture.USAGE_COPY_DST;
        int output=input|MetalGpuTexture.USAGE_SHADER_WRITE;
        int probe=(H/2)*W+W/2;
        MetalNativeBridge.metallum_metalfx_set_reactive_tuning(0,0,0,0,0,1,0);
        try(var oldWorld=texture("actual accepted biased D32",GpuFormat.R32_FLOAT,input);
            var oldHud=texture("no previous HUD",GpuFormat.R32_FLOAT,input);
            var noHand=texture("no HUD ownership",GpuFormat.R8_UNORM,input);
            var merged=texture("biased history XY",GpuFormat.RG16_FLOAT,output);
            var reactive=texture("biased history reactive",GpuFormat.R8_UNORM,output);
            var camera=texture("biased history camera",GpuFormat.RG16_FLOAT,output);
            var disocclusion=texture("biased history rejection",GpuFormat.R8_UNORM,output);
            var nextWorld=texture("biased next world",GpuFormat.R32_FLOAT,output);
            var presented=texture("biased presentation",GpuFormat.R32_FLOAT,output);
            var nextHud=texture("biased next HUD",GpuFormat.R32_FLOAT,output)) {
            ByteBuffer old=ByteBuffer.allocateDirect(actualDepth.length).order(ByteOrder.nativeOrder());old.put(actualDepth).flip();
            encoder.writeToTexture(oldWorld,old,0,0,0,0,W,H);
            writeFloatTexture(oldHud,0);encoder.clearColorTexture(noHand,new Vector4f(0));
            int[] rejected=new int[2];
            for(int control=0;control<2;control++) {
                if(control==1) {
                    ByteBuffer wrong=ByteBuffer.allocateDirect(W*H*8).order(ByteOrder.nativeOrder());
                    ByteBuffer raster=ByteBuffer.wrap(actualDepth).order(ByteOrder.nativeOrder());
                    for(int p=0;p<W*H;p++) {
                        // Independent mesh plane, intentionally omitting the source's raster bias.
                        float geometric=.5F+.1F*(2F*(p%W+.5F)/W-1);
                        wrong.putFloat(geometric).putFloat(raster.getFloat(p*4));
                    }
                    wrong.flip();encoder.writeToTexture(pair,wrong,0,0,0,0,W,H);
                }
                assertTrue(encoder.resolveSurfaceMotionV4(scene,ownership,null,source,valid,noHand,pair,oldWorld,oldHud,
                        merged,reactive,camera,disocclusion,nextWorld,presented,nextHud,
                        new Matrix4f(),new Matrix4f(),new Matrix4f(),new Vector2f(),new Vector2f(),.8F,W,H,true,true,false,true));
                encoder.submit();device.waitForSubmittedGpuWork();
                byte[] result=readback(disocclusion);rejected[control]=Byte.toUnsignedInt(result[probe]);
                Files.write(dir.resolve(control==0 ? "actual-biased-history-disocclusion.r8" : "unbiased-negative-control-disocclusion.r8"),result);
            }
            Files.writeString(dir.resolve("history-result.json"),"{\"actualRasterHistoryRejection\":"+rejected[0]
                    +",\"unbiasedNegativeControlRejection\":"+rejected[1]+",\"depthDilationEnabled\":false}");
            assertEquals(0,rejected[0],"An unchanged biased surface must retain its accepted history");
            assertEquals(255,rejected[1],"The oracle must catch the original uncorrected previous-Z regression");
        } finally {
            MetalNativeBridge.metallum_metalfx_set_reactive_tuning(0,0,0,0,0,1,1);
        }
    }

    static Stream<String> biasedPoseFixtures() {return Stream.of("orthographic","projective");}

    @ParameterizedTest(name="previous raster bias across poses: {0}")
    @MethodSource("biasedPoseFixtures")
    void previousBiasMatchesActualOldRasterAcrossDifferentSourcePoses(String name) throws Exception {
        boolean perspective=name.equals("projective");
        var format=DefaultVertexFormat.ENTITY;
        var source=RenderPipeline.builder().withLocation("motion_contract/biased_pose/"+name)
                .withVertexShader("core/entity").withFragmentShader("core/entity").withVertexBinding(0,format)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
                .withShaderDefine("EMISSIVE").withShaderDefine("NO_OVERLAY").withShaderDefine("NO_CARDINAL_LIGHTING")
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,true,2,3))
                .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_ALL))
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("Projection",UniformType.UNIFORM_BUFFER)
                        .withUniform("DynamicTransforms",UniformType.UNIFORM_BUFFER).withUniform("Fog",UniformType.UNIFORM_BUFFER)
                        .withUniform("Sampler0",UniformType.COMBINED_IMAGE_SAMPLER).build()).build();
        ByteBuffer mesh=vertices(new Fixture(name,"core/entity",format,false,false,false,false),false);
        for(int i=0;i<6;i++) {
            mesh.putFloat(i*format.getVertexSize()+8,.5F+.1F*mesh.getFloat(i*format.getVertexSize()));
            // Independent primitive IDs in original color. Raster snapping can make
            // the two triangles of a geometrically planar quad have distinct Z planes.
            mesh.putInt(i*format.getVertexSize()+format.getElement("Color").offset(),i<3 ? 0xFF0000FF : 0xFF00FF00);
        }
        ByteBuffer oldPositions=ByteBuffer.allocateDirect(96).order(ByteOrder.nativeOrder());
        MetalPreviousVertexReplay.writePreviousPositions(MetalPreviousVertexHistory.extractPositions(format,java.util.List.of(mesh),6),oldPositions);oldPositions.flip();
        Matrix4f[] views={new Matrix4f().translation(-.1F,.05F,perspective ? -2 : 0).rotateY(.35F).rotateZ(.55F).scale(.8F,1.2F,1),
                new Matrix4f().translation(.15F,-.1F,perspective ? -1.7F : 0).rotateY(-.2F).rotateZ(-.3F).scale(1.3F,.7F,1)};
        Matrix4f projection=perspective ? new Matrix4f().perspective(1.2F,(float)W/H,100,.1F,true) : new Matrix4f();
        ByteBuffer[] dynamics=new ByteBuffer[2];
        for(int frame=0;frame<2;frame++) {
            dynamics[frame]=ByteBuffer.allocateDirect(160).order(ByteOrder.nativeOrder());views[frame].get(0,dynamics[frame]);
            new Matrix4f().get(64,dynamics[frame]);for(int c=0;c<4;c++)dynamics[frame].putFloat(128+c*4,1);
        }
        Matrix4f oldMvp=new Matrix4f().scaling(1,-1,1).mul(projection).mul(views[0]);
        ByteBuffer p=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());projection.get(0,p);
        ByteBuffer motion=ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        new Matrix4f().scaling(1,-1,1).get(0,motion);oldMvp.get(64,motion);
        ByteBuffer raster=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        new MetalPreviousVertexReplay.RasterState(W,H,2,3).write(raster);raster.flip();
        ByteBuffer fog=ByteBuffer.allocateDirect(40).order(ByteOrder.nativeOrder());fog.putFloat(16,1000).putFloat(20,2000).putFloat(24,1000).putFloat(28,2000);
        var oracleVertex=net.minecraft.resources.Identifier.fromNamespaceAndPath("metallum","oracle/biased_pose_vertex");
        var oracleFragment=net.minecraft.resources.Identifier.fromNamespaceAndPath("metallum","oracle/biased_pose_fragment");
        var oracle=RenderPipeline.builder().withLocation("motion_contract/biased_pose_point/"+name)
                .withVertexShader(oracleVertex).withFragmentShader(oracleFragment).withVertexBinding(0,format)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
                .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA32_FLOAT,ColorTargetState.WRITE_ALL))
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("OracleRaster",UniformType.UNIFORM_BUFFER).build()).build();
        device.precompilePipeline(oracle,MetalShaderSourceAdapters.overlay(java.util.Map.of(
                oracleVertex,surfacePointVertexSource(),oracleFragment,surfacePointFragmentSource()),device.activeShaderSource()));
        ByteBuffer pointMatrix=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());new Matrix4f(projection).mul(views[1]).get(0,pointMatrix);
        int copy=GpuTexture.USAGE_COPY_SRC|GpuTexture.USAGE_COPY_DST;
        byte[][] colors=new byte[2][],depths=new byte[2][];
        try(var vb=device.createBuffer(()->"biased pose mesh",GpuBuffer.USAGE_VERTEX,mesh);
            var pb=device.createBuffer(()->"biased old positions",GpuBuffer.USAGE_VERTEX,oldPositions);
            var d0=device.createBuffer(()->"old biased pose",GpuBuffer.USAGE_UNIFORM,dynamics[0]);
            var d1=device.createBuffer(()->"current biased pose",GpuBuffer.USAGE_UNIFORM,dynamics[1]);
            var pr=device.createBuffer(()->"biased projection",GpuBuffer.USAGE_UNIFORM,p);
            var fo=device.createBuffer(()->"biased fog",GpuBuffer.USAGE_UNIFORM,fog);
            var mo=device.createBuffer(()->"old biased source mapping",GpuBuffer.USAGE_UNIFORM,motion);
            var ra=device.createBuffer(()->"old hardware bias",GpuBuffer.USAGE_UNIFORM,raster);
            var op=device.createBuffer(()->"independent current point mapping",GpuBuffer.USAGE_UNIFORM,pointMatrix);
            var sampled=texture("biased material",GpuFormat.RGBA8_UNORM,GpuTexture.USAGE_TEXTURE_BINDING);
            var color=texture("original biased color",GpuFormat.RGBA8_UNORM,copy);
            var scene=texture("original biased source Z",GpuFormat.D32_FLOAT,copy|GpuTexture.USAGE_TEXTURE_BINDING);
            var ownership=texture("biased private Z",GpuFormat.D32_FLOAT,copy);
            var points=texture("independent biased surface point",GpuFormat.RGBA32_FLOAT,copy);
            var flow=texture("biased pose XY",GpuFormat.RG16_FLOAT,copy);var valid=texture("biased pose validity",GpuFormat.R8_UNORM,copy);
            var pair=texture("biased pose correspondence",GpuFormat.RG32_FLOAT,copy);
            var sv=device.createTextureView(sampled);var cv=device.createTextureView(color);var sd=device.createTextureView(scene);
            var od=device.createTextureView(ownership);var ov=device.createTextureView(points);
            var fv=device.createTextureView(flow);var vv=device.createTextureView(valid);var zv=device.createTextureView(pair);
            var sampler=device.createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,FilterMode.NEAREST,FilterMode.NEAREST,1,java.util.OptionalDouble.empty())) {
            encoder.clearColorTexture(sampled,new Vector4f(1));
            for(int frame=0;frame<2;frame++) {
                try(var pass=encoder.createRenderPass(RenderPassDescriptor.builder(()->"Independent original biased source frame")
                        .withColorAttachment(cv,Optional.of(new Vector4f(0))).withDepthAttachment(sd,java.util.OptionalDouble.of(0)).build())) {
                    pass.setPipeline(source);pass.setUniform("Projection",pr.slice());pass.setUniform("DynamicTransforms",(frame==0 ? d0 : d1).slice());
                    pass.setUniform("Fog",fo.slice());pass.bindTexture("Sampler0",sv,sampler);pass.setVertexBuffer(0,vb.slice());pass.draw(6,1,0,0);
                }
                encoder.submit();device.waitForSubmittedGpuWork();colors[frame]=readback(color);depths[frame]=readback(scene);
            }
            try(var pass=encoder.createRenderPass(RenderPassDescriptor.builder(()->"Independent current surface point")
                    .withColorAttachment(ov,Optional.of(new Vector4f(0))).build())) {
                pass.setPipeline(oracle);pass.setUniform("OracleRaster",op.slice());pass.setVertexBuffer(0,vb.slice());pass.draw(6,1,0,0);
            }
            encoder.copyTextureToTexture(scene,ownership,0,0,0,0,0,W,H);
            try(var pass=encoder.createRenderPass(RenderPassDescriptor.builder(()->"Actual previous biased source correspondence")
                    .withColorAttachment(fv,Optional.of(new Vector4f(0))).withColorAttachment(vv,Optional.of(new Vector4f(0)))
                    .withColorAttachment(zv,Optional.of(new Vector4f(-1))).withDepthAttachment(od).build())) {
                pass.setPipeline(MetalEntityMotionPipeline.forPreviousPositionsWithDepth(source));
                pass.setUniform("Projection",pr.slice());pass.setUniform("DynamicTransforms",d1.slice());
                pass.setUniform("MetallumMotion",mo.slice());pass.setUniform("MetallumSurface",ra.slice());
                pass.bindTexture("Sampler0",sv,sampler);pass.bindTexture("MetallumSourceDepth",sd,sampler);
                pass.setVertexBuffer(0,vb.slice());pass.setVertexBuffer(1,pb.slice());pass.draw(6,1,0,0);
            }
            encoder.submit();device.waitForSubmittedGpuWork();
            RasterDepthPlane[] before=new RasterDepthPlane[2],now=new RasterDepthPlane[2];
            for(int triangle=0;triangle<2;triangle++) {
                before[triangle]=fitRasterDepthPlane(colors[0],depths[0],W,H,triangle);
                now[triangle]=fitRasterDepthPlane(colors[1],depths[1],W,H,triangle);
            }
            byte[] mask=readback(valid),z=readback(pair),pointBytes=readback(points);
            ByteBuffer zs=ByteBuffer.wrap(z).order(ByteOrder.nativeOrder()),ps=ByteBuffer.wrap(pointBytes).order(ByteOrder.nativeOrder());
            ByteBuffer currentZ=ByteBuffer.wrap(depths[1]).order(ByteOrder.nativeOrder());
            int checked=0,wrong=0;double maxError=0,wrongCurrentSlopeError=0;
            for(int pixel=0;pixel<W*H;pixel++)if(Byte.toUnsignedInt(mask[pixel])>127) {
                checked++;assertEquals(1,ps.getFloat(pixel*16+12),0);
                Vector4f surface=new Vector4f(ps.getFloat(pixel*16),ps.getFloat(pixel*16+4),ps.getFloat(pixel*16+8),1);
                int triangle=surface.x+surface.y<=0 ? 0 : 1;
                Vector4f oldPoint=oldMvp.transform(surface);
                double oldX=(oldPoint.x/oldPoint.w+1)*W/2,oldY=(1-oldPoint.y/oldPoint.w)*H/2;
                double expected=before[triangle].at(oldX,oldY);
                double error=Math.max(Math.abs(zs.getFloat(pixel*8)-expected),Math.abs(zs.getFloat(pixel*8+4)-currentZ.getFloat(pixel*4)));
                if(!Double.isFinite(error)||error>3e-5)wrong++;maxError=Math.max(maxError,error);
                double geometric=oldPoint.z/oldPoint.w,unit=Math.scalb(1,Math.getExponent(geometric)-23);
                double wrongBias=2*Math.max(Math.abs(now[triangle].xSlope()),Math.abs(now[triangle].ySlope()))+3*unit;
                wrongCurrentSlopeError=Math.max(wrongCurrentSlopeError,Math.abs(geometric+wrongBias-expected));
            }
            Path dir=Path.of("build/agent-evidence/motion-producers/previous-raster-bias/"+name);Files.createDirectories(dir);
            Files.write(dir.resolve("old-original.rgba8"),colors[0]);Files.write(dir.resolve("old-original.d32f"),depths[0]);
            Files.write(dir.resolve("current-original.rgba8"),colors[1]);Files.write(dir.resolve("current-original.d32f"),depths[1]);
            Files.write(dir.resolve("correspondence.rg32f"),z);Files.write(dir.resolve("surface-point.rgba32f"),pointBytes);
            Files.writeString(dir.resolve("result.json"),"{\"checkedPixels\":"+checked+",\"wrongDepthPixels\":"+wrong
                    +",\"maxDepthError\":"+maxError+",\"maxOldPlaneResidual\":"+Math.max(before[0].maxResidual(),before[1].maxResidual())
                    +",\"independentPrimitivePlanes\":2"
                    +",\"wrongCurrentSlopeNegativeControlError\":"+wrongCurrentSlopeError+"}");
            for(var plane:before)assertTrue(plane.maxResidual()<3e-6);
            assertTrue(checked>40);assertEquals(0,wrong);
            assertTrue(wrongCurrentSlopeError>3e-4,"The independent old raster must distinguish using the current slope");
        }
    }

    record RasterDepthPlane(double xSlope,double ySlope,double constant,double maxResidual) {
        double at(double x,double y) {return xSlope*x+ySlope*y+constant;}
    }

    /** Least-squares plane fitted only from original GPU color coverage and D32 readback. */
    static RasterDepthPlane fitRasterDepthPlane(byte[] rgba,byte[] d32,int width,int height) {
        return fitRasterDepthPlane(rgba,d32,width,height,-1);
    }

    static RasterDepthPlane fitRasterDepthPlane(byte[] rgba,byte[] d32,int width,int height,int primitiveChannel) {
        ByteBuffer z=ByteBuffer.wrap(d32).order(ByteOrder.nativeOrder());
        double mx=0,my=0,mz=0;int n=0;
        for(int p=0;p<width*height;p++)if(planeSample(rgba,p,primitiveChannel)) {n++;mx+=p%width+.5;my+=p/width+.5;mz+=z.getFloat(p*4);}
        if(n<3)throw new IllegalArgumentException("Insufficient original raster samples");mx/=n;my/=n;mz/=n;
        double xx=0,xy=0,yy=0,xz=0,yz=0;
        for(int p=0;p<width*height;p++)if(planeSample(rgba,p,primitiveChannel)) {
            double x=p%width+.5-mx,y=p/width+.5-my,depth=z.getFloat(p*4)-mz;
            xx+=x*x;xy+=x*y;yy+=y*y;xz+=x*depth;yz+=y*depth;
        }
        double determinant=xx*yy-xy*xy;if(!(determinant>0))throw new IllegalArgumentException("Degenerate original raster plane");
        double a=(xz*yy-yz*xy)/determinant,b=(yz*xx-xz*xy)/determinant,c=mz-a*mx-b*my,residual=0;
        for(int p=0;p<width*height;p++)if(planeSample(rgba,p,primitiveChannel))
            residual=Math.max(residual,Math.abs(a*(p%width+.5)+b*(p/width+.5)+c-z.getFloat(p*4)));
        return new RasterDepthPlane(a,b,c,residual);
    }

    private static boolean planeSample(byte[] rgba,int pixel,int primitiveChannel) {
        return Byte.toUnsignedInt(rgba[pixel*4+3])>127
                && (primitiveChannel<0 || Byte.toUnsignedInt(rgba[pixel*4+primitiveChannel])>127);
    }

    @Test
    void blockMotionUsesPreviousAcceptedModelOffsetAgainstActualMinecraftFrames() throws Exception {
        var source=RenderPipeline.builder().withLocation("motion_contract/block_offset_history")
                .withVertexShader("core/block").withFragmentShader("core/block")
                .withVertexBinding(0,DefaultVertexFormat.BLOCK).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
                .withShaderDefine("ALPHA_CUTOUT",.1F).withDepthStencilState(DepthStencilState.DEFAULT)
                .withColorTargetState(0,new ColorTargetState(Optional.empty(),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_ALL))
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("Projection",UniformType.UNIFORM_BUFFER)
                        .withUniform("DynamicTransforms",UniformType.UNIFORM_BUFFER).withUniform("Fog",UniformType.UNIFORM_BUFFER)
                        .withUniform("Sampler0",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("Sampler2",UniformType.COMBINED_IMAGE_SAMPLER).build()).build();
        var fixture=new Fixture("block_offset","core/block",DefaultVertexFormat.BLOCK,true,true,false,false);
        ByteBuffer[] mesh={vertices(fixture,true),vertices(fixture,false)},dynamic=new ByteBuffer[2];
        byte[][] dynamicBytes=new byte[2][],masks=new byte[2][],sourceDepths=new byte[2][];
        float[][] offsets={{-.1F,.08F,-.1F},{.12F,-.05F,.1F}};
        for(int frame=0;frame<2;frame++) {
            dynamic[frame]=ByteBuffer.allocateDirect(160).order(ByteOrder.nativeOrder());
            new Matrix4f().get(0,dynamic[frame]);new Matrix4f().get(64,dynamic[frame]);
            for(int c=0;c<4;c++)dynamic[frame].putFloat(128+c*4,1);
            for(int c=0;c<3;c++)dynamic[frame].putFloat(144+c*4,offsets[frame][c]);
            dynamicBytes[frame]=new byte[160];dynamic[frame].get(0,dynamicBytes[frame]);
        }
        ByteBuffer projection=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());new Matrix4f().get(0,projection);
        ByteBuffer fog=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        fog.putFloat(16,1000).putFloat(20,2000).putFloat(24,1000).putFloat(28,2000);
        int copy=GpuTexture.USAGE_COPY_SRC|GpuTexture.USAGE_COPY_DST;
        var sample=new MetalEntityMotionCapture.Sample(81013,8,new Matrix4f(),new Matrix4f());
        MetalPreviousVertexHistory.DrawToken token=null;
        try(var v0=device.createBuffer(()->"previous block source",GpuBuffer.USAGE_VERTEX,mesh[0]);
            var v1=device.createBuffer(()->"current block source",GpuBuffer.USAGE_VERTEX,mesh[1]);
            var d0=device.createBuffer(()->"previous block offset",GpuBuffer.USAGE_UNIFORM,dynamic[0]);
            var d1=device.createBuffer(()->"current block offset",GpuBuffer.USAGE_UNIFORM,dynamic[1]);
            var pr=device.createBuffer(()->"block projection",GpuBuffer.USAGE_UNIFORM,projection);
            var fo=device.createBuffer(()->"block fog",GpuBuffer.USAGE_UNIFORM,fog);
            var sampled=texture("block material and lightmap",GpuFormat.RGBA8_UNORM,GpuTexture.USAGE_TEXTURE_BINDING);
            var color=texture("original block source color",GpuFormat.RGBA8_UNORM,copy);
            var scene=texture("original block source Z",GpuFormat.D32_FLOAT,copy|GpuTexture.USAGE_TEXTURE_BINDING);
            var ownership=texture("block private depth",GpuFormat.D32_FLOAT,copy);
            var flow=texture("block XY",GpuFormat.RG16_FLOAT,copy);var valid=texture("block exact validity",GpuFormat.R8_UNORM,copy);
            var pair=texture("block previous/current Z",GpuFormat.RG32_FLOAT,copy);
            var sv=device.createTextureView(sampled);var cv=device.createTextureView(color);var sd=device.createTextureView(scene);
            var od=device.createTextureView(ownership);var fv=device.createTextureView(flow);var vv=device.createTextureView(valid);var zv=device.createTextureView(pair);
            var sampler=device.createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,FilterMode.NEAREST,FilterMode.NEAREST,1,java.util.OptionalDouble.empty())) {
            encoder.clearColorTexture(sampled,new Vector4f(1));
            for(int frame=0;frame<2;frame++) {
                MetalPreviousVertexHistory.beginFrame();token=MetalPreviousVertexHistory.reserveDraw(sample,source);
                MetalPreviousVertexHistory.stageSnapshot(token,new MetalPreviousVertexHistory.Signature(token.pipelineKey(),
                        DefaultVertexFormat.BLOCK.getElements(),DefaultVertexFormat.BLOCK.getVertexSize(),PrimitiveTopology.TRIANGLES,6,6),
                        MetalPreviousVertexHistory.extractPositions(DefaultVertexFormat.BLOCK,java.util.List.of(mesh[frame]),6));
                MetalPreviousVertexHistory.stageDrawTransform(token,new MetalPreviousVertexHistory.DrawTransform(new Matrix4f(),new Matrix4f()));
                MetalPreviousVertexHistory.stageSourceUniforms(token,MetalSourceUniforms.decode(dynamicBytes[frame],null));
                MetalPreviousVertexHistory.stageSurface(token,new MetalPreviousVertexHistory.SurfaceIdentity(source,java.util.Map.of()));
                try(var pass=encoder.createRenderPass(RenderPassDescriptor.builder(()->"Independent original Minecraft block frame")
                        .withColorAttachment(cv,Optional.of(new Vector4f(0))).withDepthAttachment(sd,java.util.OptionalDouble.of(0))
                        .withRenderArea(new RenderPass.RenderArea(0,0,W,H)).build())) {
                    pass.setPipeline(source);pass.setUniform("Projection",pr.slice());pass.setUniform("DynamicTransforms",(frame==0 ? d0 : d1).slice());
                    pass.setUniform("Fog",fo.slice());pass.bindTexture("Sampler0",sv,sampler);pass.bindTexture("Sampler2",sv,sampler);
                    pass.setVertexBuffer(0,(frame==0 ? v0 : v1).slice());pass.draw(6,1,0,0);
                }
                encoder.submit();device.waitForSubmittedGpuWork();byte[] rgba=readback(color);
                masks[frame]=new byte[W*H];for(int p=0;p<W*H;p++)masks[frame][p]=rgba[p*4+3];
                sourceDepths[frame]=readback(scene);
                if(frame==0)MetalPreviousVertexHistory.commitSubmittedFrame();
            }
            var transforms=MetalPreviousVertexReplay.worldTransforms(token);assertNotNull(transforms);
            float[] positions=MetalPreviousVertexReplay.previousPositionsForSource(source,token);assertNotNull(positions);
            ByteBuffer old=ByteBuffer.allocateDirect(6*16).order(ByteOrder.nativeOrder());assertTrue(MetalPreviousVertexReplay.writePreviousPositions(positions,old));old.flip();
            ByteBuffer motion=ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
            transforms.currentUnjitteredFromRaster().get(0,motion);transforms.previousFromPosition().get(64,motion);
            try(var previous=device.createBuffer(()->"accepted block offset positions",GpuBuffer.USAGE_VERTEX,old);
                var mo=device.createBuffer(()->"accepted block transform",GpuBuffer.USAGE_UNIFORM,motion)) {
                encoder.copyTextureToTexture(scene,ownership,0,0,0,0,0,W,H);
                try(var pass=encoder.createRenderPass(RenderPassDescriptor.builder(()->"Production exact block offset replay")
                        .withColorAttachment(fv,Optional.of(new Vector4f(0))).withColorAttachment(vv,Optional.of(new Vector4f(0)))
                        .withColorAttachment(zv,Optional.of(new Vector4f(-1))).withDepthAttachment(od)
                        .withRenderArea(new RenderPass.RenderArea(0,0,W,H)).build())) {
                    pass.setPipeline(MetalEntityMotionPipeline.forPreviousPositionsWithDepth(source));pass.setUniform("Projection",pr.slice());
                    pass.setUniform("DynamicTransforms",d1.slice());pass.setUniform("MetallumMotion",mo.slice());
                    pass.bindTexture("Sampler0",sv,sampler);pass.bindTexture("MetallumSourceDepth",sd,sampler);
                    pass.setVertexBuffer(0,v1.slice());pass.setVertexBuffer(1,previous.slice());pass.draw(6,1,0,0);
                }
                encoder.submit();device.waitForSubmittedGpuWork();
                byte[] mask=readback(valid),xy=readback(flow),depth=readback(pair);ByteBuffer vectors=ByteBuffer.wrap(xy).order(ByteOrder.nativeOrder());
                ByteBuffer depths=ByteBuffer.wrap(depth).order(ByteOrder.nativeOrder());int covered=0,mismatches=0,wrongDepth=0;double x=0,y=0;
                for(int p=0;p<W*H;p++) {
                    boolean reference=Byte.toUnsignedInt(masks[1][p])>127;
                    if(reference!=(Byte.toUnsignedInt(mask[p])==255))mismatches++;
                    if(!reference)continue;covered++;
                    float vx=Float.float16ToFloat(vectors.getShort(p*4)),vy=Float.float16ToFloat(vectors.getShort(p*4+2));
                    x+=vx*W/2;y+=vy*H/2;
                    if(!Float.isFinite(depths.getFloat(p*8))||!Float.isFinite(depths.getFloat(p*8+4))
                            ||Math.abs(depths.getFloat(p*8)-.4F)>3e-6||Math.abs(depths.getFloat(p*8+4)-.6F)>3e-6)wrongDepth++;
                }
                if(covered>0) {x/=covered;y/=covered;}
                double[] before=centroid(masks[0]),after=centroid(masks[1]);
                Path dir=Path.of("build/agent-evidence/motion-producers/block-offset-history");Files.createDirectories(dir);
                Files.write(dir.resolve("previous-source.r8"),masks[0]);Files.write(dir.resolve("current-source.r8"),masks[1]);
                Files.write(dir.resolve("previous-source.d32f"),sourceDepths[0]);Files.write(dir.resolve("current-source.d32f"),sourceDepths[1]);
                Files.write(dir.resolve("motion.rg16f"),xy);Files.write(dir.resolve("ownership.r8"),mask);Files.write(dir.resolve("correspondence.rg32f"),depth);
                Files.writeString(dir.resolve("result.json"),"{\"coveredPixels\":"+covered+",\"coverageMismatches\":"+mismatches
                        +",\"wrongDepthPixels\":"+wrongDepth+",\"motionPixels\":["+x+","+y+"],\"sourceDisplacementPixels\":["
                        +(before[0]-after[0])+","+(before[1]-after[1])+"]}");
                assertTrue(covered>100&&before[2]>100);assertEquals(0,mismatches);assertEquals(0,wrongDepth);
                assertEquals(before[0]-after[0],x,.6);assertEquals(before[1]-after[1],y,.6);
            }
        }
    }

    static Stream<String> sourceDepthPolicyFixtures() {
        return Stream.of("no_depth_state", "missing_depth_attachment", "always_pass", "later_visible_normal",
                "missing_depth_attachment_with_write", "missing_depth_attachment_with_write_and_bias",
                "standard_z_hidden_normal", "overlay_after_normal", "strict_hidden_equal", "different_source_attachment");
    }

    @Test
    void actualFragmentDepthOutputRequiresAnAttachmentEvenWhenRasterWritesAreDisabled() {
        var vertex=net.minecraft.resources.Identifier.fromNamespaceAndPath("metallum","oracle/depth_output_vertex");
        var fragment=net.minecraft.resources.Identifier.fromNamespaceAndPath("metallum","oracle/depth_output_fragment");
        var source=RenderPipeline.builder().withLocation("motion_contract/actual_fragment_depth_output")
                .withVertexShader(vertex).withFragmentShader(fragment).withCull(false)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS,false))
                .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_ALL)).build();
        var shaders=MetalShaderSourceAdapters.overlay(java.util.Map.of(vertex,"""
                #version 330
                void main(){gl_Position=vec4(0,0,0.5,1);}
                """,fragment,"""
                #version 330
                #extension GL_ARB_separate_shader_objects : require
                layout(location=0) out vec4 color;
                void main(){color=vec4(1);gl_FragDepth=.25;}
                """),device.activeShaderSource());
        var compiled=(MetalCompiledRenderPipeline)device.precompilePipeline(source,shaders);
        assertTrue(compiled.isValid());
        assertFalse(MetalNativeBridge.isNullHandle(compiled.getNativePipeline(
                com.metallum.client.metal.render.mtl.MTLPixelFormat.Depth32Float,
                com.metallum.client.metal.render.mtl.MTLPixelFormat.Invalid)));
        assertThrows(IllegalArgumentException.class,()->compiled.getNativePipeline(
                com.metallum.client.metal.render.mtl.MTLPixelFormat.Invalid,
                com.metallum.client.metal.render.mtl.MTLPixelFormat.Invalid));
    }

    @ParameterizedTest(name = "actual source depth policy: {0}")
    @MethodSource("sourceDepthPolicyFixtures")
    void overlayAndLaterGeometryFollowActualSourceDepthAndDrawOrder(String name) throws Exception {
        boolean standard=name.startsWith("standard_"), missing=name.startsWith("missing_depth_attachment");
        boolean lastOverlay=name.equals("overlay_after_normal");
        float sceneZ=standard ? .2F : name.equals("different_source_attachment") ? .9F : .8F;
        float initialOwnership=standard ? .2F : .8F, overlayZ=standard ? .8F : .2F;
        float normalZ=name.equals("later_visible_normal")||lastOverlay ? .9F
                : name.equals("strict_hidden_equal") ? .8F : name.equals("different_source_attachment") ? .85F : .5F;
        CompareOp comparison=standard ? CompareOp.LESS_THAN_OR_EQUAL
                : name.equals("strict_hidden_equal") ? CompareOp.GREATER_THAN : CompareOp.GREATER_THAN_OR_EQUAL;
        var resources=BindGroupLayout.builder().withUniform("Projection",UniformType.UNIFORM_BUFFER)
                .withUniform("DynamicTransforms",UniformType.UNIFORM_BUFFER).withUniform("Fog",UniformType.UNIFORM_BUFFER)
                .withUniform("Sampler0",UniformType.COMBINED_IMAGE_SAMPLER).build();
        var builder=RenderPipeline.builder().withLocation("motion_contract/source_policy/"+name+"/overlay")
                .withVertexShader("core/entity").withFragmentShader("core/entity")
                .withVertexBinding(0,DefaultVertexFormat.ENTITY).withBindGroupLayout(resources)
                .withShaderDefine("EMISSIVE").withShaderDefine("NO_OVERLAY").withShaderDefine("NO_CARDINAL_LIGHTING")
                .withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withColorTargetState(0,new ColorTargetState(Optional.empty(),GpuFormat.RGBA32_FLOAT,ColorTargetState.WRITE_ALL));
        if(missing)builder.withDepthStencilState(new DepthStencilState(comparison,name.contains("with_write"),
                name.contains("and_bias") ? 2 : 0,name.contains("and_bias") ? 3 : 0));
        if(name.equals("always_pass"))builder.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS,false));
        var overlayPipeline=builder.build();
        var normalPipeline=builder.withLocation("motion_contract/source_policy/"+name+"/normal")
                .withDepthStencilState(new DepthStencilState(comparison,false)).build();
        ByteBuffer[] mesh=new ByteBuffer[2],old=new ByteBuffer[2],dynamic=new ByteBuffer[2];
        for(int layer=0;layer<2;layer++) {
            mesh[layer]=vertices(new Fixture(name,"core/entity",DefaultVertexFormat.ENTITY,false,false,false,false),false);
            float z=layer==0 ? overlayZ : normalZ;
            for(int i=0;i<6;i++)mesh[layer].putFloat(i*DefaultVertexFormat.ENTITY.getVertexSize()+8,z);
            float[] previous=MetalPreviousVertexHistory.extractPositions(DefaultVertexFormat.ENTITY,java.util.List.of(mesh[layer]),6);
            for(int i=0;i<6;i++)previous[i*3]+=layer==0 ? -.125F : .25F;
            old[layer]=ByteBuffer.allocateDirect(6*16).order(ByteOrder.nativeOrder());
            assertTrue(MetalPreviousVertexReplay.writePreviousPositions(previous,old[layer]));old[layer].flip();
            dynamic[layer]=ByteBuffer.allocateDirect(160).order(ByteOrder.nativeOrder());
            new Matrix4f().get(0,dynamic[layer]);new Matrix4f().get(64,dynamic[layer]);
            dynamic[layer].putFloat(128,layer==0 ? 1 : 0).putFloat(132,layer==1 ? 1 : 0).putFloat(140,1);
        }
        ByteBuffer projection=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());new Matrix4f().get(0,projection);
        ByteBuffer fog=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        fog.putFloat(16,1000).putFloat(20,2000).putFloat(24,1000).putFloat(28,2000);
        ByteBuffer motion=ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        new Matrix4f().scaling(1,-1,1).get(0,motion);new Matrix4f().scaling(1,-1,1).get(64,motion);
        int copy=GpuTexture.USAGE_COPY_SRC|GpuTexture.USAGE_COPY_DST;
        try(var v0=device.createBuffer(()->"overlay vertices",GpuBuffer.USAGE_VERTEX,mesh[0]);
            var v1=device.createBuffer(()->"normal vertices",GpuBuffer.USAGE_VERTEX,mesh[1]);
            var p0=device.createBuffer(()->"previous overlay",GpuBuffer.USAGE_VERTEX,old[0]);
            var p1=device.createBuffer(()->"previous normal",GpuBuffer.USAGE_VERTEX,old[1]);
            var d0=device.createBuffer(()->"red overlay",GpuBuffer.USAGE_UNIFORM,dynamic[0]);
            var d1=device.createBuffer(()->"green ordinary source",GpuBuffer.USAGE_UNIFORM,dynamic[1]);
            var pr=device.createBuffer(()->"source policy projection",GpuBuffer.USAGE_UNIFORM,projection);
            var fo=device.createBuffer(()->"source policy fog",GpuBuffer.USAGE_UNIFORM,fog);
            var mo=device.createBuffer(()->"source policy motion",GpuBuffer.USAGE_UNIFORM,motion);
            var sampled=texture("source policy material",GpuFormat.RGBA8_UNORM,GpuTexture.USAGE_TEXTURE_BINDING);
            var color=texture("actual original source ordering",GpuFormat.RGBA32_FLOAT,copy);
            var scene=texture("actual source depth attachment",GpuFormat.D32_FLOAT,copy|GpuTexture.USAGE_TEXTURE_BINDING);
            var referenceDepth=texture("immutable source depth reference",GpuFormat.D32_FLOAT,copy|GpuTexture.USAGE_TEXTURE_BINDING);
            var ownership=texture("private selected depth",GpuFormat.D32_FLOAT,copy);
            var flow=texture("source policy XY",GpuFormat.RG16_FLOAT,copy);var valid=texture("source policy tag",GpuFormat.R8_UNORM,copy);
            var pair=texture("source policy Z",GpuFormat.RG32_FLOAT,copy);
            var sv=device.createTextureView(sampled);var cv=device.createTextureView(color);var sd=device.createTextureView(scene);
            var rd=device.createTextureView(referenceDepth);var od=device.createTextureView(ownership);
            var fv=device.createTextureView(flow);var vv=device.createTextureView(valid);var zv=device.createTextureView(pair);
            var sampler=device.createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,FilterMode.NEAREST,FilterMode.NEAREST,1,java.util.OptionalDouble.empty())) {
            encoder.clearColorTexture(sampled,new Vector4f(1));encoder.clearDepthTexture(scene,sceneZ);
            encoder.clearDepthTexture(ownership,initialOwnership);
            for(int draw=0;draw<2;draw++) {
                int layer=lastOverlay ? 1-draw : draw;
                var descriptor=RenderPassDescriptor.builder(()->"Actual original source depth policy")
                        .withColorAttachment(cv,draw==0 ? Optional.of(new Vector4f(0,0,1,1)) : Optional.empty())
                        .withRenderArea(new RenderPass.RenderArea(0,0,W,H));
                if(layer==1 || !missing)descriptor.withDepthAttachment(sd);
                try(var pass=encoder.createRenderPass(descriptor.build())) {
                    pass.setPipeline(layer==0 ? overlayPipeline : normalPipeline);pass.setUniform("Projection",pr.slice());
                    pass.setUniform("DynamicTransforms",(layer==0 ? d0 : d1).slice());pass.setUniform("Fog",fo.slice());
                    pass.bindTexture("Sampler0",sv,sampler);pass.setVertexBuffer(0,(layer==0 ? v0 : v1).slice());pass.draw(6,1,0,0);
                }
            }
            encoder.copyTextureToTexture(scene,referenceDepth,0,0,0,0,0,W,H);
            try(var pass=encoder.createRenderPass(RenderPassDescriptor.builder(()->"Replay preserves original source semantics")
                    .withColorAttachment(fv,Optional.of(new Vector4f(0))).withColorAttachment(vv,Optional.of(new Vector4f(0)))
                    .withColorAttachment(zv,Optional.of(new Vector4f(-1))).withDepthAttachment(od)
                    .withRenderArea(new RenderPass.RenderArea(0,0,W,H)).build())) {
                for(int draw=0;draw<2;draw++) {
                    int layer=lastOverlay ? 1-draw : draw;
                    pass.setPipeline(MetalEntityMotionPipeline.forPreviousPositionsWithDepth(layer==0 ? overlayPipeline : normalPipeline,layer==1||!missing));
                    pass.setUniform("Projection",pr.slice());pass.setUniform("DynamicTransforms",(layer==0 ? d0 : d1).slice());
                    pass.setUniform("MetallumMotion",mo.slice());pass.bindTexture("Sampler0",sv,sampler);pass.bindTexture("MetallumSourceDepth",rd,sampler);
                    pass.setVertexBuffer(0,(layer==0 ? v0 : v1).slice());pass.setVertexBuffer(1,(layer==0 ? p0 : p1).slice());pass.draw(6,1,0,0);
                }
            }
            encoder.submit();device.waitForSubmittedGpuWork();
            byte[] rgba=readback(color),mask=readback(valid),xy=readback(flow),depth=readback(pair),sceneBytes=readback(scene);
            ByteBuffer colors=ByteBuffer.wrap(rgba).order(ByteOrder.nativeOrder()),vectors=ByteBuffer.wrap(xy).order(ByteOrder.nativeOrder());
            ByteBuffer pairs=ByteBuffer.wrap(depth).order(ByteOrder.nativeOrder()),sceneAfter=ByteBuffer.wrap(sceneBytes).order(ByteOrder.nativeOrder());
            int covered=0,wrongSurface=0,wrongMotion=0,wrongDepth=0;
            for(int p=0;p<W*H;p++) {
                boolean green=colors.getFloat(p*16+4)>.5F,red=colors.getFloat(p*16)>.5F;
                int expectedTag=green ? 255 : red ? 223 : 0;
                if(Byte.toUnsignedInt(mask[p])!=expectedTag)wrongSurface++;
                if(sceneAfter.getFloat(p*4)!=sceneZ)wrongDepth++;
                if(!red&&!green)continue;
                covered++;float x=Float.float16ToFloat(vectors.getShort(p*4)),y=Float.float16ToFloat(vectors.getShort(p*4+2));
                float z=green ? normalZ : overlayZ;
                double expectedX=green ? .25 : -.125;
                // The existing projective oracle independently calibrates this RG16F storage budget.
                if(!Float.isFinite(x)||!Float.isFinite(y)
                        || Math.abs(x-expectedX)*W/2>halfRoundingBudgetPixels(expectedX,W)
                        || Math.abs(y)>1e-5)wrongMotion++;
                if(Math.abs(pairs.getFloat(p*8)-z)>1e-6||Math.abs(pairs.getFloat(p*8+4)-z)>1e-6)wrongDepth++;
            }
            Path dir=Path.of("build/agent-evidence/motion-producers/source-depth-policy/"+name);Files.createDirectories(dir);
            Files.write(dir.resolve("original.rgba32f"),rgba);Files.write(dir.resolve("ownership.r8"),mask);Files.write(dir.resolve("motion.rg16f"),xy);
            Files.write(dir.resolve("correspondence.rg32f"),depth);Files.write(dir.resolve("source-depth.d32f"),sceneBytes);
            Files.writeString(dir.resolve("result.json"),"{\"coveredPixels\":"+covered+",\"wrongSurfacePixels\":"+wrongSurface
                    +",\"wrongMotionPixels\":"+wrongMotion+",\"wrongDepthPixels\":"+wrongDepth+"}");
            assertTrue(covered>100);assertEquals(0,wrongSurface,"Private depth changes must not admit a source-hidden later draw");
            assertEquals(0,wrongMotion);assertEquals(0,wrongDepth,"Overlay/normal replay must preserve the game's original depth");
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
