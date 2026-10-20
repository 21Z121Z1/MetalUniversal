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
        ByteBuffer old = ByteBuffer.allocateDirect(36).order(ByteOrder.nativeOrder());
        for (int i=0; i<3; i++) {
            int offset = i*DefaultVertexFormat.ENTITY.getVertexSize();
            for (int c=0; c<3; c++) { mesh.putFloat(offset+4*c,current[i][c]); old.putFloat(previous[i][c]); }
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
        ByteBuffer motion = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        new Matrix4f(flip).mul(baseP).mul(new Matrix4f(rasterP).invert()).get(0,motion); previousMvp.get(64,motion);
        try (var vb=device.createBuffer(() -> "projective source",GpuBuffer.USAGE_VERTEX,mesh);
             var pb=device.createBuffer(() -> "projective previous",GpuBuffer.USAGE_VERTEX,old);
             var du=device.createBuffer(() -> "projective dynamic",GpuBuffer.USAGE_UNIFORM,dynamic);
             var pr=device.createBuffer(() -> "projective projection",GpuBuffer.USAGE_UNIFORM,projection);
             var fo=device.createBuffer(() -> "projective fog",GpuBuffer.USAGE_UNIFORM,fog);
             var mo=device.createBuffer(() -> "projective motion",GpuBuffer.USAGE_UNIFORM,motion);
             var color=texture("actual projective source",GpuFormat.RGBA8_UNORM,GpuTexture.USAGE_COPY_SRC);
             var flow=texture("projective motion",GpuFormat.RG16_FLOAT,GpuTexture.USAGE_COPY_SRC);
             var valid=texture("projective validity",GpuFormat.R8_UNORM,GpuTexture.USAGE_COPY_SRC);
             var sampled=texture("projective texture",GpuFormat.RGBA8_UNORM,GpuTexture.USAGE_TEXTURE_BINDING);
             var cv=device.createTextureView(color);var fv=device.createTextureView(flow);var vv=device.createTextureView(valid);var sv=device.createTextureView(sampled);
             var sampler=device.createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,FilterMode.NEAREST,FilterMode.NEAREST,1,java.util.OptionalDouble.empty())) {
            encoder.clearColorTexture(sampled,new Vector4f(1));
            try (var pass=encoder.createRenderPass(RenderPassDescriptor.builder(() -> "Actual projective Minecraft color")
                    .withColorAttachment(cv,Optional.of(new Vector4f(0))).build())) {
                pass.setPipeline(source);pass.setUniform("Projection",pr.slice());pass.setUniform("DynamicTransforms",du.slice());
                pass.setUniform("Fog",fo.slice());pass.bindTexture("Sampler0",sv,sampler);pass.setVertexBuffer(0,vb.slice());pass.draw(3,1,0,0);
            }
            try (var pass=encoder.createRenderPass(RenderPassDescriptor.builder(() -> "Projective motion contract")
                    .withColorAttachment(fv,Optional.of(new Vector4f(0))).withColorAttachment(vv,Optional.of(new Vector4f(0))).build())) {
                pass.setPipeline(MetalEntityMotionPipeline.forPreviousPositions(source));pass.setUniform("Projection",pr.slice());
                pass.setUniform("DynamicTransforms",du.slice());pass.setUniform("MetallumMotion",mo.slice());pass.bindTexture("Sampler0",sv,sampler);
                pass.setVertexBuffer(0,vb.slice());pass.setVertexBuffer(1,pb.slice());pass.draw(3,1,0,0);
            }
            encoder.submit();device.waitForSubmittedGpuWork();
            byte[] pixels=readback(color),mask=readback(valid),vectors=readback(flow);
            ByteBuffer values=ByteBuffer.wrap(vectors).order(ByteOrder.nativeOrder());
            Vector4f[] rasterClip=project(rasterMvp,current),currentClip=project(currentMvp,current),previousClip=project(previousMvp,previous);
            int covered=0,expectedValid=0,invalid=0,wrongValidity=0;
            double maxError=0,maxLinearError=0,maxPrecisionExcess=0;
            for (int p=0;p<W*H;p++) {
                if (Byte.toUnsignedInt(pixels[p*4+3])<128) continue;
                covered++;
                double[] screen=barycentric(2*(p%W+.5)/W-1,1-2*(p/W+.5)/H,rasterClip);
                double[] weights=new double[3];double sum=0;
                for(int i=0;i<3;i++){weights[i]=screen[i]/rasterClip[i].w;sum+=weights[i];}
                for(int i=0;i<3;i++)weights[i]/=sum;
                double[] c=interpolate(currentClip,weights),b=interpolate(previousClip,weights);
                double x=b[0]/b[3]-c[0]/c[3],y=c[1]/c[3]-b[1]/b[3];
                boolean expected=c[3]>1E-6&&b[3]>1E-6&&Double.isFinite(x)&&Double.isFinite(y)&&Math.abs(x)<=32&&Math.abs(y)<=32;
                boolean actual=Byte.toUnsignedInt(mask[p])>127;
                if(expected!=actual)wrongValidity++;
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
            Files.writeString(dir.resolve("result.json"),"{\"fixture\":\""+fixture.name()+"\",\"width\":"+W+",\"height\":"+H
                    +",\"sourcePixels\":"+covered+",\"expectedValidPixels\":"+expectedValid+",\"expectedInvalidPixels\":"+invalid
                    +",\"wrongValidityPixels\":"+wrongValidity+",\"maxErrorPixels\":"+maxError
                    +",\"maxErrorBeyondHalfPrecisionPixels\":"+maxPrecisionExcess+",\"oldLinearErrorPixels\":"+maxLinearError+"}");
            assertTrue(covered>100&&expectedValid>50);
            if(fixture.previousBehind())assertTrue(invalid>10,"the fixture must cross previous camera w=0");
            else assertTrue(maxLinearError>.2,"the independent oracle must detect old per-vertex NDC interpolation");
            assertEquals(0,wrongValidity,"validity is evaluated per fragment, not from a provoking vertex");
            assertTrue(maxPrecisionExcess<.02,
                    "each pixel must match the independent surface point within RG16F rounding + .02 pixel: "+maxPrecisionExcess);
        }
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
