package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.pipeline.*;
import net.minecraft.client.renderer.RenderPipelines;

import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** One bounded matrix of source inputs; sampled contribution is decided by original GPU color. */
final class MetalParticlePartialAlphaCases {
    enum Groups { SINGLE, MERGE_AB, MERGE_BA, SPLIT_AB, SPLIT_BA }
    record Case(String label, boolean opaque, int oldMin, int oldMax, float oldModulator,
                int currentAlpha, float currentModulator, int atlasAlpha, Groups groups, boolean recovery) {
        String identity() {
            return (opaque ? "opaque_" : "translucent_") + label + "_old_" + oldMin + "_" + oldMax + "_"
                    + Integer.toHexString(Float.floatToRawIntBits(oldModulator)) + "_current_" + currentAlpha + "_"
                    + Integer.toHexString(Float.floatToRawIntBits(currentModulator)) + "_atlas_" + atlasAlpha + "_"
                    + groups.name().toLowerCase(Locale.ROOT) + "_recover_" + recovery;
        }
        boolean paired() { return groups != Groups.SINGLE; }
        boolean rejectPartialInputs() {return oldMin < 255 || oldMax < 255 || oldModulator != 1 || currentModulator != 1;}
        List<List<Long>> ids(int frame) {
            if (!paired()) return List.of(List.of(11L));
            boolean split = frame == 0 ? groups == Groups.MERGE_AB || groups == Groups.MERGE_BA
                    : groups == Groups.SPLIT_AB || groups == Groups.SPLIT_BA;
            var order = frame > 0 && (groups == Groups.MERGE_BA || groups == Groups.SPLIT_BA) ? List.of(22L,11L) : List.of(11L,22L);
            return split ? List.of(List.of(order.get(0)),List.of(order.get(1))) : List.of(order);
        }
        Map<String,Object> metadata() {
            return Map.ofEntries(Map.entry("identity",identity()),Map.entry("label",label),Map.entry("opaque",opaque),
                    Map.entry("oldMin",oldMin),Map.entry("oldMax",oldMax),Map.entry("oldModulatorBits",Integer.toHexString(Float.floatToRawIntBits(oldModulator))),
                    Map.entry("currentAlpha",currentAlpha),Map.entry("currentModulatorBits",Integer.toHexString(Float.floatToRawIntBits(currentModulator))),
                    Map.entry("atlasAlpha",atlasAlpha),Map.entry("groups",groups.name()),Map.entry("recovery",recovery));
        }
        @Override public String toString() {return identity();}
    }
    static List<Case> all() {
        var c = new ArrayList<Case>();
        c.add(single("vertex_25",false,25,25,1,255,1,255,true));
        c.add(single("vertex_26",false,26,26,1,255,1,255,true));
        c.add(single("modulator_below_cutoff",false,255,255,Math.nextDown(.1F),255,1,255,true));
        c.add(single("modulator_at_cutoff",false,255,255,.1F,255,1,255,true));
        c.add(single("modulator_above_cutoff",false,255,255,Math.nextUp(.1F),255,1,255,true));
        c.add(single("vertex128_modulator019",false,128,128,.19F,255,1,255,true));
        c.add(single("atlas38_vertex128",false,128,128,1,255,1,38,false));
        c.add(single("visible_partial128",false,128,128,1,255,1,255,true));
        c.add(single("gradient25_26",false,25,26,1,255,1,255,true));
        c.add(single("gradient25_255",false,25,255,1,255,1,255,true));
        c.add(single("current_below_cutoff",false,255,255,1,25,1,255,false));
        c.add(single("current_above_cutoff",false,255,255,1,26,1,255,false));
        c.add(single("current_modulator_changed",false,255,255,1,255,.1F,255,false));
        c.add(single("full_source_inputs",false,255,255,1,255,1,255,false));
        for (Groups group : List.of(Groups.MERGE_AB,Groups.MERGE_BA,Groups.SPLIT_AB,Groups.SPLIT_BA))
            c.add(new Case("cross_batch25",false,25,25,1,255,1,255,group,true));
        c.add(single("vertex_25",true,25,25,1,255,1,255,true));
        c.add(single("vertex_26",true,26,26,1,255,1,255,true));
        c.add(single("modulator_below_cutoff",true,255,255,Math.nextDown(.1F),255,1,255,true));
        c.add(single("modulator_at_cutoff",true,255,255,.1F,255,1,255,true));
        c.add(single("atlas38_vertex128",true,128,128,1,255,1,38,false));
        c.add(single("full_source_inputs",true,255,255,1,255,1,255,false));
        return List.copyOf(c);
    }
    private static Case single(String label,boolean opaque,int lo,int hi,float old,int current,float now,int atlas,boolean recover) {
        return new Case(label,opaque,lo,hi,old,current,now,atlas,Groups.SINGLE,recover);
    }
    static ByteBuffer mesh(Case c,List<Long> ids,int frame) {
        ByteBuffer mesh = MetalParticleAlphaFixtures.mesh(ids,c.paired(),frame == 0 ? c.oldMin : c.currentAlpha);
        if (frame == 0) for (int q=0;q<ids.size();q++) if (ids.get(q)==11L) {
            int stride=com.mojang.blaze3d.vertex.DefaultVertexFormat.PARTICLE.getVertexSize();
            int alpha=com.mojang.blaze3d.vertex.DefaultVertexFormat.PARTICLE.getElement("Color").offset()+3;
            for(int v:new int[]{1,2}) mesh.put((q*4+v)*stride+alpha,(byte)c.oldMax);
        }
        return mesh;
    }
    /** Uses the actual pinned client's ordinary pipeline; no OIT or reconstructed descriptor. */
    static RenderPipeline pipeline(boolean opaque) {
        try {
            for(var field:RenderPipelines.class.getDeclaredFields()) {
                if(!Modifier.isStatic(field.getModifiers()) || field.getType()!=RenderPipeline.class) continue;
                field.setAccessible(true); var p=(RenderPipeline)field.get(null);
                if(p==null || p.getLocation().getPath().contains("oit") || p.getColorTargetStates().size()!=1
                        || !p.getShaders().get(ShaderType.VERTEX).getPath().equals("core/particle")) continue;
                var blend=p.getColorTargetStates().getFirst().blendFunction();
                if(opaque ? blend.isPresent() : !blend.equals(java.util.Optional.of(BlendFunction.TRANSLUCENT))) continue;
                assertEquals(PrimitiveTopology.QUADS,p.getPrimitiveTopology());
                var depth=p.getDepthStencilState();assertNotNull(depth);
                assertEquals(CompareOp.GREATER_THAN_OR_EQUAL,depth.depthTest());assertTrue(depth.writeDepth());
                assertEquals(0,depth.depthBiasScaleFactor());assertEquals(0,depth.depthBiasConstant());
                return p;
            }
        } catch(ReflectiveOperationException e) {throw new AssertionError(e);}
        throw new AssertionError("Missing canonical ordinary particle pipeline, opaque="+opaque);
    }
    private MetalParticlePartialAlphaCases() {}
}
