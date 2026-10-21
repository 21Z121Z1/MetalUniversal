package com.metallum.client.metal.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

final class MetalParticlePartialAlphaHistoryTest {
    static List<MetalParticlePartialAlphaCases.Case> cases() {return MetalParticlePartialAlphaCases.all();}
    private final MetalEntityMotionCapture.Sample owner=new MetalEntityMotionCapture.Sample(72912,32,new Matrix4f(),new Matrix4f(),FrameSynthesisContract.ProducerDomain.PARTICLES_WEATHER);
    @AfterEach void close() {MetalPreviousVertexHistory.discardFrame();MetalPreviousVertexHistory.reset();}

    @Test void allParametersHaveDistinctArtifactIdentitiesAndOrdinaryPipelineSemantics() {
        assertEquals(24,cases().size());assertEquals(24,cases().stream().map(MetalParticlePartialAlphaCases.Case::identity).distinct().count());
        for(boolean opaque:List.of(false,true)) {
            var p=MetalParticlePartialAlphaCases.pipeline(opaque);
            System.out.println("CANONICAL_PARTICLE_PIPELINE "+p.getLocation()+" depth="+p.getDepthStencilState()+" color="+p.getColorTargetStates());
        }
    }
    @ParameterizedTest @MethodSource("cases")
    void sourceInputsRemainTransactionalAcrossPartialHistoryAndGroupChanges(MetalParticlePartialAlphaCases.Case c) {
        MetalPreviousVertexHistory.beginFrame();stage(c,0);MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();var first=stage(c,1);boolean reject=allRejected(c,first);
        MetalPreviousVertexHistory.discardFrame();
        MetalPreviousVertexHistory.beginFrame();var after=stage(c,1);boolean discardDidNotAdvance=allRejected(c,after);
        MetalPreviousVertexHistory.commitSubmittedFrame();
        if(c.recovery()) {
            MetalPreviousVertexHistory.beginFrame();var next=stage(c,1);
            for(var t:next)assertNotNull(MetalPreviousVertexReplay.previousPositionsForSource(MetalParticlePartialAlphaCases.pipeline(c.opaque()),t));
        }
        assertEquals(c.rejectPartialInputs(),reject,"Partial inputs are not a previous-sample proof: "+c);
        assertEquals(c.rejectPartialInputs(),discardDidNotAdvance,"Discard must retain accepted old input evidence: "+c);
    }
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> modulators() {
        var out=new java.util.ArrayList<org.junit.jupiter.params.provider.Arguments>();
        for(float value:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,-1,0,.5F,1.01F,2})
            for(boolean old:new boolean[]{false,true})out.add(org.junit.jupiter.params.provider.Arguments.of(old,value));
        return out.stream();
    }
    @ParameterizedTest @MethodSource("modulators")
    void unknownNonfiniteAndOutsideUnitModulatorsAreRejectedWithoutClamping(boolean old,float value) {
                MetalPreviousVertexHistory.reset();MetalPreviousVertexHistory.beginFrame();var c=cases().get(13);
                var before=stage(c,0).getFirst();if(old)MetalPreviousVertexHistory.stageSourceUniforms(before,MetalSourceUniforms.decode(MetalPreviousVisibilityHistoryTest.dynamic(value),null));
                MetalPreviousVertexHistory.commitSubmittedFrame();MetalPreviousVertexHistory.beginFrame();var current=stage(c,1).getFirst();
                if(!old)MetalPreviousVertexHistory.stageSourceUniforms(current,MetalSourceUniforms.decode(MetalPreviousVisibilityHistoryTest.dynamic(value),null));
                assertNull(MetalPreviousVertexReplay.previousPositionsForSource(MetalParticlePartialAlphaCases.pipeline(false),current),"old="+old+", alpha="+value);
                assertNotNull(MetalPreviousVertexReplay.currentOnlyTransforms(current,false));MetalPreviousVertexHistory.discardFrame();
    }
    @Test void unsignedBoundsAreExactAcrossTheWholeByteRange() {
        for(int value:new int[]{0,25,26,128,255}) {
            MetalPreviousVertexHistory.reset();MetalPreviousVertexHistory.beginFrame();
            var c=new MetalParticlePartialAlphaCases.Case("bounds",false,value,value,1,255,1,255,MetalParticlePartialAlphaCases.Groups.SINGLE,false);
            stage(c,0);MetalPreviousVertexHistory.commitSubmittedFrame();MetalPreviousVertexHistory.beginFrame();
            var current=stage(c,1).getFirst();var alpha=MetalPreviousVertexHistory.matchedPreviousParticleAlpha(current);assertNotNull(alpha);
            assertEquals(value,alpha.min());assertEquals(value,alpha.max());assertTrue(alpha.unitModulator());
            assertEquals(value==255,alpha.fullSourceInputs());MetalPreviousVertexHistory.discardFrame();
        }
    }
    @Test void ordinaryPartialGuardPreservesTheExistingOitZeroInputPolicy() throws ReflectiveOperationException {
        com.mojang.renderpearl.api.pipeline.RenderPipeline oit=null;
        for(var field:net.minecraft.client.renderer.RenderPipelines.class.getDeclaredFields()) {
            if(!java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType()!=com.mojang.renderpearl.api.pipeline.RenderPipeline.class)continue;
            field.setAccessible(true);var pipeline=(com.mojang.renderpearl.api.pipeline.RenderPipeline)field.get(null);
            if(pipeline!=null && pipeline.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX).getPath().equals("core/particle")
                    && pipeline.getShaderDefines().flags().contains("OIT_ACCUMULATE")) {oit=pipeline;break;}
        }
        assertNotNull(oit,() -> "The pinned client's actual OIT particle pipeline must exist; OitStage API="
                +java.util.Arrays.toString(net.minecraft.client.renderer.oit.OitStage.class.getDeclaredMethods())
                +"; RenderPipelines methods="+java.util.Arrays.toString(net.minecraft.client.renderer.RenderPipelines.class.getDeclaredMethods())
                +"; pipeline OIT methods="+java.util.Arrays.stream(com.mojang.renderpearl.api.pipeline.RenderPipeline.class.getMethods())
                    .filter(method->method.toString().toLowerCase(java.util.Locale.ROOT).contains("oit")).toList());
        assertEquals("minecraft:pipeline/oit_accumulate_particle",oit.getLocation().toString());
        for(int oldAlpha:new int[]{0,26,255})for(float oldModulator:new float[]{0,.1F,1}) {
            MetalPreviousVertexHistory.reset();MetalPreviousVertexHistory.beginFrame();
            var c=new MetalParticlePartialAlphaCases.Case("oit_unchanged",false,oldAlpha,oldAlpha,oldModulator,255,1,255,MetalParticlePartialAlphaCases.Groups.SINGLE,false);
            var old=MetalPreviousVertexHistory.reserveDraw(owner,oit);capture(old,oit,List.of(11L),MetalParticlePartialAlphaCases.mesh(c,List.of(11L),0),oldModulator);
            MetalPreviousVertexHistory.commitSubmittedFrame();MetalPreviousVertexHistory.beginFrame();
            var current=MetalPreviousVertexHistory.reserveDraw(owner,oit);capture(current,oit,List.of(11L),MetalParticlePartialAlphaCases.mesh(c,List.of(11L),1),1);
            var positions=MetalPreviousVertexReplay.previousPositionsForSource(oit,current);
            if(oldAlpha==0 || oldModulator==0)assertNull(positions);else assertNotNull(positions);
            MetalPreviousVertexHistory.discardFrame();
        }
        var unknown=com.mojang.renderpearl.api.pipeline.RenderPipeline.builder().withLocation("test/unknown_oit_particle")
                .withVertexShader("core/particle").withFragmentShader("core/particle").withShaderDefine("OIT_ACCUMULATE")
                .withVertexBinding(0,DefaultVertexFormat.PARTICLE).withPrimitiveTopology(PrimitiveTopology.QUADS).build();
        MetalPreviousVertexHistory.reset();MetalPreviousVertexHistory.beginFrame();
        var c=new MetalParticlePartialAlphaCases.Case("unknown_oit",false,26,26,1,255,1,255,MetalParticlePartialAlphaCases.Groups.SINGLE,false);
        var old=MetalPreviousVertexHistory.reserveDraw(owner,unknown);capture(old,unknown,List.of(11L),MetalParticlePartialAlphaCases.mesh(c,List.of(11L),0),1);
        MetalPreviousVertexHistory.commitSubmittedFrame();MetalPreviousVertexHistory.beginFrame();
        var current=MetalPreviousVertexHistory.reserveDraw(owner,unknown);capture(current,unknown,List.of(11L),MetalParticlePartialAlphaCases.mesh(c,List.of(11L),1),1);
        assertNull(MetalPreviousVertexReplay.previousPositionsForSource(unknown,current),"An OIT flag on an unknown source cannot bypass the ordinary partial-input rejection");
    }
    @Test void aPartialVertexOnlyInTheSecondFreedSliceKeepsItsBoundsAndAcceptedUniformWitness() {
        var c=cases().get(13);var p=MetalParticlePartialAlphaCases.pipeline(false);var format=DefaultVertexFormat.PARTICLE;
        MetalPreviousVertexHistory.beginFrame();var old=MetalPreviousVertexHistory.reserveDraw(owner,p);
        var mesh=MetalParticlePartialAlphaCases.mesh(c,List.of(11L),0);
        mesh.put(2*format.getVertexSize()+format.getElement("Color").offset()+3,(byte)25);
        capture(old,p,List.of(11L),mesh,.19F);MetalPreviousVertexHistory.commitSubmittedFrame();
        MetalPreviousVertexHistory.beginFrame();var current=stage(c,1).getFirst();
        var alpha=MetalPreviousVertexHistory.matchedPreviousParticleAlpha(current);assertNotNull(alpha);
        assertEquals(25,alpha.min());assertEquals(255,alpha.max());assertFalse(alpha.unitModulator());
        assertNull(MetalPreviousVertexReplay.previousPositionsForSource(p,current));MetalPreviousVertexHistory.discardFrame();
        MetalPreviousVertexHistory.beginFrame();current=stage(c,1).getFirst();
        assertEquals(alpha,MetalPreviousVertexHistory.matchedPreviousParticleAlpha(current));
        MetalPreviousVertexHistory.commitSubmittedFrame();MetalPreviousVertexHistory.beginFrame();current=stage(c,1).getFirst();
        assertEquals(new MetalPreviousVertexHistory.ParticleAlpha(255,255,true),MetalPreviousVertexHistory.matchedPreviousParticleAlpha(current));
        assertNotNull(MetalPreviousVertexReplay.previousPositionsForSource(p,current));
    }
    private boolean allRejected(MetalParticlePartialAlphaCases.Case c,List<MetalPreviousVertexHistory.DrawToken> tokens) {
        boolean all=true;
        for(var t:tokens) {
            assertNotNull(MetalPreviousVertexHistory.matchedPreviousPositions(t));
            all &= MetalPreviousVertexReplay.previousPositionsForSource(MetalParticlePartialAlphaCases.pipeline(c.opaque()),t)==null;
            assertNotNull(MetalPreviousVertexReplay.currentOnlyTransforms(t,false));
        }
        return all;
    }
    private List<MetalPreviousVertexHistory.DrawToken> stage(MetalParticlePartialAlphaCases.Case c,int frame) {
        var p=MetalParticlePartialAlphaCases.pipeline(c.opaque());var result=new java.util.ArrayList<MetalPreviousVertexHistory.DrawToken>();
        for(var ids:c.ids(frame)) {
            var token=MetalPreviousVertexHistory.reserveDraw(owner,p);result.add(token);
            capture(token,p,ids,MetalParticlePartialAlphaCases.mesh(c,ids,frame),frame==0?c.oldModulator():c.currentModulator());
        }
        return List.copyOf(result);
    }
    private void capture(MetalPreviousVertexHistory.DrawToken token,com.mojang.renderpearl.api.pipeline.RenderPipeline p,List<Long> ids,java.nio.ByteBuffer mesh,float modulator) {
        MetalParticleAlphaFixtures.capture(token,DefaultVertexFormat.PARTICLE,PrimitiveTopology.QUADS,mesh,ids.size()*4,ids.size()*6);
        MetalPreviousVertexHistory.stageParticleIdentities(token,ids);
        MetalPreviousVertexHistory.stageDrawTransform(token,new MetalPreviousVertexHistory.DrawTransform(new Matrix4f(),new Matrix4f()));
        MetalPreviousVertexHistory.stageSourceUniforms(token,MetalSourceUniforms.decode(MetalPreviousVisibilityHistoryTest.dynamic(modulator),null));
        MetalPreviousVertexHistory.stageSurface(token,new MetalPreviousVertexHistory.SurfaceIdentity(p,Map.of(),true));
    }
}
