package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Map;
import java.util.TreeMap;

/** Actual UI render draws. Unknown shader ranges and non-render writes remain explicit gates. */
public final class MetalUiDrawCoverage {
    public record Program(MetalMotionProgramIdentity.Description source,int attachment,
                          MetalUiBlendContract.State blend,long calls,boolean indirect) {}
    public record Snapshot(long frameId,long epoch,long allocation,long allocationGeneration,boolean sceneSeparated,
                           boolean overflow,Map<String,Program> programs,boolean shaderOutputRangeProven,
                           boolean completeWriteCoverageProven,boolean premultipliedProven) {}
    private static final Map<String,Program> PROGRAMS=new TreeMap<>();
    private static FrameSynthesisContract.FrameStamp stamp;
    private static MetalGpuTexture target;
    private static boolean separated,overflow;
    static void reset() {PROGRAMS.clear();stamp=null;target=null;separated=overflow=false;}
    static void begin(FrameSynthesisContract.FrameStamp source,MetalGpuTexture texture,boolean sceneSeparated) {
        reset();stamp=source;target=texture;separated=sceneSeparated;
    }
    static void actualDraw(MetalCompiledRenderPipeline compiled,GpuTextureView[] attachments,int calls,boolean indirect) {
        if(target==null || stamp==null || calls<=0 || compiled==null)return;
        var pipeline=compiled.sourcePipeline();
        for(int slot=0;slot<attachments.length;slot++) {
            var view=attachments[slot];
            if(view==null || view.texture()!=target)continue;
            if(pipeline==null || slot>=pipeline.getColorTargetStates().size()) {overflow=true;continue;}
            ColorTargetState color=pipeline.getColorTargetStates().get(slot);
            if(color==null || color.writeMask()==0)continue;
            var identity=MetalMotionDrawCoverage.programIdentity(pipeline);
            var blend=color.blendFunction().orElse(null);
            var state=new MetalUiBlendContract.State(color.writeMask()==ColorTargetState.WRITE_ALL,
                    blend==null ? null : new MetalUiBlendContract.Equation(blend.color().sourceFactor().name(),
                            blend.color().destFactor().name(),blend.color().op().name()),
                    blend==null ? null : new MetalUiBlendContract.Equation(blend.alpha().sourceFactor().name(),
                            blend.alpha().destFactor().name(),blend.alpha().op().name()));
            String key=identity.key()+":attachment="+slot;
            var old=PROGRAMS.get(key);
            if(old==null && PROGRAMS.size()>=256) {overflow=true;continue;}
            PROGRAMS.put(key,new Program(identity.source(),slot,state,calls+(old==null ? 0 : old.calls()),
                    indirect || old!=null && old.indirect()));
        }
    }
    public static Snapshot snapshot() {
        return new Snapshot(stamp==null ? 0 : stamp.frameId(),stamp==null ? 0 : stamp.historyEpoch(),
                target==null ? 0 : target.allocationId(),target==null ? 0 : target.allocationIdentity().generation(),
                separated,overflow,Map.copyOf(PROGRAMS),false,false,false);
    }
    private MetalUiDrawCoverage() {}
}
