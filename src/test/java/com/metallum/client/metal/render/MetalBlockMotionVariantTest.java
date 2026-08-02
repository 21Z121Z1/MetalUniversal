package com.metallum.client.metal.render;

import java.util.Optional;

import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds motion variants from synthetic sources shaped like Minecraft's block and
 * entity pipelines.
 *
 * <p>Synthetic rather than the real {@code RenderPipelines} constants, so the test
 * does not depend on that class's static initialiser running outside a game. What
 * it exercises is this mod's own {@code build()}: which shader a family selects,
 * that the source's defines, topology, cull and vertex bindings are carried over,
 * that depth write is turned off while the depth test is kept, and that the two
 * motion targets are attached. Those are the parts that had no coverage at all —
 * before this, a block variant that failed to build would first be noticed by a
 * frame that quietly produced no motion.</p>
 */
final class MetalBlockMotionVariantTest {
    private static final ColorTargetState OPAQUE_TARGET =
            new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_COLOR);
    private static final ColorTargetState TRANSLUCENT_TARGET =
            new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT), GpuFormat.RGBA8_UNORM,
                    ColorTargetState.WRITE_COLOR);

    @AfterEach
    void clearVariantCache() {
        // The builder caches by source identity; leaving entries behind would let one
        // test observe another's variant.
        MetalEntityMotionPipeline.clear();
    }

    private static RenderPipeline.Builder source(final String name, final String vertexShader) {
        return RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("minecraft", "pipeline/" + name))
                .withVertexShader(vertexShader)
                .withFragmentShader(vertexShader)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .withColorTargetState(OPAQUE_TARGET);
    }

    private static RenderPipeline solidBlock() {
        return source("solid_block", "core/block")
                .withVertexBinding(0, DefaultVertexFormat.BLOCK)
                .build();
    }

    private static RenderPipeline cutoutBlock() {
        return source("cutout_block", "core/block")
                .withVertexBinding(0, DefaultVertexFormat.BLOCK)
                .withShaderDefine("ALPHA_CUTOUT", 0.5F)
                .build();
    }

    @Test
    void aBlockSourceIsClaimedByTheBlockFamily() {
        assertSame(MetalEntityMotionPipeline.Family.BLOCK,
                MetalEntityMotionPipeline.familyOf(solidBlock()));
        assertSame(MetalEntityMotionPipeline.Family.ENTITY,
                MetalEntityMotionPipeline.familyOf(source("solid_entity", "core/entity")
                        .withVertexBinding(0, DefaultVertexFormat.ENTITY)
                        .build()));
        assertEquals(null, MetalEntityMotionPipeline.familyOf(source("terrain", "core/terrain")
                        .withVertexBinding(0, DefaultVertexFormat.BLOCK)
                        .build()),
                "core/terrain adds a chunk offset this backend does not reconstruct, so no family may"
                        + " claim it");
    }

    @Test
    void aBlockVariantUsesTheBlockShaderAndItsOwnLocationPrefix() {
        RenderPipeline variant = MetalEntityMotionPipeline.forSource(solidBlock());

        assertEquals(MetalEntityMotionPipeline.Family.BLOCK.shader().toString(),
                variant.getVertexShader().toString(), "block variants must replay with the block shader");
        assertEquals(MetalEntityMotionPipeline.Family.BLOCK.shader().toString(),
                variant.getFragmentShader().toString());
        assertTrue(variant.getLocation().getPath()
                        .startsWith(MetalEntityMotionPipeline.Family.BLOCK.locationPrefix()),
                "variant location was " + variant.getLocation());
        assertEquals("metallum", variant.getLocation().getNamespace());
    }

    @Test
    void anEntityVariantStillUsesTheEntityShader() {
        RenderPipeline variant = MetalEntityMotionPipeline.forSource(
                source("solid_entity", "core/entity").withVertexBinding(0, DefaultVertexFormat.ENTITY).build());

        assertEquals(MetalEntityMotionPipeline.Family.ENTITY.shader().toString(),
                variant.getVertexShader().toString(),
                "adding the block family must not have moved the entity family's shader");
    }

    @Test
    void theVariantWritesMotionAndValidityAndNoDepth() {
        RenderPipeline variant = MetalEntityMotionPipeline.forSource(solidBlock());

        assertEquals(GpuFormat.RG16_FLOAT, variant.getColorTargetStates()[0].format(),
                "slot 0 carries the motion vector");
        assertEquals(GpuFormat.R8_UNORM, variant.getColorTargetStates()[1].format(),
                "slot 1 carries per-pixel validity");
        assertTrue(variant.getColorTargetStates()[0].blendFunction().isEmpty(),
                "a motion vector must never be blended");

        DepthStencilState depth = variant.getDepthStencilState();
        assertNotNull(depth);
        assertFalse(depth.writeDepth(),
                "the motion pass replays geometry the color pass already depth-tested; writing depth again"
                        + " would let the replay change what the scene sees");
        assertEquals(DepthStencilState.DEFAULT.depthTest(), depth.depthTest(),
                "the depth test itself must match the source, or the replay covers different pixels");
    }

    @Test
    void alphaCutoutDefinesAreCarriedIntoTheVariant() {
        RenderPipeline variant = MetalEntityMotionPipeline.forSource(cutoutBlock());

        assertEquals("0.5", variant.getShaderDefines().values().get("ALPHA_CUTOUT"),
                "the replay discards the same fragments as the color pass only if it gets the same"
                        + " threshold; without it a cutout block's motion covers its whole quad");
    }

    @Test
    void theSourceVertexBindingIsCarriedIntoTheVariant() {
        RenderPipeline variant = MetalEntityMotionPipeline.forSource(solidBlock());

        assertSame(DefaultVertexFormat.BLOCK, variant.getVertexFormatBinding(0),
                "the variant reads the same buffer the color pass filled, so it must declare the same"
                        + " format");
    }

    @Test
    void translucentSourcesAreNotSupported() {
        RenderPipeline translucentBlock = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("minecraft", "pipeline/translucent_block"))
                .withVertexShader("core/block")
                .withFragmentShader("core/block")
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .withColorTargetState(TRANSLUCENT_TARGET)
                .withVertexBinding(0, DefaultVertexFormat.BLOCK)
                .build();

        assertSame(MetalEntityMotionPipeline.Family.BLOCK,
                MetalEntityMotionPipeline.familyOf(translucentBlock),
                "the family still claims it; support is a separate question");
        assertFalse(MetalEntityMotionPipeline.supports(translucentBlock),
                "a blended source has no single owning surface per pixel, so replaying it would write"
                        + " whichever fragment happened to land last");
    }

    @Test
    void variantsAreCachedPerSourceAndClearedTogether() {
        RenderPipeline first = solidBlock();
        assertSame(MetalEntityMotionPipeline.forSource(first), MetalEntityMotionPipeline.forSource(first),
                "one source must not rebuild a pipeline every frame");

        RenderPipeline firstVariant = MetalEntityMotionPipeline.forSource(first);
        MetalEntityMotionPipeline.clear();
        assertNotSame(firstVariant, MetalEntityMotionPipeline.forSource(first),
                "clear() must drop variants so a resource reload rebuilds them");
    }

    @Test
    void depthTestOnlySourcesKeepTheirOwnDepthFunction() {
        RenderPipeline lessEqualSource = source("depth_variant", "core/block")
                .withVertexBinding(0, DefaultVertexFormat.BLOCK)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN, true, 0.0F, 0.0F))
                .build();

        DepthStencilState depth = MetalEntityMotionPipeline.forSource(lessEqualSource).getDepthStencilState();
        assertEquals(CompareOp.LESS_THAN, depth.depthTest());
        assertFalse(depth.writeDepth());
    }
}
