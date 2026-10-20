package com.metallum.client.metal.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.AddressMode;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Source-submit history for the two real weather draws, separate from particle feature staging. */
public final class MetalWeatherMotion {
    public enum Kind { RAIN, SNOW }
    public record Rates(double time,long phaseEpoch,double u,double v) {}
    private static final long GENERATION=-0x57454154484552L;
    private static long nextObjectId=0x5745415400000000L;
    private static boolean frameOpen;
    private static final Map<Object,Layer[]> LAYERS=new IdentityHashMap<>();
    private static final Map<MetalPreviousVertexHistory.DrawToken,DrawData> DRAWS=new HashMap<>();

    private static final class RainOwner {}
    private static final class SnowOwner {}
    private static final class Layer {
        final long objectId=++nextObjectId;
        final Object owner;
        @Nullable Prepared prepared;
        @Nullable Prepared pending;
        @Nullable Prepared previous;
        boolean submitted;
        Layer(Kind kind) {owner=kind==Kind.RAIN ? new RainOwner() : new SnowOwner();}
    }
    private record Prepared(List<MetalWeatherCorrespondence.Column> columns,int firstColumn,byte[] vertices) {
        Prepared {columns=List.copyOf(columns);vertices=vertices.clone();}
        Map<Long,MetalWeatherCorrespondence.Column> byId() {
            Map<Long,MetalWeatherCorrespondence.Column> result=new HashMap<>();
            for(var column:columns)if(result.put(column.id(),column)!=null)return Map.of();
            return Map.copyOf(result);
        }
    }
    private record DrawData(List<MetalWeatherCorrespondence.Column> current,
                            Map<Long,MetalWeatherCorrespondence.Column> previous,GpuBufferSlice currentVertices,
                            double uPeriod,double vPeriod) {}

    private MetalWeatherMotion() {}
    public static boolean collecting() {return MetalEntityMotionCapture.isEnabled();}

    /** Vanilla's camera-cell direction is 0/0. A finite zero-width quad preserves its empty coverage. */
    public static void repairZeroRadiusColumn(float[] x,float[] z) {
        if(x.length==1024 && z.length==1024) {x[16*32+16]=0;z[16*32+16]=0;}
    }

    static void beginFrame() {
        for(var layers:LAYERS.values())for(var layer:layers) {layer.pending=null;layer.submitted=false;}
        DRAWS.clear();frameOpen=true;
    }
    static void commitSubmittedFrame() {
        if(!frameOpen)return;
        for(var layers:LAYERS.values())for(var layer:layers) {
            layer.previous=layer.submitted ? layer.pending : null;layer.pending=null;layer.submitted=false;
        }
        DRAWS.clear();frameOpen=false;
    }
    static void discardFrame() {
        for(var layers:LAYERS.values())for(var layer:layers) {layer.pending=null;layer.submitted=false;}
        DRAWS.clear();frameOpen=false;
    }
    static void reset() {LAYERS.clear();DRAWS.clear();frameOpen=false;}
    static void resetHistory() {
        for(var layers:LAYERS.values())for(var layer:layers) {layer.previous=null;layer.pending=null;layer.submitted=false;}
        DRAWS.clear();
    }
    public static void closeRenderer(Object renderer) {LAYERS.remove(renderer);}

    /** Called at the actual mesh upload while Minecraft still owns the source CPU bytes. */
    public static void prepare(Object renderer,WeatherRenderState state,ByteBuffer vertices,
            Map<WeatherEffectRenderer.ColumnInstance,Rates> rates) {
        // A new upload replaces both source ranges even if its metadata is incomplete.
        // This also applies while capture is disabled: re-enabling must not
        // replay bytes from before an unobserved upload. Do not allocate an
        // owner merely to invalidate a renderer that has never been captured.
        var existing=LAYERS.get(renderer);
        if(existing!=null)for(var layer:existing)layer.prepared=null;
        if(!collecting())return;
        int total=state.rainColumns.size()+state.snowColumns.size();
        int stride=DefaultVertexFormat.PARTICLE.getVertexSize();
        if((long)total*4*stride>vertices.remaining())return;
        ByteBuffer bytes=vertices.duplicate().order(ByteOrder.nativeOrder());int base=bytes.position();
        prepareLayer(renderer,Kind.RAIN,state.rainColumns,0,bytes,base,stride,rates);
        prepareLayer(renderer,Kind.SNOW,state.snowColumns,state.rainColumns.size(),bytes,base,stride,rates);
    }
    private static void prepareLayer(Object renderer,Kind kind,List<WeatherEffectRenderer.ColumnInstance> columns,int first,
            ByteBuffer bytes,int base,int stride,Map<WeatherEffectRenderer.ColumnInstance,Rates> rates) {
        var result=new ArrayList<MetalWeatherCorrespondence.Column>(columns.size());
        int position=DefaultVertexFormat.PARTICLE.getElement("Position").offset();
        int uv=DefaultVertexFormat.PARTICLE.getElement("UV0").offset();
        for(int column=0;column<columns.size();column++) {
            var source=columns.get(column);var rate=rates.get(source);
            if(rate==null)return;
            float[] xyz=new float[12],texture=new float[8];
            for(int vertex=0;vertex<4;vertex++) {
                int offset=base+((first+column)*4+vertex)*stride;
                for(int axis=0;axis<3;axis++)xyz[vertex*3+axis]=bytes.getFloat(offset+position+axis*4);
                for(int axis=0;axis<2;axis++)texture[vertex*2+axis]=bytes.getFloat(offset+uv+axis*4);
            }
            long id=((long)source.x()<<32)^(source.z()&0xffffffffL);
            result.add(new MetalWeatherCorrespondence.Column(id,rate.time(),rate.phaseEpoch(),source.uOffset(),source.vOffset(),
                    rate.u(),rate.v(),xyz,texture));
        }
        byte[] vertexBytes=new byte[columns.size()*4*stride];
        bytes.get(base+first*4*stride,vertexBytes);
        stagePrepared(renderer,kind,result,first,vertexBytes);
    }
    static void stagePrepared(Object renderer,Kind kind,List<MetalWeatherCorrespondence.Column> columns,int first,byte[] vertices) {
        Layer layer=layer(renderer,kind);Prepared prepared=new Prepared(columns,first,vertices);
        layer.prepared=prepared.byId().size()==columns.size() ? prepared : null;
    }
    private static Layer layer(Object renderer,Kind kind) {
        return LAYERS.computeIfAbsent(renderer,ignored->new Layer[]{new Layer(Kind.RAIN),new Layer(Kind.SNOW)})[kind.ordinal()];
    }
    static MetalEntityMotionCapture.@Nullable Sample sample(Object renderer,Kind kind) {
        Layer layer=layer(renderer,kind);
        if(layer.prepared==null || layer.prepared.columns().isEmpty())return null;
        Matrix4f identity=new Matrix4f();
        return new MetalEntityMotionCapture.Sample(layer.objectId,GENERATION,identity,layer.previous==null ? null : identity,
                FrameSynthesisContract.ProducerDomain.PARTICLES_WEATHER);
    }
    static MetalWeatherCorrespondence.@Nullable Column previousColumn(Object renderer,Kind kind,long id) {
        var previous=layer(renderer,kind).previous;return previous==null ? null : previous.byId().get(id);
    }

    /** Runs after the real draw, while its pipeline, uniforms, textures and index buffer are bound. */
    public static void afterDraw(Object renderer,Kind kind,RenderPass pass,int indexCount,int instances,
            int firstIndex,int baseVertex,int firstInstance) {
        if(!collecting() || indexCount<=0 || instances<=0)return;
        var geometry=MetalPreviousVertexReplay.sourceGeometry(pass);
        if(geometry==null)return; // No indexed command was issued without an index buffer.
        var source=MetalPreviousVertexReplay.sourceState(pass);
        OitStage stage=stage(source==null ? null : source.pipeline());
        if(stage!=null && stage!=OitStage.ACCUMULATE) {
            MetalMotionDrawCoverage.sourceExecution(stage,null,false);MetalMotionDrawCoverage.hookedDraw(source,stage,null);return;
        }
        Layer layer=layer(renderer,kind);Prepared current=layer.prepared;
        var texture=source==null ? null : source.textures().get("Sampler0");
        int stride=DefaultVertexFormat.PARTICLE.getVertexSize();
        if(!frameOpen || current==null || source==null || texture==null || geometry.vertices()==null
                || layer.submitted || current.vertices.length!=current.columns().size()*4*stride
                || instances!=1 || baseVertex!=0 || firstInstance!=0
                || indexCount!=current.columns().size()*6 || firstIndex!=current.firstColumn()*6
                || geometry.vertices().offset()%stride!=0
                || (long)(current.firstColumn()+current.columns().size())*4*stride>geometry.vertices().length()
                || !MetalEntityMotionPipeline.supportsPreviousPositions(source.pipeline())
                || !source.pipeline().getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX).getPath().equals("core/particle")
                || geometry.indices()!=RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer()) {
            unsupported(source,stage);return;
        }
        var sample=sample(renderer,kind);if(sample==null) {unsupported(source,stage);return;}
        MetalEntityMotionCapture.attachState(layer.owner,sample);MetalEntityMotionCapture.requireExactState(layer.owner);
        MetalFxManager.observeParticlesWeather(1);
        if(sample.hasPrevious())MetalFxManager.markExactParticleProducerCandidate(sample);
        var token=MetalPreviousVertexHistory.reserveDraw(sample,source.pipeline());
        if(token==null) {unsupported(source,stage);return;}
        GpuBufferSlice frozen=MetalFxManager.freezeMotionVertices(current.vertices);
        if(frozen==null) {MetalEntityMotionCapture.failExactSample(sample,"weather-source-vertex-snapshot-unavailable");unsupported(source,stage);return;}
        int vertexCount=current.columns().size()*4;
        float[] positions=new float[vertexCount*3];var ids=new ArrayList<Long>(current.columns().size());
        int column=0;for(var data:current.columns()) {System.arraycopy(data.positions(),0,positions,column++*12,12);ids.add(data.id());}
        var format=DefaultVertexFormat.PARTICLE;
        MetalPreviousVertexHistory.stageSnapshot(token,new MetalPreviousVertexHistory.Signature(token.pipelineKey(),format.getElements(),
                stride,PrimitiveTopology.QUADS,vertexCount,indexCount),positions);
        MetalPreviousVertexHistory.stageQuadIdentities(token,ids);
        DRAWS.put(token,new DrawData(current.columns(),layer.previous==null ? Map.of() : layer.previous.byId(),frozen,
                period(texture.sampler().getAddressModeU()),period(texture.sampler().getAddressModeV())));
        sourceSubmitted(renderer,kind);
        // Rebase both bindings and standard indices: snow follows rain in the source vertex buffer.
        long replayBase=geometry.vertices().offset()/stride+(long)current.firstColumn()*4;
        if(replayBase>Integer.MAX_VALUE) {MetalEntityMotionCapture.failExactSample(sample,"weather-vertex-base-overflow");return;}
        var info=new StagedVertexBuffer.ExecuteInfo(geometry.vertices().buffer(),geometry.indices(),geometry.indexType(),
                (int)replayBase,0,indexCount,PrimitiveTopology.QUADS);
        MetalFxManager.drawDirectMotion(info,sample,token,stage,pass);
        // Resolve against world depth before the hand pass can clear/rewrite its source attachment.
        MetalPreviousVertexReplay.flushAfterSourcePass(pass);
    }
    private static double period(AddressMode mode) {return mode==AddressMode.REPEAT ? 1 : 0;}
    static void sourceSubmitted(Object renderer,Kind kind) {
        Layer layer=layer(renderer,kind);
        if(frameOpen && layer.prepared!=null) {layer.pending=layer.prepared;layer.submitted=true;}
    }
    private static @Nullable OitStage stage(@Nullable RenderPipeline pipeline) {
        if(pipeline==null)return null;var flags=pipeline.getShaderDefines().flags();
        if(flags.contains("OIT_DEPTH_BOUNDS"))return OitStage.DEPTH_BOUNDS;
        if(flags.contains("OIT_TRANSMITTANCE"))return OitStage.TRANSMITTANCE;
        return flags.contains("OIT_ACCUMULATE") ? OitStage.ACCUMULATE : null;
    }
    private static void unsupported(MetalPreviousVertexReplay.@Nullable SourceState source,@Nullable OitStage stage) {
        MetalEntityMotionCapture.recordMotionDrawSkip("weather-source-contract-unavailable");
        MetalMotionDrawCoverage.sourceExecution(stage,null,false);MetalMotionDrawCoverage.hookedDraw(source,stage,null);
        MetalFxManager.observeReactiveParticlesWeather(1);MetalFxManager.observeParticleMotion();
    }
    static MetalPreviousVertexReplay.@Nullable Plan plan(MetalPreviousVertexHistory.DrawToken token,boolean previous) {
        var data=DRAWS.get(token);if(data==null)return null;
        if(previous && MetalPreviousVertexHistory.matchedPreviousPositions(token)==null)return null;
        float[] positions=new float[data.current().size()*12],coverage=new float[data.current().size()*8];
        if(!previous) {
            java.util.Arrays.fill(positions,Float.NaN);
            return new MetalPreviousVertexReplay.Plan(data.currentVertices(),0,positions);
        }
        for(int column=0;column<data.current().size();column++) {
            var current=data.current().get(column);
            var old=MetalWeatherCorrespondence.reproject(data.previous().get(current.id()),current,data.uPeriod(),data.vPeriod());
            System.arraycopy(old.positions(),0,positions,column*12,12);
            System.arraycopy(old.previousCoverage(),0,coverage,column*8,8);
        }
        return new MetalPreviousVertexReplay.Plan(data.currentVertices(),0,positions,coverage);
    }
}
