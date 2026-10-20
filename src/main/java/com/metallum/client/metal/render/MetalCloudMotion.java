package com.metallum.client.metal.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.oit.OitStage;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/** Actual procedural cloud draws, with face identities and accepted-source mesh/transform history. */
public final class MetalCloudMotion {
    private static long nextObjectId=0x434C4F5500000000L,nextGeneration=-0x434C4F5544L,nextFaceId;
    private static boolean frameOpen;
    private static final Map<Object,Layer> LAYERS=new IdentityHashMap<>();
    private static final Map<MetalPreviousVertexHistory.DrawToken,GpuBufferSlice> DRAWS=new HashMap<>();
    private static final class CloudOwner {}
    private static final class Layer {
        final long objectId=++nextObjectId;
        final CloudOwner owner=new CloudOwner();
        long generation=--nextGeneration;
        byte @Nullable [] faces;
        @Nullable GpuBuffer faceBacking;
        MetalCloudGeometry.@Nullable Origin origin;
        Map<MetalCloudGeometry.FaceKey,Long> previousIds=Map.of(),pendingIds=Map.of();
        boolean previousSubmitted,submitted;
    }
    private MetalCloudMotion() {}
    private static Layer layer(Object renderer) {return LAYERS.computeIfAbsent(renderer,ignored->new Layer());}
    public static boolean collecting() {return MetalEntityMotionCapture.isEnabled();}
    public static boolean hasFaces(Object renderer) {var layer=LAYERS.get(renderer);return layer!=null && layer.faces!=null;}
    public static void faces(Object renderer,byte[] bytes,@Nullable GpuBuffer backing) {
        var layer=layer(renderer);layer.faces=bytes.clone();layer.faceBacking=backing;
    }
    public static void prepared(Object renderer,double cameraX,double cameraZ,long time,float partial,int textureWidth,int textureHeight,
            int sourceCellX,int sourceCellZ) {
        if(collecting())layer(renderer).origin=MetalCloudGeometry.origin(cameraX,cameraZ,time,partial,textureWidth,textureHeight,sourceCellX,sourceCellZ);
    }
    public static void reloaded(Object renderer) {
        var layer=LAYERS.get(renderer);if(layer==null)return;
        layer.generation=--nextGeneration;layer.faces=null;layer.faceBacking=null;layer.origin=null;
        layer.previousIds=Map.of();layer.pendingIds=Map.of();layer.previousSubmitted=false;layer.submitted=false;
    }
    public static void closeRenderer(Object renderer) {LAYERS.remove(renderer);}
    static void beginFrame() {for(var layer:LAYERS.values()){layer.submitted=false;layer.pendingIds=Map.of();}DRAWS.clear();frameOpen=true;}
    static void commitSubmittedFrame() {
        if(!frameOpen)return;
        for(var layer:LAYERS.values()) {
            layer.previousSubmitted=layer.submitted;layer.previousIds=layer.submitted ? layer.pendingIds : Map.of();
            layer.submitted=false;layer.pendingIds=Map.of();
        }
        DRAWS.clear();frameOpen=false;
    }
    static void discardFrame() {for(var layer:LAYERS.values()){layer.submitted=false;layer.pendingIds=Map.of();}DRAWS.clear();frameOpen=false;}
    static void resetHistory() {
        for(var layer:LAYERS.values()) {layer.previousIds=Map.of();layer.pendingIds=Map.of();layer.previousSubmitted=false;layer.submitted=false;}
        DRAWS.clear();
    }
    static void reset() {LAYERS.clear();DRAWS.clear();frameOpen=false;}

    public static void afterDraw(Object renderer,RenderPass pass,int count,int instances,int first,int base,int instance) {
        if(!collecting() || count<=0 || instances<=0)return;
        var geometry=MetalPreviousVertexReplay.sourceGeometry(pass);if(geometry==null)return;
        var source=MetalPreviousVertexReplay.sourceState(pass);
        var flags=source==null ? java.util.Set.<String>of() : source.pipeline().getShaderDefines().flags();
        OitStage stage=flags.contains("OIT_DEPTH_BOUNDS") ? OitStage.DEPTH_BOUNDS
                : flags.contains("OIT_TRANSMITTANCE") ? OitStage.TRANSMITTANCE
                : flags.contains("OIT_ACCUMULATE") ? OitStage.ACCUMULATE : null;
        if(stage!=null && stage!=OitStage.ACCUMULATE) {
            MetalMotionDrawCoverage.sourceExecution(stage,null,false);MetalMotionDrawCoverage.hookedDraw(source,stage,null);return;
        }
        var layer=layer(renderer);
        var info=MetalPreviousVertexReplay.sourceUniform(pass,"CloudInfo");
        var faces=MetalPreviousVertexReplay.sourceUniform(pass,"CloudFaces");
        byte[] bytes=info!=null && info.buffer() instanceof MetalGpuBuffer buffer
                ? buffer.copySourceUniformBytes(info,MetalCloudGeometry.INFO_BYTES) : null;
        if(!frameOpen || layer.submitted || layer.faces==null || layer.origin==null || bytes==null || source==null
                || faces==null || faces.buffer()!=layer.faceBacking || faces.offset()!=0 || faces.length()<layer.faces.length
                || !MetalEntityMotionPipeline.supportsPreviousPositions(source.pipeline())
                || !source.pipeline().getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX).getPath().equals("core/clouds")
                || count!=(long)layer.faces.length/3*6 || geometry.vertices()!=null
                || instances!=1 || first!=0 || base!=0 || instance!=0
                || geometry.indices()!=RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer()) {unsupported(source,stage);return;}
        var mesh=MetalCloudGeometry.decode(layer.faces,bytes,layer.origin);
        if(mesh==null) {unsupported(source,stage);return;}
        var sample=sample(renderer);if(sample==null){unsupported(source,stage);return;}
        MetalEntityMotionCapture.attachState(layer.owner,sample);MetalEntityMotionCapture.requireExactState(layer.owner);
        MetalFxManager.observeParticlesWeather(1);if(sample.hasPrevious())MetalFxManager.markExactParticleProducerCandidate(sample);
        var token=MetalPreviousVertexHistory.reserveDraw(sample,source.pipeline());
        if(token==null){unsupported(source,stage);return;}
        var frozen=MetalFxManager.freezeMotionVertices(mesh.vertices());
        if(frozen==null) {MetalEntityMotionCapture.failExactSample(sample,"cloud-source-snapshot-unavailable");unsupported(source,stage);return;}
        var ids=identities(renderer,mesh.faceKeys());
        var format=DefaultVertexFormat.POSITION_COLOR;
        MetalPreviousVertexHistory.stageSnapshot(token,new MetalPreviousVertexHistory.Signature(token.pipelineKey(),format.getElements(),
                format.getVertexSize(),PrimitiveTopology.QUADS,mesh.faceKeys().size()*4,count),mesh.positions());
        MetalPreviousVertexHistory.stageQuadIdentities(token,new ArrayList<>(ids.values()));
        DRAWS.put(token,frozen);sourceSubmitted(renderer,ids);
        var draw=new StagedVertexBuffer.ExecuteInfo(frozen.buffer(),geometry.indices(),geometry.indexType(),0,0,count,PrimitiveTopology.QUADS);
        MetalFxManager.drawDirectMotion(draw,sample,token,stage,pass,MetalCloudMotion::plan);
        MetalPreviousVertexReplay.flushAfterSourcePass(pass);
    }
    static Map<MetalCloudGeometry.FaceKey,Long> identities(Object renderer,java.util.List<MetalCloudGeometry.FaceKey> keys) {
        var layer=layer(renderer);
        // Preserve source emission order, while exact tuple equality owns the persistent ID.
        var result=new java.util.LinkedHashMap<MetalCloudGeometry.FaceKey,Long>();
        for(var key:keys) {Long id=layer.previousIds.get(key);result.put(key,id==null ? ++nextFaceId : id);}
        return result;
    }
    static void sourceSubmitted(Object renderer,Map<MetalCloudGeometry.FaceKey,Long> ids) {
        var layer=layer(renderer);
        if(frameOpen){layer.pendingIds=Map.copyOf(ids);layer.submitted=true;}
    }
    static MetalEntityMotionCapture.@Nullable Sample sample(Object renderer) {
        var layer=layer(renderer);if(layer.faces==null || layer.faces.length==0 || layer.origin==null)return null;
        var identity=new Matrix4f();return new MetalEntityMotionCapture.Sample(layer.objectId,layer.generation,identity,
                layer.previousSubmitted ? identity : null,FrameSynthesisContract.ProducerDomain.PARTICLES_WEATHER);
    }
    private static void unsupported(MetalPreviousVertexReplay.@Nullable SourceState source,@Nullable OitStage stage) {
        MetalMotionDrawCoverage.sourceExecution(stage,null,false);MetalMotionDrawCoverage.hookedDraw(source,stage,null);
        MetalEntityMotionCapture.recordMotionDrawSkip("cloud-source-contract-unavailable");
        MetalFxManager.observeReactiveParticlesWeather(1);MetalFxManager.observeParticleMotion();
    }
    private static MetalPreviousVertexReplay.@Nullable Plan plan(MetalPreviousVertexHistory.DrawToken token,boolean previous) {
        var vertices=DRAWS.get(token);if(vertices==null)return null;
        float[] positions=previous ? MetalPreviousVertexHistory.matchedPreviousPositions(token) : new float[MetalPreviousVertexHistory.currentVertexCount(token)*3];
        if(positions==null)return null;
        if(!previous)java.util.Arrays.fill(positions,Float.NaN);
        return new MetalPreviousVertexReplay.Plan(vertices,0,positions);
    }
}
