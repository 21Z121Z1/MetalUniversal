package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.Identifier;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Sodium CUTOUT terrain pipeline with an exact post-alpha-test coverage output.
 *
 * <p>The second color target is only selected while the MetalFX manager has a
 * matching R8 attachment. Solid and translucent terrain keep Sodium's original
 * pipeline. The fragment shader duplicates Sodium's current block sampling and
 * discard contract, then writes coverage only for fragments that survived the
 * same alpha test as scene color.</p>
 */
@Environment(EnvType.CLIENT)
public final class MetalCutoutReactivePipeline {
    private static final Identifier SHADER =
            Identifier.fromNamespaceAndPath("metallum", "blocks/block_layer_cutout_reactive");
    private static final ColorTargetState COVERAGE_TARGET =
            new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, ColorTargetState.WRITE_RED);
    private static final Map<VertexFormat, RenderPipeline> CACHE = new IdentityHashMap<>();
    // Launch-arg escape hatch: -Dmetallum.metalfx.stableCutoutAlpha=false
    // restores the exact pre-remediation sampling for A/B comparisons.
    private static final boolean STABLE_ALPHA =
            !"false".equalsIgnoreCase(System.getProperty("metallum.metalfx.stableCutoutAlpha", "true"));
    private static final ThreadLocal<Boolean> ACTIVE_CUTOUT_PASS =
            ThreadLocal.withInitial(() -> false);

    private MetalCutoutReactivePipeline() {
    }

    public static void beginTerrainPass(final TerrainRenderPass pass) {
        ACTIVE_CUTOUT_PASS.set(
                pass != null
                        && pass.supportsFragmentDiscard()
                        && !pass.isTranslucent()
                        && MetalFxManager.usesCutoutReactiveTerrain()
        );
    }

    public static void endTerrainPass() {
        ACTIVE_CUTOUT_PASS.remove();
    }

    public static boolean isActiveCutoutPass() {
        return ACTIVE_CUTOUT_PASS.get();
    }

    /**
     * Whether MetalFX may replace Sodium's CUTOUT pipeline for this pass.
     *
     * <p>An active Iris generation owns the terrain shader and every declared
     * DRAWBUFFERS attachment. Appending MetalFX's independent R8 target would
     * alias an Iris colortex/shadowcolor slot, so the combined path keeps the
     * Iris program and derives reactivity from transparency and depth instead.</p>
     */
    public static boolean shouldSubstitute(final boolean irisSemanticActive) {
        return shouldSubstitute(isActiveCutoutPass(), irisSemanticActive);
    }

    static boolean shouldSubstitute(
            final boolean activeCutoutPass,
            final boolean irisSemanticActive
    ) {
        return activeCutoutPass && !irisSemanticActive;
    }

    public static RenderPipeline forVertexFormat(final VertexFormat vertexFormat) {
        return CACHE.computeIfAbsent(vertexFormat, MetalCutoutReactivePipeline::build);
    }

    public static void clear() {
        CACHE.clear();
        ACTIVE_CUTOUT_PASS.remove();
    }

    private static RenderPipeline build(final VertexFormat vertexFormat) {
        var builder = RenderPipeline.builder()
                .withBindGroupLayout(ShaderChunkRenderer.BIND_GROUP)
                .withLocation(Identifier.fromNamespaceAndPath(
                        "metallum",
                        "pipeline/terrain_cutout_reactive"
                ))
                .withCull(true)
                .withVertexShader(Identifier.fromNamespaceAndPath(
                        "sodium",
                        "blocks/block_layer_opaque"
                ))
                .withFragmentShader(SHADER)
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withVertexBinding(0, vertexFormat)
                .withColorTargetState(0, ColorTargetState.DEFAULT)
                .withColorTargetState(1, COVERAGE_TARGET)
                .withShaderDefine("USE_VERTEX_COMPRESSION")
                .withShaderDefine("USE_FOG")
                .withShaderDefine("ALPHA_CUTOUT", 0.5F);
        if (STABLE_ALPHA) {
            builder = builder.withShaderDefine("METALLUM_STABLE_ALPHA");
        }
        return builder.build();
    }

}
