package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.lang.foreign.MemorySegment;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated shadow MRT experiment: the real original shader body decides both color and pixel owner. */
@EnabledOnOs(OS.MAC)
final class MetalParticlePixelOwnershipRasterTest {
    private ShaderSource library;private MetalDevice device;private MetalCommandEncoder encoder;
    private RenderPipeline original,instrumented;private final Map<String,RenderPipeline> resolvers=new HashMap<>();
    private final List<AutoCloseable> resources=new ArrayList<>();
    private final List<MetalGpuTexture> textures=new ArrayList<>();private final Map<MetalGpuTexture,Long> logicalBytes=new IdentityHashMap<>();
    private long peakLogicalBytes;private final List<Map<String,Object>> timings=new ArrayList<>();
    private final MetalParticlePixelOwnershipPrototype.Registry registry=new MetalParticlePixelOwnershipPrototype.Registry();
    private final MetalEntityMotionCapture.Sample sample=new MetalEntityMotionCapture.Sample((1L<<48)+71,33,new Matrix4f(),new Matrix4f(),FrameSynthesisContract.ProducerDomain.PARTICLES_WEATHER);
    private GpuBuffer projection,dynamic,fog,indices;private MetalGpuTexture white;private GpuTextureView whiteView;private GpuSampler sampler;
    private Path directory;private String stamp;private int w=64,h=48;
    private static final long GENERATION=(1L<<35)+17;
    private static final class Element {
        final Object emitter=new Object();final long id;final boolean red;
        Element(long id,boolean red){this.id=id;this.red=red;}
    }
    private record Draw(List<Element> elements,GpuBuffer vertices,GpuBuffer ids,MetalPreviousVertexHistory.DrawToken token) {}
    private final class Slot implements AutoCloseable {
        final MetalGpuTexture owner=texture("raster owner+generation",GpuFormat.RGBA32_UINT,w,h);
        final MetalGpuTexture depth;final GpuTextureView ov,dv;
        Slot(){
            MetalGpuTexture d=null;GpuTextureView o=null,z=null;
            try {d=texture("source raster D32",GpuFormat.D32_FLOAT,w,h);o=track(device.createTextureView(owner));z=track(device.createTextureView(d));}
            catch(RuntimeException|Error failure){if(o!=null)o.close();if(d!=null)d.close();owner.close();throw failure;}
            depth=d;ov=o;dv=z;
        }
        @Override public void close(){ov.close();dv.close();owner.close();depth.close();}
    }
    private final class Surface implements AutoCloseable {
        final MetalGpuTexture color=texture("unmodified original color",GpuFormat.RGBA8_UNORM,w,h);
        final MetalGpuTexture depth=texture("unmodified original D32",GpuFormat.D32_FLOAT,w,h);
        final MetalGpuTexture shadow=texture("instrumented original color",GpuFormat.RGBA8_UNORM,w,h);
        final MetalGpuTexture privateDepth=texture("motion source visibility",GpuFormat.D32_FLOAT,w,h);
        final MetalGpuTexture xy=texture("shipping source XY",GpuFormat.RG16_FLOAT,w,h);
        final MetalGpuTexture valid=texture("shipping source validity",GpuFormat.R8_UNORM,w,h);
        final MetalGpuTexture pair=texture("shipping source Z pair",GpuFormat.RG32_FLOAT,w,h);
        final MetalGpuTexture accepted=texture("prototype valid history",GpuFormat.R8_UNORM,w,h);
        final MetalGpuTexture foreignColor=texture("independent negative color",GpuFormat.RGBA8_UNORM,w,h);
        final MetalGpuTexture foreignDepth=texture("independent negative D32",GpuFormat.D32_FLOAT,w,h);
        final Map<MetalGpuTexture,GpuTextureView> views=new IdentityHashMap<>();
        GpuTextureView view(MetalGpuTexture t){return views.computeIfAbsent(t,k->track(device.createTextureView(k)));}
        @Override public void close(){views.values().forEach(GpuTextureView::close);for(var t:List.of(color,depth,shadow,privateDepth,xy,valid,pair,accepted,foreignColor,foreignDepth))t.close();}
    }

    @BeforeEach void setup() throws Exception {
        library=MetalShaderSourceLibraryTest.rawLibrary(false,false);
        device=new MetalDevice(library,new GpuDebugOptions(2,true,true,true),MetalNativeBridge.metallum_create_system_default_device(),MemorySegment.NULL,"Pixel ownership diagnostic only",MemorySegment.NULL);
        encoder=device.commandEncoder();MetalPreviousVertexHistory.reset();
        original=MetalParticlePartialAlphaCases.pipeline(true);
        var id=Identifier.withDefaultNamespace("core/particle");String vs=library.getShader(id,ShaderType.VERTEX),fs=library.getShader(id,ShaderType.FRAGMENT);
        var proof=MetalShaderSourceAdapters.libraryProof(library);assertNotNull(proof);assertTrue(proof.canonicalOpen());
        stamp=proof.generation()+":"+MetalShaderInputCapture.fingerprint(ByteBuffer.wrap(vs.getBytes(java.nio.charset.StandardCharsets.UTF_8)))+":"+MetalShaderInputCapture.fingerprint(ByteBuffer.wrap(fs.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var pv=id("instrumented_vertex");var pf=id("instrumented_fragment");
        var builder=RenderPipeline.builder().withLocation(id("canonical_opaque_shadow")).withVertexShader(pv).withFragmentShader(pf)
                .withPrimitiveTopology(original.getPrimitiveTopology()).withCull(original.isCull()).withPolygonMode(original.getPolygonMode())
                .withDepthStencilState(original.getDepthStencilState()).withVertexBinding(0,DefaultVertexFormat.PARTICLE)
                .withColorTargetState(0,original.getColorTargetStates().getFirst())
                .withColorTargetState(1,new ColorTargetState(Optional.empty(),GpuFormat.RGBA32_UINT,ColorTargetState.WRITE_ALL));
        original.getBindGroupLayouts().forEach(builder::withBindGroupLayout);
        original.getShaderDefines().flags().forEach(builder::withShaderDefine);
        original.getShaderDefines().values().forEach((key,value)->{try{builder.withShaderDefine(key,Integer.parseInt(value));}catch(NumberFormatException e){builder.withShaderDefine(key,Float.parseFloat(value));}});
        builder.withBindGroupLayout(BindGroupLayout.builder().withUniform("ProofIdentity",UniformType.UNIFORM_BUFFER).build());
        instrumented=builder.build();
        device.precompilePipeline(instrumented,MetalShaderSourceAdapters.overlay(Map.of(pv,MetalParticlePixelOwnershipPrototype.instrument(vs,ShaderType.VERTEX),pf,MetalParticlePixelOwnershipPrototype.instrument(fs,ShaderType.FRAGMENT)),library));
        projection=buffer("projection",GpuBuffer.USAGE_UNIFORM,matrix(new Matrix4f()));
        ByteBuffer dd=bytes(160);dd.put(MetalPreviousVisibilityHistoryTest.dynamic(1)).flip();
        dynamic=buffer("identical source dynamic",GpuBuffer.USAGE_UNIFORM,dd);
        ByteBuffer f=bytes(40);f.putFloat(16,1000).putFloat(20,2000).putFloat(24,1000).putFloat(28,2000);fog=buffer("identical source fog",GpuBuffer.USAGE_UNIFORM,f);
        ByteBuffer ix=bytes(24);for(int q=0;q<2;q++)for(int n:new int[]{0,1,2,2,3,0})ix.putShort((short)(q*4+n));ix.flip();indices=buffer("real QUADS indices",GpuBuffer.USAGE_INDEX,ix);
        white=texture("fixed white atlas/lightmap",GpuFormat.RGBA8_UNORM,64,48);encoder.clearColorTexture(white,new Vector4f(1));whiteView=track(device.createTextureView(white));
        sampler=track(device.createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,FilterMode.NEAREST,FilterMode.NEAREST,1,OptionalDouble.empty()));
    }
    @AfterEach void cleanup(){
        MetalPreviousVertexHistory.discardFrame();MetalPreviousVertexHistory.reset();MetalEntityMotionPipeline.clear();
        for(int i=resources.size()-1;i>=0;i--)try{resources.get(i).close();}catch(Exception failure){throw new IllegalStateException(failure);}
        if(device!=null){device.close();device=null;}if(library!=null){library.close();library=null;}
    }
    static List<String> scenes(){return List.of("occluded_same_z","continuous","A_before_B","generation","moving_x","moving_y",
            "split_merge_ab","split_merge_ba","merge_split_ab","merge_split_ba","discard_recover","discard_move_recover","failed_recover","resize","reload_epoch","unknown_identity","alias_identity","source_lease_unknown","foreign_depth","cost_854","cost_1080");}

    @ParameterizedTest(name="{0}") @MethodSource("scenes")
    void originalPixelOwnerControlsHistory(String scene) throws Exception {
        if(scene.equals("cost_854")){w=854;h=480;}if(scene.equals("cost_1080")){w=1920;h=1080;}
        directory=Path.of("build/agent-evidence/motion-producers/particle-pixel-ownership-prototype/"+scene+"_"+w+"x"+h+"_u64_owner_u64_generation");Files.createDirectories(directory);
        var results=new ArrayList<Map<String,Object>>();var evidence=new LinkedHashMap<String,Object>();
        evidence.put("sourceCommit",System.getenv().getOrDefault("METALLUM_SOURCE_SHA","local"));evidence.put("scene",scene);evidence.put("prototypeOnly",true);evidence.put("sourceStamp",stamp);evidence.put("results",results);evidence.put("passed",false);
        var a=new Element((1L<<40)+11,false);var b=new Element((1L<<40)+22,true);
        evidence.put("elementIds",List.of(a.id,b.id));evidence.put("initialGeneration",GENERATION);evidence.put("pipeline",original.getLocation().toString());evidence.put("depthState",original.getDepthStencilState().toString());
        evidence.put("identityProvenance","Controlled emitter ledger -> actual freed-slice geometry/index order -> flat integer vertex output -> original fragment main/discard -> winner owner MRT. No CPU-written owner image.");
        evidence.put("failureAndResetBoundary","failed_recover injects source acceptance failure after successful GPU completion. reload_epoch is the diagnostic invalidation entry, not a live Minecraft Configs reload. Native GPU faults are not forced.");
        boolean recover=scene.endsWith("recover"),reset=scene.equals("resize")||scene.equals("reload_epoch");
        int frames=recover?4:reset?3:2;Surface surface=new Surface();
        var history=new MetalParticlePixelOwnershipPrototype.History<Slot>();
        try {
            for(int frame=0;frame<frames;frame++) {
                if(frame==1 && reset){if(scene.equals("resize")){w=80;surface.close();surface=new Surface();}else{stamp+="/diagnostic-reload-epoch";registry.reset();}MetalPreviousVertexHistory.reset();}
                if(frame==1 && scene.equals("source_lease_unknown"))try(var tainted=MetalShaderSourceLibraryTest.rawLibrary(true,false)){
                    var proof=MetalShaderSourceAdapters.libraryProof(tainted);assertNotNull(proof);assertFalse(proof.canonicalOpen());stamp=proof.generation()+"/unproven-source-lease";
                }
                var transaction=history.begin(w,h,stamp,Slot::new);
                MetalPreviousVertexHistory.beginFrame();
                var batches=batches(scene,frame,a,b);var handles=new IdentityHashMap<Element,MetalParticlePixelOwnershipPrototype.Registry.Handle>();
                for(var batch:batches)for(var element:batch)if(!handles.containsKey(element))handles.put(element,registry.capture(element.emitter,element.id,GENERATION+(scene.equals("generation")&&frame>0?1:0)));
                if(frame>0 && scene.equals("unknown_identity"))handles.put(b,null);
                if(frame>0 && scene.equals("source_lease_unknown"))handles.put(b,null);
                if(frame>0 && scene.equals("alias_identity"))assertNull(registry.capture(new Object(),b.id,GENERATION));
                var draws=originalFrame(surface,batches,handles,scene,frame);
                instrumentedFrame(surface,history,transaction,draws);
                if(scene.startsWith("cost_") && frame==1)for(int round=0;round<2;round++)for(boolean proof:new boolean[]{false,true,true,false})colourPass(surface,transaction.current,draws,proof,"ABBA-"+round);
                byte[] raw=read(surface.color),shadow=read(surface.shadow),depth=read(surface.depth),shadowDepth=read(transaction.current.depth);
                assertArrayEquals(raw,shadow,"Instrumentation must preserve every original RGBA byte");assertArrayEquals(depth,shadowDepth,"Instrumentation must preserve every original D32 byte");
                byte[] owners=read(transaction.current.owner);int visible=verifyOwnerPixels(raw,owners,handles,a,b,scene,frame);
                Files.write(directory.resolve("frame-"+frame+"-original.rgba8"),raw);Files.write(directory.resolve("frame-"+frame+"-instrumented.rgba8"),shadow);
                Files.write(directory.resolve("frame-"+frame+"-original.d32f"),depth);Files.write(directory.resolve("frame-"+frame+"-instrumented.d32f"),shadowDepth);Files.write(directory.resolve("frame-"+frame+"-owner.rgba32ui"),owners);
                if(frame>0) {
                    motion(surface,draws);
                    if(scene.equals("foreign_depth"))foreignDepth(surface,b);
                    byte[] accepted=resolve(surface,transaction,scene,false);int valid=count(accepted);
                    boolean reject=scene.equals("occluded_same_z") || scene.equals("generation") || scene.equals("unknown_identity") || scene.equals("alias_identity") || scene.equals("source_lease_unknown") || scene.equals("foreign_depth") || recover&&frame<=2 || reset&&frame==1;
                    assertEquals(reject?0:visible,valid,"Pixel owner/generation/depth must ALL match: "+scene+" frame "+frame);
                    int unsafe=-1;
                    if(frame==1 && List.of("occluded_same_z","generation","foreign_depth").contains(scene)) {
                        unsafe=count(resolve(surface,transaction,scene,true));assertEquals(visible,unsafe,"Removing only the relevant pixel guard must expose false acceptance");
                    }
                    results.add(Map.of("frame",frame,"visiblePixels",visible,"acceptedPixels",valid,"expectedReject",reject,"onlyRelevantGuardRemovedAccepted",unsafe,"oldSlotAvailable",transaction.previous!=null,"width",w,"height",h));
                }
                // These callback states are attached to the real instrumented GPU submission.
                assertEquals(MetalParticlePixelOwnershipPrototype.History.State.COMPLETE,transaction.state);
                if(scene.equals("failed_recover") && frame==1){assertFalse(history.accept(transaction,false));MetalPreviousVertexHistory.discardFrame();}
                else if(recover && frame==1){history.discard(transaction);MetalPreviousVertexHistory.discardFrame();}
                else {assertTrue(history.accept(transaction,true));MetalPreviousVertexHistory.commitSubmittedFrame();}
                for(var draw:draws){draw.ids.close();draw.vertices.close();}
            }
            evidence.put("passed",true);
        } finally {
            history.close();surface.close();device.waitForSubmittedGpuWork();
            for(int i=resources.size()-1;i>=0;i--)resources.get(i).close();
            long closed=textures.stream().filter(MetalGpuTexture::isClosed).count();assertEquals(textures.size(),closed);
            evidence.put("textureAllocations",textures.size());evidence.put("closedTextures",closed);evidence.put("peakLogicalTextureBytes",peakLogicalBytes);
            evidence.put("historyPairLogicalBytesAtCurrentResolution",MetalParticlePixelOwnershipPrototype.bytes(w,h));evidence.put("timings",timings);
            evidence.put("timingBoundary","Bounded offscreen command-buffer GPU timestamps and CPU encoding/wait samples; unavailable GPU timestamps are null, not zero-cost. No FPS, presentation or Temporal quality claim.");
            String json=new com.google.gson.GsonBuilder().serializeNulls().create().toJson(evidence);Files.writeString(directory.resolve("result.json"),json);System.out.println("PARTICLE_PIXEL_OWNERSHIP_PROTOTYPE "+json);
        }
    }

    private List<List<Element>> batches(String scene,int frame,Element a,Element b){
        if(scene.startsWith("split_merge"))return frame==0?List.of(List.of(a),List.of(b)):List.of(scene.endsWith("ba")?List.of(b,a):List.of(a,b));
        if(scene.startsWith("merge_split"))return frame==0?List.of(List.of(a,b)):scene.endsWith("ba")?List.of(List.of(b),List.of(a)):List.of(List.of(a),List.of(b));
        if(frame==0 && (scene.equals("occluded_same_z")||scene.endsWith("recover")))return List.of(List.of(b),List.of(a));
        if(frame==0 && scene.equals("A_before_B"))return List.of(List.of(a),List.of(b));
        return List.of(List.of(b));
    }

    @Test void actualPartialTextureAllocationFailureRollsBackTheCreatedSlots() throws Exception {
        Path dir=Path.of("build/agent-evidence/motion-producers/particle-pixel-ownership-prototype/native_allocation_failure_64x48");Files.createDirectories(dir);
        int first=textures.size();var calls=new java.util.concurrent.atomic.AtomicInteger();
        try(var history=new MetalParticlePixelOwnershipPrototype.History<Slot>()) {
            assertThrows(IllegalStateException.class,()->history.begin(w,h,stamp,()->{
                if(calls.incrementAndGet()==2)throw new IllegalStateException("injected failure before second actual slot allocation");return new Slot();
            }));
            assertEquals(2,textures.size()-first);assertTrue(textures.subList(first,textures.size()).stream().allMatch(MetalGpuTexture::isClosed));
            assertEquals(-1,history.acceptedSlot());var retry=history.begin(w,h,stamp,Slot::new);assertNull(retry.previous);history.discard(retry);
        }
        assertEquals(6,textures.size()-first);assertTrue(textures.subList(first,textures.size()).stream().allMatch(MetalGpuTexture::isClosed));
        String result=new com.google.gson.Gson().toJson(Map.of("sourceCommit",System.getenv().getOrDefault("METALLUM_SOURCE_SHA","local"),"scene","native_allocation_failure","prototypeOnly",true,"passed",true,"actualTexturesCreatedAndClosed",6,"createdBeforeInjectedException",2,"injection","application exception before second slot factory, not forced Metal OOM"));
        Files.writeString(dir.resolve("result.json"),result);System.out.println("PARTICLE_PIXEL_OWNERSHIP_RESOURCE_FAILURE "+result);
    }
    private ByteBuffer mesh(List<Element> elements,String scene,int frame,float z){
        var format=DefaultVertexFormat.PARTICLE;var data=bytes(elements.size()*4*format.getVertexSize());float[][] corners={{-1,-1},{1,-1},{1,1},{-1,1}};
        boolean paired=scene.startsWith("split_")||scene.startsWith("merge_");
        for(int q=0;q<elements.size();q++){var e=elements.get(q);for(int v=0;v<4;v++){
            int base=(q*4+v)*format.getVertexSize();float x=(paired?(e.red?.5F:-.5F):0)+corners[v][0]*(paired?.25F:.5F);
            float y=corners[v][1]*.5F;if(scene.equals("moving_x"))x+=frame==0?-.25F:.25F;if(scene.equals("moving_y"))y+=frame==0?-.25F:.25F;
            if(scene.equals("discard_move_recover"))x+=frame==1?.25F:-.25F;
            data.putFloat(base,x).putFloat(base+4,y).putFloat(base+8,z);int c=base+format.getElement("Color").offset();data.put(c,(byte)(e.red?255:0)).put(c+1,(byte)(e.red?0:255)).put(c+2,(byte)0).put(c+3,(byte)255);
            int uv=base+format.getElement("UV0").offset();data.putFloat(uv,.25F).putFloat(uv+4,.5F);
        }}return data;
    }

    private List<Draw> originalFrame(Surface surface,List<List<Element>> batches,Map<Element,MetalParticlePixelOwnershipPrototype.Registry.Handle> handles,String scene,int frame){
        long start=System.nanoTime();var draws=new ArrayList<Draw>();
        try(var pass=encoder.createRenderPass(descriptor("independent untouched canonical",surface.view(surface.color),surface.view(surface.depth),true).build())){
            bindSource(pass,original);
            for(var batch:batches){
                var token=MetalPreviousVertexHistory.reserveDraw(sample,original);
                var uploaded=MetalParticleAlphaFixtures.capture(token,DefaultVertexFormat.PARTICLE,PrimitiveTopology.QUADS,mesh(batch,scene,frame,.5F),batch.size()*4,batch.size()*6);
                MetalPreviousVertexHistory.stageParticleIdentities(token,batch.stream().map(e->e.id).toList());
                var vertex=buffer("actual freed-slice source",GpuBuffer.USAGE_VERTEX,uploaded);
                long freeze=System.nanoTime();var tickets=new ArrayList<MetalParticlePixelOwnershipPrototype.Registry.Handle>();batch.forEach(e->tickets.add(handles.get(e)));
                var ids=buffer("same emission identity stream",GpuBuffer.USAGE_UNIFORM,registry.freeze(tickets));
                timings.add(Map.of("kind","identity-freeze-cpu","nanos",System.nanoTime()-freeze,"vertices",batch.size()*4));
                pass.setVertexBuffer(0,vertex.slice());pass.drawIndexed(batch.size()*6,1,0,0,0);
                assertTrue(MetalPreviousVertexReplay.captureSourceTransform(token,MetalPreviousVertexReplay.sourceDrawInputs(pass),new Matrix4f()));
                MetalPreviousVertexHistory.stageSurface(token,MetalPreviousVertexReplay.surfaceIdentity(MetalPreviousVertexReplay.sourceState(pass)));
                draws.add(new Draw(batch,vertex,ids,token));
            }
        }
        submitTimed("original-colour",start);return draws;
    }
    private void instrumentedFrame(Surface surface,MetalParticlePixelOwnershipPrototype.History<Slot> history,MetalParticlePixelOwnershipPrototype.History.Frame<Slot> transaction,List<Draw> draws){
        long start=System.nanoTime();encodeColour(surface,transaction.current,draws,true);
        encoder.onCurrentSubmit(()->history.submitted(transaction),()->history.completed(transaction,true),()->history.completed(transaction,false));
        submitTimed("instrumented-colour",start);
    }
    private void colourPass(Surface surface,Slot slot,List<Draw> draws,boolean proof,String label){
        long start=System.nanoTime();encodeColour(surface,slot,draws,proof);submitTimed(label+(proof?"-instrumented":"-original"),start);
    }
    private void encodeColour(Surface surface,Slot slot,List<Draw> draws,boolean proof){
        var d=descriptor(proof?"same canonical main with integer owner MRT":"untouched canonical timing control",surface.view(proof?surface.shadow:surface.color),proof?slot.dv:surface.view(surface.depth),true);
        if(proof)d.withColorAttachment(slot.ov,Optional.of(new Vector4f(0)));
        try(var pass=encoder.createRenderPass(d.build())){
            bindSource(pass,proof?instrumented:original);
            for(var draw:draws){if(proof)pass.setUniform("ProofIdentity",draw.ids.slice());pass.setVertexBuffer(0,draw.vertices.slice());pass.drawIndexed(draw.elements.size()*6,1,0,0,0);}
        }
    }
    private void bindSource(MetalRenderPass pass,RenderPipeline pipeline){
        pass.setPipeline(pipeline);pass.setUniform("Projection",projection.slice());pass.setUniform("DynamicTransforms",dynamic.slice());pass.setUniform("Fog",fog.slice());
        pass.bindTexture("Sampler0",whiteView,sampler);pass.bindTexture("Sampler2",whiteView,sampler);pass.setIndexBuffer(indices,IndexType.SHORT);
    }
    private void motion(Surface surface,List<Draw> draws){
        encoder.copyTextureToTexture(surface.depth,surface.privateDepth,0,0,0,0,0,w,h);long start=System.nanoTime();
        var d=RenderPassDescriptor.builder(()->"unchanged shipping motion candidate").withRenderArea(new RenderPass.RenderArea(0,0,w,h))
                .withColorAttachment(surface.view(surface.xy),Optional.of(new Vector4f(0)))
                .withColorAttachment(surface.view(surface.valid),Optional.of(new Vector4f(0)))
                .withColorAttachment(surface.view(surface.pair),Optional.of(new Vector4f(-1)))
                .withDepthAttachment(surface.view(surface.privateDepth));
        var buffers=new ArrayList<GpuBuffer>();
        try(var pass=encoder.createRenderPass(d.build())){
            for(var draw:draws){
                var info=new StagedVertexBuffer.ExecuteInfo(draw.vertices,indices,IndexType.SHORT,0,0,draw.elements.size()*6,PrimitiveTopology.QUADS);
                var exact=MetalPreviousVertexReplay.plan(original,info,draw.token);
                var selected=exact!=null?exact:MetalPreviousVertexReplay.currentOnlyPlan(original,info,draw.token);assertNotNull(selected);
                var transforms=exact!=null?MetalPreviousVertexReplay.worldTransforms(draw.token):MetalPreviousVertexReplay.currentOnlyTransforms(draw.token,false);assertNotNull(transforms);
                ByteBuffer previous=bytes(draw.elements.size()*4*16);MetalPreviousVertexReplay.writePreviousPositions(selected.previousPositions(),previous);previous.flip();
                var p=buffer("shipping previous positions",GpuBuffer.USAGE_VERTEX,previous);buffers.add(p);
                ByteBuffer mat=bytes(128);transforms.currentUnjitteredFromRaster().get(0,mat);transforms.previousFromPosition().get(64,mat);
                var m=buffer("shipping source matrices",GpuBuffer.USAGE_UNIFORM,mat);buffers.add(m);
                pass.setPipeline(MetalEntityMotionPipeline.forPreviousPositionsWithDepth(original));
                pass.setUniform("Projection",projection.slice());pass.setUniform("DynamicTransforms",dynamic.slice());pass.setUniform("MetallumMotion",m.slice());
                pass.bindTexture("Sampler0",whiteView,sampler);pass.bindTexture("Sampler2",whiteView,sampler);pass.bindTexture("MetallumSourceDepth",surface.view(surface.depth),sampler);
                pass.setVertexBuffer(0,selected.currentVertexBuffer());pass.setVertexBuffer(1,p.slice());pass.setIndexBuffer(indices,IndexType.SHORT);pass.drawIndexed(draw.elements.size()*6,1,0,selected.replayBaseVertex(),0);
            }
        }
        submitTimed("shipping-motion",start);buffers.forEach(GpuBuffer::close);
        try{Files.write(directory.resolve("motion-"+timings.size()+".rg16f"),read(surface.xy));Files.write(directory.resolve("pair-"+timings.size()+".rg32f"),read(surface.pair));}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
    }
    private RenderPipeline resolver(String removed){
        return resolvers.computeIfAbsent(removed,key->{
            var v=id("resolve_vertex");var f=id("resolve_fragment_"+key);String fs=MetalParticlePixelOwnershipPrototype.RESOLVE_FRAGMENT;
            if(key.equals("owner"))fs=fs.replace("any(notEqual(current,old))","any(notEqual(current.zw,old.zw))");
            if(key.equals("generation"))fs=fs.replace("any(notEqual(current,old))","any(notEqual(current.xy,old.xy))");
            if(key.equals("depth"))fs=fs.replace("if(abs(previousDepth-z.x)>0.000003 || abs(currentDepth-z.y)>0.000003)return;","");
            var layout=BindGroupLayout.builder().withUniform("ProofState",UniformType.UNIFORM_BUFFER);
            for(String name:List.of("OldOwner","OldDepth","CurrentOwner","CurrentDepth","SourceMotion","SourcePair","SourceValid"))layout.withUniform(name,UniformType.COMBINED_IMAGE_SAMPLER);
            var p=RenderPipeline.builder().withLocation(id("resolve_"+key)).withVertexShader(v).withFragmentShader(f).withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.R8_UNORM,ColorTargetState.WRITE_ALL)).withBindGroupLayout(layout.build()).build();
            device.precompilePipeline(p,MetalShaderSourceAdapters.overlay(Map.of(v,MetalParticlePixelOwnershipPrototype.RESOLVE_VERTEX,f,fs),library));return p;
        });
    }
    private byte[] resolve(Surface surface,MetalParticlePixelOwnershipPrototype.History.Frame<Slot> frame,String scene,boolean removeGuard) throws Exception {
        String removed=!removeGuard?"none":scene.equals("generation")?"generation":scene.equals("foreign_depth")?"depth":"owner";
        var old=frame.previous!=null?frame.previous:frame.current;ByteBuffer state=bytes(16);state.putInt(0,frame.previous==null?0:1);
        var proof=buffer("source epoch and accepted slot",GpuBuffer.USAGE_UNIFORM,state);long start=System.nanoTime();
        try(var pass=encoder.createRenderPass(descriptor("diagnostic pixel ownership acceptance",surface.view(surface.accepted),null,false).build())){
            pass.setPipeline(resolver(removed));pass.setUniform("ProofState",proof.slice());
            pass.bindTexture("OldOwner",old.ov,sampler);pass.bindTexture("OldDepth",scene.equals("foreign_depth")?surface.view(surface.foreignDepth):old.dv,sampler);
            pass.bindTexture("CurrentOwner",frame.current.ov,sampler);pass.bindTexture("CurrentDepth",frame.current.dv,sampler);
            pass.bindTexture("SourceMotion",surface.view(surface.xy),sampler);pass.bindTexture("SourcePair",surface.view(surface.pair),sampler);pass.bindTexture("SourceValid",surface.view(surface.valid),sampler);
            pass.draw(3,1,0,0);
        }
        submitTimed("pixel-proof-"+removed,start);proof.close();byte[] out=read(surface.accepted);
        Files.write(directory.resolve("proof-"+timings.size()+"-"+removed+".r8"),out);return out;
    }
    private void foreignDepth(Surface surface,Element b){
        var data=buffer("independent original wrong-depth element",GpuBuffer.USAGE_VERTEX,mesh(List.of(b),"continuous",0,.25F));long start=System.nanoTime();
        try(var pass=encoder.createRenderPass(descriptor("actual original shader foreign-depth negative",surface.view(surface.foreignColor),surface.view(surface.foreignDepth),true).build())){
            bindSource(pass,original);pass.setVertexBuffer(0,data.slice());pass.drawIndexed(6,1,0,0,0);
        }
        submitTimed("independent-depth-negative",start);data.close();
    }
    private int verifyOwnerPixels(byte[] raw,byte[] owner,Map<Element,MetalParticlePixelOwnershipPrototype.Registry.Handle> handles,Element a,Element b,String scene,int frame){
        int visible=0;ByteBuffer ids=ByteBuffer.wrap(owner).order(ByteOrder.nativeOrder());
        for(int p=0;p<w*h;p++){
            Element e=Byte.toUnsignedInt(raw[p*4])==255?b:Byte.toUnsignedInt(raw[p*4+1])==255?a:null;
            var handle=e==null?null:handles.get(e);boolean known=registry.live(handle);
            long actual=Integer.toUnsignedLong(ids.getInt(p*16))|(Integer.toUnsignedLong(ids.getInt(p*16+4))<<32);
            long gen=Integer.toUnsignedLong(ids.getInt(p*16+8))|(Integer.toUnsignedLong(ids.getInt(p*16+12))<<32);
            assertEquals(known?handle.id:0,actual,"Raster winner must match independent original pixel color at "+p);assertEquals(known?handle.generation:0,gen);
            if(e!=null)visible++;
        }
        assertTrue(visible>100);return visible;
    }
    private RenderPassDescriptor.Builder descriptor(String label,GpuTextureView color,GpuTextureView depth,boolean clearDepth){
        var d=RenderPassDescriptor.builder(()->label).withRenderArea(new RenderPass.RenderArea(0,0,w,h)).withColorAttachment(color,Optional.of(new Vector4f(0)));
        if(depth!=null)d.withDepthAttachment(depth,clearDepth?OptionalDouble.of(0):OptionalDouble.empty());return d;
    }
    private void submitTimed(String kind,long encodeStart){
        var command=encoder.commandBuffer();long encoded=System.nanoTime()-encodeStart,waitStart=System.nanoTime();double[] gpu={Double.NaN};
        encoder.onCurrentSubmit(()->{},()->{double start=command.gpuStartTime(),end=command.gpuEndTime();if(start>0&&end>start&&Double.isFinite(end))gpu[0]=(end-start)*1e9;},()->{throw new IllegalStateException("Actual diagnostic command buffer failed");});
        encoder.submit();device.waitForSubmittedGpuWork();var row=new LinkedHashMap<String,Object>();row.put("kind",kind);row.put("cpuEncodeNs",encoded);row.put("cpuSubmitWaitNs",System.nanoTime()-waitStart);row.put("gpuNs",Double.isFinite(gpu[0])?gpu[0]:null);row.put("width",w);row.put("height",h);timings.add(row);
    }
    private static int count(byte[] mask){int n=0;for(byte v:mask){assertTrue(v==0||Byte.toUnsignedInt(v)==255);if(v!=0)n++;}return n;}
    private MetalGpuTexture texture(String name,GpuFormat format,int width,int height){
        var t=(MetalGpuTexture)device.createTexture(name,GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_SRC|GpuTexture.USAGE_COPY_DST,format,width,height,1,1);
        textures.add(t);resources.add(t);logicalBytes.put(t,(long)width*height*t.pixelSize());long live=logicalBytes.entrySet().stream().filter(e->!e.getKey().isClosed()).mapToLong(Map.Entry::getValue).sum();peakLogicalBytes=Math.max(peakLogicalBytes,live);return t;
    }
    private GpuBuffer buffer(String name,int usage,ByteBuffer data){return track(device.createBuffer(()->name,usage,data));}
    private <T extends AutoCloseable>T track(T value){resources.add(value);return value;}
    private byte[] read(MetalGpuTexture texture){
        int size=texture.getWidth(0)*texture.getHeight(0)*texture.pixelSize();
        try(var b=(MetalGpuBuffer)device.createBuffer(()->"actual diagnostic pixel readback",GpuBuffer.USAGE_MAP_READ|GpuBuffer.USAGE_COPY_DST,size)){
            encoder.copyTextureToBuffer(texture,b,0,()->{},0);encoder.submit();device.waitForSubmittedGpuWork();byte[] out=new byte[size];b.currentStorage().get(0,out);return out;
        }
    }
    private static ByteBuffer bytes(int n){return ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder());}
    private static ByteBuffer matrix(Matrix4f m){var out=bytes(64);m.get(0,out);return out;}
    private static Identifier id(String name){return Identifier.fromNamespaceAndPath("metallum","pixel_ownership_diagnostic/"+name);}
}
