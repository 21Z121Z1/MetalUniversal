package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.joml.Vector4f;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the original shaderc -> SPIRV-Cross -> shipping MSL MRT through real RenderPearl validation. */
@EnabledOnOs(OS.MAC)
final class MetalFxCutoutReactiveIntegrationTest {
    private static final int WIDTH=31, HEIGHT=5;
    private static final FrameSynthesisContract.FrameStamp STAMP=new FrameSynthesisContract.FrameStamp(17,3);
    private static final String VERTEX="""
            #version 450
            void main(){
                vec2 p[3]=vec2[](vec2(-1,-1),vec2(3,-1),vec2(-1,3));
                gl_Position=vec4(p[gl_VertexIndex],0.5,1);
            }
            """;
    private static final String CUTOUT="""
            #version 450
            layout(location=0) out vec4 color;
            void main(){
                if(gl_FragCoord.x<15.0)discard;
                color=vec4(0.25,0.5,0.75,0.6);
                if(gl_FragCoord.x<23.0)return;
                color.r=1.0;
            }
            """;
    private final Map<String,String> shaders=new HashMap<>();
    private MetalDevice device;
    private MetalCommandEncoder encoder;

    @BeforeEach void createDevice(){
        var handle=MetalNativeBridge.metallum_create_system_default_device();
        assertFalse(MetalNativeBridge.isNullHandle(handle));
        device=new MetalDevice(MetalShaderSourceAdapters.from((id,type)->type==ShaderType.VERTEX
                ?VERTEX:shaders.get(id.getPath().substring(id.getPath().lastIndexOf('/')+1))),
                new GpuDebugOptions(2,true,true,true),handle,MemorySegment.NULL,"CUTOUT contract",MemorySegment.NULL);
        encoder=device.commandEncoder();
        if(Boolean.getBoolean("metallum.opt.metal4MainRenderer"))
            assertTrue(device.metal4MainRendererEnabled(),"Metal 4 cannot silently exercise Metal 3");
    }
    @AfterEach void closeDevice(){MetalFxManager.close();if(device!=null)device.close();}

    @Test void originalDiscardWritesSameFrameMrtAndOpaquePassPreservesCoverage(){
        var cutout=pipeline("cutout",CUTOUT);
        var opaque=pipeline("opaque","#version 450\nlayout(location=0) out vec4 color;void main(){color=vec4(0,1,0,1);}");
        try(var scene=texture(GpuFormat.RGBA8_UNORM,WIDTH,HEIGHT);
            var depth=texture(GpuFormat.D32_FLOAT,WIDTH,HEIGHT);
            var coverage=texture(GpuFormat.R8_UNORM,WIDTH,HEIGHT);
            var reactive=texture(GpuFormat.R8_UNORM,WIDTH,HEIGHT);
            var sceneView=new MetalGpuTextureView(scene,0,1);
            var depthView=new MetalGpuTextureView(depth,0,1);
            var coverageView=new MetalGpuTextureView(coverage,0,1)){
            var world=world(sceneView,depthView);
            // Baseline executes the unmodified shader with exactly one color attachment.
            var baselinePass=encoder.createRenderPass(world);
            baselinePass.setPipeline(cutout.backendRenderPipeline());
            baselinePass.draw(3,1,0,0);encoder.submitRenderPass();
            byte[] baseline=bytes(readback(scene));
            encoder.clearColorTexture(coverage,new Vector4f());
            var source=new MetalFxReactivePass.Source(STAMP,scene,depth,coverageView);
            assertFalse(source.hasReceipt(STAMP,coverage),"allocation is not a producer");
            var expanded=source.decorate(world);
            assertEquals(2,expanded.colorAttachments().size());
            assertSame(coverageView,expanded.colorAttachments().get(1).textureView());
            assertTrue(expanded.colorAttachments().get(1).clearValue().isEmpty(),"subsequent passes must LOAD");
            var backend=encoder.createRenderPass(expanded);
            backend.installReactivePass(source.claim(expanded));
            try(var frontend=frontend(backend,expanded)){
                assertThrows(IllegalStateException.class,()->frontend.setPipeline(cutout),"one-target pipeline must fail real RenderPearl validation");
                var adapted=MetalFxReactivePass.adaptPipeline(backend,cutout);
                assertSame(adapted,MetalFxReactivePass.adaptPipeline(backend,adapted));
                assertEquals(GpuFormat.R8_UNORM,((FrontendRenderPipeline)adapted).colorTargetStates().get(1).format());
                frontend.setPipeline(adapted);
                assertFalse(source.hasReceipt(STAMP,coverage),"pipeline binding is not a producer");
                frontend.draw(0,1,0,0);
                assertFalse(source.hasReceipt(STAMP,coverage),"zero geometry is not a producer");
                frontend.draw(3,1,0,0);
                assertTrue(source.hasReceipt(STAMP,coverage));
            }
            assertArrayEquals(baseline,bytes(readback(scene)),"the new MRT must not change sampling, discard, color or alpha");
            ByteBuffer mask=readback(coverage),worldDepth=readback(depth);
            for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++){
                assertEquals(x<15?0:255,Byte.toUnsignedInt(mask.get(y*WIDTH+x)),"coverage x="+x+" y="+y);
                assertEquals(x<15?0f:0.5f,worldDepth.getFloat((y*WIDTH+x)*4),0.000001f);
            }
            // Use the same production compute consumer on the same allocation.
            encoder.clearColorTexture(reactive,new Vector4f());
            MetalNativeBridge.metallum_metalfx_set_reactive_tuning(0.35f,0.05f,0.5f,0.9f,0f,0.85f,0f);
            assertTrue(encoder.encodeCutoutReactiveMask(coverage,reactive,WIDTH,HEIGHT,1));
            ByteBuffer consumed=readback(reactive);
            for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++){
                boolean any=x>=14;
                boolean edge=x<=15 || x==WIDTH-1 || y==0 || y==HEIGHT-1;
                int expected=any?Math.round(255*(edge?0.35f:0.05f)):0;
                assertEquals(expected,Byte.toUnsignedInt(consumed.get(y*WIDTH+x)),1,"same-source dilation x="+x+" y="+y);
            }
            var later=source.decorate(RenderPassDescriptor.builder(()->"opaque following CUTOUT")
                    .withColorAttachment(sceneView,Optional.empty()).withDepthAttachment(depthView,OptionalDouble.empty()).build());
            var opaqueBackend=encoder.createRenderPass(later);opaqueBackend.installReactivePass(source.claim(later));
            try(var frontend=frontend(opaqueBackend,later)){
                var adapted=(FrontendRenderPipeline)MetalFxReactivePass.adaptPipeline(opaqueBackend,opaque);
                assertEquals(0,adapted.colorTargetStates().get(1).writeMask());
                frontend.setPipeline(adapted);frontend.draw(3,1,0,0);
            }
            assertArrayEquals(bytes(mask),bytes(readback(coverage)),"opaque MRT write mask must preserve prior CUTOUT coverage");
            source.endWorld();
            assertSame(world,source.decorate(world));
            assertTrue(source.hasReceipt(STAMP,coverage),"ending world retains the same-source consumer receipt");
            assertFalse(source.hasReceipt(new FrameSynthesisContract.FrameStamp(18,3),coverage));
            assertFalse(source.hasReceipt(new FrameSynthesisContract.FrameStamp(17,4),coverage));
            assertFalse(source.hasReceipt(STAMP,reactive));
            source.invalidate();assertFalse(source.hasReceipt(STAMP,coverage));
        }
    }

    @Test void mismatchedHandDepthDimensionsFormatAndResetCannotMintSourceReceipt(){
        try(var scene=texture(GpuFormat.RGBA8_UNORM,WIDTH,HEIGHT);
            var depth=texture(GpuFormat.D32_FLOAT,WIDTH,HEIGHT);
            var hand=texture(GpuFormat.D32_FLOAT,WIDTH,HEIGHT);
            var coverage=texture(GpuFormat.R8_UNORM,WIDTH,HEIGHT);
            var wrongSize=texture(GpuFormat.R8_UNORM,WIDTH+1,HEIGHT);
            var wrongFormat=texture(GpuFormat.RGBA8_UNORM,WIDTH,HEIGHT);
            var sv=new MetalGpuTextureView(scene,0,1);var dv=new MetalGpuTextureView(depth,0,1);
            var hv=new MetalGpuTextureView(hand,0,1);var cv=new MetalGpuTextureView(coverage,0,1);
            var sizeView=new MetalGpuTextureView(wrongSize,0,1);var formatView=new MetalGpuTextureView(wrongFormat,0,1)){
            assertThrows(IllegalArgumentException.class,()->new MetalFxReactivePass.Source(STAMP,scene,depth,sizeView));
            assertThrows(IllegalArgumentException.class,()->new MetalFxReactivePass.Source(STAMP,scene,depth,formatView));
            var source=new MetalFxReactivePass.Source(STAMP,scene,depth,cv);
            var handPass=world(sv,hv);assertSame(handPass,source.decorate(handPass));assertNull(source.claim(handPass));
            var world=world(sv,dv);var valid=source.decorate(world);
            var badClear=RenderPassDescriptor.builder(()->"wrong clear").withColorAttachment(sv,Optional.empty())
                    .withColorAttachment(cv,Optional.of(new Vector4f())).withDepthAttachment(dv,OptionalDouble.empty()).build();
            assertNull(source.claim(badClear));
            var reversed=RenderPassDescriptor.builder(()->"wrong index").withColorAttachment(cv,Optional.empty())
                    .withColorAttachment(sv,Optional.empty()).withDepthAttachment(dv,OptionalDouble.empty()).build();
            assertNull(source.claim(reversed));
            var pending=source.claim(valid);assertNotNull(pending);
            var cutout=(MetalCompiledRenderPipeline)pipeline("reset-cutout",CUTOUT).backendRenderPipeline();
            pending.bind(cutout.withCutoutReactiveTarget());source.invalidate();pending.didEncode(1);
            assertFalse(source.hasReceipt(STAMP,coverage));
            var recreated=new MetalFxReactivePass.Source(new FrameSynthesisContract.FrameStamp(18,4),scene,depth,cv);
            pending.didEncode(1);assertEquals(0,recreated.encodedDrawBatches(),"A-B-A view reuse must not resurrect an old source");
            var closed=recreated.claim(recreated.decorate(world));assertNotNull(closed);
            closed.bind(cutout.withCutoutReactiveTarget());closed.close();closed.close();closed.didEncode(1);
            assertEquals(0,recreated.encodedDrawBatches());
        }
    }

    @Test void unsupportedFragmentKeepsOriginalProgramAndActualDrawRequiresFullReactiveFallback(){
        var never=pipeline("all-discard","#version 450\nlayout(location=0) out vec4 color;void main(){discard;}");
        try(var scene=texture(GpuFormat.RGBA8_UNORM,WIDTH,HEIGHT);
            var depth=texture(GpuFormat.D32_FLOAT,WIDTH,HEIGHT);
            var coverage=texture(GpuFormat.R8_UNORM,WIDTH,HEIGHT);
            var sv=new MetalGpuTextureView(scene,0,1);var dv=new MetalGpuTextureView(depth,0,1);var cv=new MetalGpuTextureView(coverage,0,1)){
            var source=new MetalFxReactivePass.Source(STAMP,scene,depth,cv);
            var descriptor=source.decorate(world(sv,dv));
            var backend=encoder.createRenderPass(descriptor);backend.installReactivePass(source.claim(descriptor));
            try(var frontend=frontend(backend,descriptor)){
                var adapted=(FrontendRenderPipeline)MetalFxReactivePass.adaptPipeline(backend,never);
                assertTrue(((MetalCompiledRenderPipeline)adapted.backendRenderPipeline()).unsupportedCutoutCoverage());
                frontend.setPipeline(adapted);
                assertFalse(source.requiresConservativeFallback(STAMP),"compilation is not visibility");
                frontend.draw(3,1,0,0);
            }
            assertTrue(source.requiresConservativeFallback(STAMP));assertFalse(source.hasReceipt(STAMP,coverage));
            for(byte value:bytes(readback(scene)))assertEquals(0,value,"the original all-discard shader must still discard");
        }
    }

    private FrontendRenderPass frontend(MetalRenderPass backend,RenderPassDescriptor descriptor){
        return new FrontendRenderPass(backend,device,descriptor.colorAttachments(),true,encoder::submitRenderPass,descriptor.renderArea());
    }
    private RenderPassDescriptor world(MetalGpuTextureView color,MetalGpuTextureView depth){
        return RenderPassDescriptor.builder(()->"source world").withColorAttachment(color,Optional.of(new Vector4f()))
                .withDepthAttachment(depth,OptionalDouble.of(0)).build();
    }
    private FrontendRenderPipeline pipeline(String name,String fragment){
        shaders.put(name,fragment);
        var target=new ColorTargetState(Optional.empty(),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_ALL);
        var info=RenderPipeline.builder().withLocation("metallum_test/"+name).withVertexShader("metallum_test/"+name)
                .withFragmentShader("metallum_test/"+name).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,true)).withColorTargetState(0,target).build();
        return new FrontendRenderPipeline(name,device.getOrCompilePipeline(info),info.getVertexFormatBindings(),
                new Object2IntOpenHashMap<>(),List.of(),List.of(target),true,0);
    }
    private MetalGpuTexture texture(GpuFormat format,int width,int height){
        int usage=GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_SRC;
        if(format==GpuFormat.R8_UNORM)usage|=MetalGpuTexture.USAGE_SHADER_WRITE;
        return (MetalGpuTexture)device.createTexture(()->"reactive fixture",usage,format,width,height,1,1);
    }
    private ByteBuffer readback(MetalGpuTexture texture){
        int size=texture.getWidth(0)*texture.getHeight(0)*texture.pixelSize();
        try(var buffer=(MetalGpuBuffer)device.createBuffer(()->"coverage readback",GpuBuffer.USAGE_MAP_READ|GpuBuffer.USAGE_COPY_DST,size)){
            encoder.copyTextureToBuffer(texture,buffer,0,()->{},0);encoder.submit();device.waitForSubmittedGpuWork();
            return ByteBuffer.allocate(size).order(ByteOrder.nativeOrder()).put(buffer.currentStorage().limit(size).slice()).flip();
        }
    }
    private static byte[] bytes(ByteBuffer buffer){byte[] data=new byte[buffer.remaining()];buffer.duplicate().get(data);return data;}
}
