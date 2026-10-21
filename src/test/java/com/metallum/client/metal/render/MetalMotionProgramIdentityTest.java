package com.metallum.client.metal.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class MetalMotionProgramIdentityTest {
    private static RenderPipeline.Builder base() {
        return RenderPipeline.builder().withLocation("test/shared_source")
                .withVertexShader("core/entity").withFragmentShader("core/entity")
                .withPrimitiveTopology(com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLES)
                .withVertexBinding(0, DefaultVertexFormat.ENTITY).withColorTargetState(ColorTargetState.DEFAULT);
    }

    @Test
    void sameLocationDoesNotAliasDiscardGlintLayoutOrCompositingVariants() {
        var ordinary = MetalMotionProgramIdentity.of(base().build());
        var cutout = MetalMotionProgramIdentity.of(base().withShaderDefine("ALPHA_CUTOUT", .1F).build());
        var dissolve = MetalMotionProgramIdentity.of(base().withShaderDefine("DISSOLVE").build());
        var glint = MetalMotionProgramIdentity.of(base().withShaderDefine("GLINT")
                .withShaderDefine("GLINT_SPECIAL").withVertexBinding(0, DefaultVertexFormat.ENTITY_GLINT_SPECIAL).build());
        var blended = MetalMotionProgramIdentity.of(base().withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE)).build());
        assertEquals(5, java.util.Set.of(ordinary.key(), cutout.key(), dissolve.key(), glint.key(), blended.key()).size());
        assertEquals("Position", glint.source().vertexBindings().getFirst().elements().getFirst().semantic());
        assertTrue(glint.source().vertexBindings().getFirst().elements().stream().anyMatch(e -> e.semantic().equals("UV3")));
    }

    @Test
    void declarationOrderDoesNotSplitEquivalentPrograms() {
        var a = MetalMotionProgramIdentity.of(base().withShaderDefine("EMISSIVE").withShaderDefine("NO_OVERLAY")
                .withShaderDefine("ALPHA_CUTOUT", .1F).withShaderDefine("TEST_VALUE", 2).build());
        var b = MetalMotionProgramIdentity.of(base().withShaderDefine("TEST_VALUE", 2).withShaderDefine("ALPHA_CUTOUT", .1F)
                .withShaderDefine("NO_OVERLAY").withShaderDefine("EMISSIVE").build());
        assertEquals(a, b);
    }
}
