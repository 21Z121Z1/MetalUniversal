package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.textures.*;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;

/** Unmodified original color draw, deliberately absent staged capture, shipping hook and native V4. */
@EnabledOnOs(OS.MAC)
final class MetalUnownedOpaqueTemporalGpuTest {
    private static final int W=64,H=48,N=W*H;

    @Test
    void visibleUnownedSameDepthCannotBeProvedByCameraReprojection() throws Exception {
        ShaderSource library=MetalShaderSourceLibraryTest.rawLibrary(false,false);
        var device=new MetalDevice(library,new GpuDebugOptions(2,true,true,true),
                MetalNativeBridge.metallum_create_system_default_device(),MemorySegment.NULL,
                "Unowned ordinary opaque source",MemorySegment.NULL);
        var encoder=device.commandEncoder();
        var textures=new ArrayList<MetalGpuTexture>();
        var buffers=new ArrayList<GpuBuffer>();
        var views=new ArrayList<GpuTextureView>();
        Path dir=Path.of("build/agent-evidence/motion-producers/unowned-opaque-temporal/same_z_missing_capture");
        Files.createDirectories(dir);
        MetalEntityMotionCapture.setEnabled(true);
        MetalEntityMotionCapture.beginFrame();
        MetalMotionDrawCoverage.beginFrame();
        var stamp=new FrameSynthesisContract.FrameStamp(73,9);
        MetalMotionDrawCoverage.bindSourceFrame(stamp);
        try {
            var source=MetalParticlePartialAlphaCases.pipeline(true);
            var format=DefaultVertexFormat.PARTICLE;
            ByteBuffer blue=mesh(false),red=mesh(true),idx=bytes(12);
            for(int i:new int[]{0,1,2,2,3,0})idx.putShort((short)i);idx.flip();
            ByteBuffer projection=bytes(64);new Matrix4f().get(0,projection);
            ByteBuffer fog=bytes(40);fog.putFloat(16,1000).putFloat(20,2000).putFloat(24,1000).putFloat(28,2000);
            var bg=buffer(device,buffers,"original blue background",GpuBuffer.USAGE_VERTEX,blue);
            var fg=buffer(device,buffers,"visible unowned red particle",GpuBuffer.USAGE_VERTEX,red);
            var ib=buffer(device,buffers,"real QUADS indices",GpuBuffer.USAGE_INDEX,idx);
            var pr=buffer(device,buffers,"Projection",GpuBuffer.USAGE_UNIFORM,projection);
            var fo=buffer(device,buffers,"Fog",GpuBuffer.USAGE_UNIFORM,fog);
            ByteBuffer dynamic=bytes(160);dynamic.put(MetalPreviousVisibilityHistoryTest.dynamic(1)).flip();
            var du=buffer(device,buffers,"DynamicTransforms",GpuBuffer.USAGE_UNIFORM,dynamic);
            var white=texture(device,textures,"fixed atlas and lightmap",GpuFormat.RGBA8_UNORM);
            var color=texture(device,textures,"original source RGBA",GpuFormat.RGBA8_UNORM);
            var depth=texture(device,textures,"original source D32",GpuFormat.D32_FLOAT);
            var privateDepth=texture(device,textures,"object depth",GpuFormat.D32_FLOAT);
            var flow=texture(device,textures,"object XY",GpuFormat.RG16_FLOAT);
            var valid=texture(device,textures,"object tags",GpuFormat.R8_UNORM);
            var pair=texture(device,textures,"object Z pair",GpuFormat.RG32_FLOAT);
            var oldWorld=texture(device,textures,"accepted blue frame depth",GpuFormat.R32_FLOAT);
            var oldHud=texture(device,textures,"no previous HUD",GpuFormat.R32_FLOAT);
            var noHand=texture(device,textures,"no current HUD",GpuFormat.R8_UNORM);
            var merged=texture(device,textures,"V4 XY",GpuFormat.RG16_FLOAT);
            var reactive=texture(device,textures,"V4 reactive",GpuFormat.R8_UNORM);
            var camera=texture(device,textures,"V4 camera",GpuFormat.RG16_FLOAT);
            var reject=texture(device,textures,"V4 disocclusion",GpuFormat.R8_UNORM);
            var next=texture(device,textures,"next world",GpuFormat.R32_FLOAT);
            var presented=texture(device,textures,"presentation depth",GpuFormat.R32_FLOAT);
            var nextHud=texture(device,textures,"next HUD",GpuFormat.R32_FLOAT);
            var cv=view(device,views,color);var dv=view(device,views,depth);var wv=view(device,views,white);
            try(var sampler=device.createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST,FilterMode.NEAREST,1,OptionalDouble.empty())) {
                encoder.clearColorTexture(white,new Vector4f(1));
                try(var pass=encoder.createRenderPass(RenderPassDescriptor.builder(()->"Uninstrumented accepted blue source")
                        .withColorAttachment(cv,Optional.of(new Vector4f(0))).withDepthAttachment(dv,OptionalDouble.of(0))
                        .withRenderArea(new RenderPass.RenderArea(0,0,W,H)).build())) {
                    pass.setPipeline(source);pass.setUniform("Projection",pr.slice());pass.setUniform("Fog",fo.slice());
                    pass.setUniform("DynamicTransforms",du.slice());pass.bindTexture("Sampler0",wv,sampler);pass.bindTexture("Sampler2",wv,sampler);
                    pass.setVertexBuffer(0,bg.slice());pass.setIndexBuffer(ib,IndexType.SHORT);pass.drawIndexed(6,1,0,0,0);
                }
                byte[] oldRgba=read(device,encoder,color),oldZ=read(device,encoder,depth);
                Files.write(dir.resolve("accepted-blue.rgba8"),oldRgba);Files.write(dir.resolve("accepted-blue.d32f"),oldZ);
                ByteBuffer acceptedDepth=bytes(oldZ.length);acceptedDepth.put(oldZ).flip();
                encoder.writeToTexture(oldWorld,acceptedDepth,0,0,0,0,W,H);
                encoder.copyTextureToTexture(depth,privateDepth,0,0,0,0,0,W,H);
                encoder.clearColorTexture(flow,new Vector4f(0));encoder.clearColorTexture(valid,new Vector4f(0));
                encoder.clearColorTexture(pair,new Vector4f(-1));encoder.clearColorTexture(reactive,new Vector4f(0));
                encoder.clearColorTexture(oldHud,new Vector4f(0));encoder.clearColorTexture(noHand,new Vector4f(0));
                MetalMotionDrawCoverage.beginScene();
                try(var pass=encoder.createRenderPass(RenderPassDescriptor.builder(()->"Actual visible unowned ordinary opaque draw")
                        .withColorAttachment(cv,Optional.empty()).withDepthAttachment(dv)
                        .withRenderArea(new RenderPass.RenderArea(0,0,W,H)).build())) {
                    pass.setPipeline(source);pass.setUniform("Projection",pr.slice());pass.setUniform("Fog",fo.slice());
                    pass.setUniform("DynamicTransforms",du.slice());pass.bindTexture("Sampler0",wv,sampler);pass.bindTexture("Sampler2",wv,sampler);
                    pass.setVertexBuffer(0,fg.slice());pass.setIndexBuffer(ib,IndexType.SHORT);pass.drawIndexed(6,1,0,0,0);
                    var info=new StagedVertexBuffer.ExecuteInfo(fg,ib,IndexType.SHORT,0,0,6,PrimitiveTopology.QUADS);
                    // No attach/transfer: this is a genuine missing owner at the production hook.
                    MetalFxManager.drawStagedMotion(info,null,pass);
                }
                MetalMotionDrawCoverage.endScene();
                byte[] currentRgba=read(device,encoder,color),currentZ=read(device,encoder,depth);
                Files.write(dir.resolve("current-red.rgba8"),currentRgba);Files.write(dir.resolve("current-red.d32f"),currentZ);
                assertTrue(encoder.resolveSurfaceMotionV4(depth,privateDepth,null,flow,valid,noHand,pair,oldWorld,oldHud,
                        merged,reactive,camera,reject,next,presented,nextHud,new Matrix4f(),new Matrix4f(),new Matrix4f(),
                        new Vector2f(),new Vector2f(),.8F,W,H,true,true,false,true));
                byte[] tags=read(device,encoder,valid),rejection=read(device,encoder,reject),reactivity=read(device,encoder,reactive);
                byte[] resolved=read(device,encoder,merged);
                Files.write(dir.resolve("object-tags.r8"),tags);Files.write(dir.resolve("v4-rejection.r8"),rejection);
                Files.write(dir.resolve("v4-reactive.r8"),reactivity);Files.write(dir.resolve("v4-motion.rg16f"),resolved);
                assertArrayEquals(currentRgba,read(device,encoder,color));assertArrayEquals(currentZ,read(device,encoder,depth));
                int visible=0,falseCameraAccepted=0;
                for(int p=0;p<N;p++) {
                    assertEquals(.5F,ByteBuffer.wrap(oldZ).order(ByteOrder.nativeOrder()).getFloat(p*4));
                    assertEquals(.5F,ByteBuffer.wrap(currentZ).order(ByteOrder.nativeOrder()).getFloat(p*4));
                    assertEquals(0,tags[p]);
                    boolean inside=p%W>=W/4&&p%W<3*W/4&&p/W>=H/4&&p/W<3*H/4;
                    assertEquals(inside?255:0,Byte.toUnsignedInt(currentRgba[p*4]));
                    assertEquals(255,Byte.toUnsignedInt(oldRgba[p*4+2]));
                    if(inside) {visible++;if(rejection[p]==0&&reactivity[p]==0)falseCameraAccepted++;}
                }
                boolean admitted=MetalFxManager.temporalSourceFrameAdmitted(stamp,false);
                var result=Map.ofEntries(Map.entry("sourceCommit",System.getenv().getOrDefault("METALLUM_SOURCE_SHA","local")),
                        Map.entry("sourceFrame",stamp),Map.entry("pipeline",source.getLocation().toString()),
                        Map.entry("depthState",source.getDepthStencilState().toString()),Map.entry("visibleUnownedPixels",visible),
                        Map.entry("v4CameraAcceptedWrongHistoryPixels",falseCameraAccepted),Map.entry("productionSourceAdmitted",admitted),
                        Map.entry("shaderGateRejected",!MetalFxManager.temporalSourceFrameAdmitted(stamp,true)),
                        Map.entry("coverage",MetalMotionDrawCoverage.census()),Map.entry("rgbaAndD32UnchangedByMotion",true),
                        Map.entry("physicalTemporalEncodeAttempted",false),Map.entry("admissionWithSupportedCapabilities",MetalFxManager.selectMode(
                                MetalFxConfig.Mode.TEMPORAL,true,true,true).toString()));
                String json=new com.google.gson.Gson().toJson(result);Files.writeString(dir.resolve("result.json"),json);
                System.out.println("UNOWNED_OPAQUE_TEMPORAL "+json);
                assertEquals(768,visible);assertEquals(768,falseCameraAccepted);
                assertTrue(admitted,"Frozen baseline: source dispatch currently accepts the unowned frame");
            }
        } finally {
            MetalMotionDrawCoverage.endScene();MetalEntityMotionCapture.beginFrame();
            for(var v:views)v.close();for(var t:textures)t.close();for(var b:buffers)b.close();
            device.close();library.close();
        }
    }
    private static ByteBuffer mesh(boolean red) {
        var f=DefaultVertexFormat.PARTICLE;var b=bytes(4*f.getVertexSize());
        float e=red?.5F:1;float[][] xy={{-e,-e},{e,-e},{e,e},{-e,e}};
        for(int v=0;v<4;v++) {int k=v*f.getVertexSize();b.putFloat(k,xy[v][0]).putFloat(k+4,xy[v][1]).putFloat(k+8,.5F);
            int c=k+f.getElement("Color").offset();b.put(c,(byte)(red?255:0)).put(c+1,(byte)0).put(c+2,(byte)(red?0:255)).put(c+3,(byte)255);
            int uv=k+f.getElement("UV0").offset();b.putFloat(uv,.25F).putFloat(uv+4,.5F);}
        return b;
    }
    private static ByteBuffer bytes(int n) {return ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder());}
    private static GpuBuffer buffer(MetalDevice d,ArrayList<GpuBuffer> all,String name,int usage,ByteBuffer bytes) {
        var b=d.createBuffer(()->name,usage,bytes);all.add(b);return b;
    }
    private static GpuTextureView view(MetalDevice d,ArrayList<GpuTextureView> all,MetalGpuTexture t) {var v=d.createTextureView(t);all.add(v);return v;}
    private static MetalGpuTexture texture(MetalDevice d,ArrayList<MetalGpuTexture> all,String name,GpuFormat f) {
        int usage=GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_SRC|GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_RENDER_ATTACHMENT;
        if(f==GpuFormat.RG16_FLOAT||f==GpuFormat.R32_FLOAT||f==GpuFormat.R8_UNORM)usage|=MetalGpuTexture.USAGE_SHADER_WRITE;
        var t=(MetalGpuTexture)d.createTexture(name,usage,f,W,H,1,1);all.add(t);return t;
    }
    private static byte[] read(MetalDevice d,MetalCommandEncoder e,MetalGpuTexture t) {
        int n=N*t.pixelSize();try(var b=(MetalGpuBuffer)d.createBuffer(()->"unowned source readback",GpuBuffer.USAGE_MAP_READ|GpuBuffer.USAGE_COPY_DST,n)) {
            e.copyTextureToBuffer(t,b,0,()->{},0);e.submit();d.waitForSubmittedGpuWork();byte[] out=new byte[n];b.currentStorage().get(0,out);return out;}
    }
}
