package com.metallum.client.metal.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.pathways.HandRenderer;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.renderer.RenderPipelines;
import org.jspecify.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Resolves Mojang world pipelines to the Iris program selected for the current draw state. */
@Environment(EnvType.CLIENT)
public final class IrisMetalCoreGbufferPipelines {
    private static final Map<RenderPipeline, Resolver> MAIN = new IdentityHashMap<>();
    private static final Map<RenderPipeline, Resolver> SHADOW = new IdentityHashMap<>();

    static {
        main(RenderPipelines.SOLID_BLOCK, ShaderKey.TERRAIN_SOLID);
        main(RenderPipelines.CUTOUT_BLOCK, ShaderKey.TERRAIN_CUTOUT);
        main(RenderPipelines.SOLID_TERRAIN, ShaderKey.TERRAIN_SOLID);
        main(RenderPipelines.CUTOUT_TERRAIN, ShaderKey.TERRAIN_CUTOUT);
        main(RenderPipelines.TRANSLUCENT_TERRAIN, ShaderKey.TERRAIN_TRANSLUCENT);
        main(RenderPipelines.TRANSLUCENT_BLOCK, ShaderKey.MOVING_BLOCK);
        main(RenderPipelines.WORLD_BORDER, ShaderKey.TEXTURED);
        main(RenderPipelines.ENTITY_CUTOUT, IrisMetalCoreGbufferPipelines::cutout);
        main(RenderPipelines.ENTITY_CUTOUT_CULL, IrisMetalCoreGbufferPipelines::cutout);
        main(RenderPipelines.ENTITY_CUTOUT_DISSOLVE, IrisMetalCoreGbufferPipelines::cutout);
        main(RenderPipelines.ENTITY_TRANSLUCENT_CULL, IrisMetalCoreGbufferPipelines::translucent);
        main(RenderPipelines.ITEM_TRANSLUCENT, IrisMetalCoreGbufferPipelines::translucent);
        main(RenderPipelines.ITEM_CUTOUT, IrisMetalCoreGbufferPipelines::cutout);
        main(RenderPipelines.ENTITY_TRANSLUCENT, IrisMetalCoreGbufferPipelines::translucent);
        main(RenderPipelines.ENTITY_SHADOW, IrisMetalCoreGbufferPipelines::translucent);
        main(RenderPipelines.LINES, ShaderKey.LINES);
        main(RenderPipelines.LINES_TRANSLUCENT, ShaderKey.LINES);
        main(RenderPipelines.SECONDARY_BLOCK_OUTLINE, ShaderKey.LINES);
        main(RenderPipelines.STARS, ShaderKey.SKY_BASIC);
        main(RenderPipelines.SUNRISE_SUNSET, ShaderKey.SKY_BASIC_COLOR);
        main(RenderPipelines.SKY, ShaderKey.SKY_BASIC);
        main(RenderPipelines.CELESTIAL, ShaderKey.SKY_TEXTURED);
        main(RenderPipelines.OPAQUE_PARTICLE, ShaderKey.PARTICLES);
        main(RenderPipelines.TRANSLUCENT_PARTICLE, ShaderKey.PARTICLES_TRANS);
        main(RenderPipelines.WATER_MASK, ShaderKey.BASIC);
        main(RenderPipelines.GLINT, ShaderKey.GLINT);
        main(RenderPipelines.ARMOR_CUTOUT_NO_CULL, IrisMetalCoreGbufferPipelines::cutout);
        main(RenderPipelines.EYES, ShaderKey.ENTITIES_EYES);
        main(RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE, ShaderKey.ENTITIES_EYES_TRANS);
        main(RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL, IrisMetalCoreGbufferPipelines::cutout);
        main(RenderPipelines.ARMOR_TRANSLUCENT, IrisMetalCoreGbufferPipelines::translucent);
        main(RenderPipelines.BREEZE_WIND, IrisMetalCoreGbufferPipelines::translucent);
        main(RenderPipelines.ENTITY_SOLID, IrisMetalCoreGbufferPipelines::solid);
        main(RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD, IrisMetalCoreGbufferPipelines::solid);
        main(RenderPipelines.END_GATEWAY, ShaderKey.BLOCK_ENTITY);
        main(RenderPipelines.ENERGY_SWIRL, ShaderKey.ENTITIES_CUTOUT);
        main(RenderPipelines.END_CRYSTAL_BEAM, ShaderKey.ENTITIES_CUTOUT);
        main(RenderPipelines.ENTITY_CUTOUT_Z_OFFSET, ShaderKey.ENTITIES_CUTOUT);
        main(RenderPipelines.LIGHTNING, ShaderKey.LIGHTNING);
        main(RenderPipelines.DRAGON_RAYS, ShaderKey.LIGHTNING);
        main(RenderPipelines.BEACON_BEAM_OPAQUE, ShaderKey.BEACON);
        main(RenderPipelines.BEACON_BEAM_TRANSLUCENT, ShaderKey.BEACON);
        main(RenderPipelines.END_PORTAL, ShaderKey.BLOCK_ENTITY);
        main(RenderPipelines.END_SKY, ShaderKey.SKY_TEXTURED);
        main(RenderPipelines.WEATHER_DEPTH_WRITE, ShaderKey.WEATHER);
        main(RenderPipelines.WEATHER_NO_DEPTH_WRITE, ShaderKey.WEATHER);
        main(RenderPipelines.TEXT, IrisMetalCoreGbufferPipelines::text);
        main(RenderPipelines.TEXT_POLYGON_OFFSET, IrisMetalCoreGbufferPipelines::text);
        main(RenderPipelines.TEXT_SEE_THROUGH, IrisMetalCoreGbufferPipelines::text);
        main(RenderPipelines.TEXT_GRAYSCALE_SEE_THROUGH, IrisMetalCoreGbufferPipelines::intensityText);
        main(RenderPipelines.TEXT_BACKGROUND, ShaderKey.TEXT_BG);
        main(RenderPipelines.TEXT_BACKGROUND_SEE_THROUGH, ShaderKey.TEXT_BG);
        main(RenderPipelines.TEXT_GRAYSCALE, IrisMetalCoreGbufferPipelines::intensityText);
        main(RenderPipelines.CRUMBLING, ShaderKey.CRUMBLING);
        main(RenderPipelines.LEASH, ShaderKey.LEASH);
        main(RenderPipelines.CLOUDS, ShaderKey.CLOUDS);
        main(RenderPipelines.FLAT_CLOUDS, ShaderKey.CLOUDS);
        main(RenderPipelines.BANNER_PATTERN, IrisMetalCoreGbufferPipelines::translucent);

        shadow(RenderPipelines.SOLID_BLOCK, ShaderKey.SHADOW_TERRAIN_CUTOUT);
        shadow(RenderPipelines.SOLID_TERRAIN, ShaderKey.SHADOW_TERRAIN_CUTOUT);
        shadow(RenderPipelines.CUTOUT_TERRAIN, ShaderKey.SHADOW_TERRAIN_CUTOUT);
        shadow(RenderPipelines.TRANSLUCENT_TERRAIN, ShaderKey.SHADOW_TRANSLUCENT);
        shadow(RenderPipelines.CUTOUT_BLOCK, ShaderKey.SHADOW_TERRAIN_CUTOUT);
        shadow(RenderPipelines.TRANSLUCENT_BLOCK, ShaderKey.SHADOW_TRANSLUCENT);
        shadow(RenderPipelines.ENTITY_CUTOUT, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        shadow(RenderPipelines.ARMOR_CUTOUT_NO_CULL, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        shadow(RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        shadow(RenderPipelines.ENTITY_SOLID, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        shadow(RenderPipelines.CRUMBLING, ShaderKey.SHADOW_TEX);
        shadow(RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        shadow(RenderPipelines.ENTITY_CUTOUT_CULL, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        shadow(RenderPipelines.ITEM_CUTOUT, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        shadow(RenderPipelines.ITEM_TRANSLUCENT, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        shadow(RenderPipelines.ENTITY_TRANSLUCENT, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        shadow(RenderPipelines.ENTITY_CUTOUT_DISSOLVE, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        shadow(RenderPipelines.ENTITY_TRANSLUCENT_CULL, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        shadow(RenderPipelines.END_CRYSTAL_BEAM, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        shadow(RenderPipelines.ENTITY_CUTOUT_Z_OFFSET, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        shadow(RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        shadow(RenderPipelines.BREEZE_WIND, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        shadow(RenderPipelines.EYES, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        shadow(RenderPipelines.BANNER_PATTERN, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        shadow(RenderPipelines.ENERGY_SWIRL, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        shadow(RenderPipelines.GLINT, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        shadow(RenderPipelines.WEATHER_DEPTH_WRITE, ShaderKey.SHADOW_PARTICLES);
        shadow(RenderPipelines.WEATHER_NO_DEPTH_WRITE, ShaderKey.SHADOW_PARTICLES);
        shadow(RenderPipelines.OPAQUE_PARTICLE, ShaderKey.SHADOW_PARTICLES);
        shadow(RenderPipelines.TRANSLUCENT_PARTICLE, ShaderKey.SHADOW_PARTICLES);
        shadow(RenderPipelines.LINES, ShaderKey.SHADOW_LINES);
        shadow(RenderPipelines.LEASH, ShaderKey.SHADOW_LEASH);
        shadow(RenderPipelines.SECONDARY_BLOCK_OUTLINE, ShaderKey.SHADOW_LINES);
        shadow(RenderPipelines.TEXT, ShaderKey.SHADOW_TEXT);
        shadow(RenderPipelines.TEXT_POLYGON_OFFSET, ShaderKey.SHADOW_TEXT);
        shadow(RenderPipelines.TEXT_SEE_THROUGH, ShaderKey.SHADOW_TEXT);
        shadow(RenderPipelines.TEXT_GRAYSCALE_SEE_THROUGH, ShaderKey.SHADOW_TEXT_INTENSITY);
        shadow(RenderPipelines.TEXT_BACKGROUND, ShaderKey.SHADOW_TEXT_BG);
        shadow(RenderPipelines.TEXT_BACKGROUND_SEE_THROUGH, ShaderKey.SHADOW_TEXT_BG);
        shadow(RenderPipelines.TEXT_GRAYSCALE, ShaderKey.SHADOW_TEXT_INTENSITY);
        shadow(RenderPipelines.WATER_MASK, ShaderKey.SHADOW_BASIC);
        shadow(RenderPipelines.BEACON_BEAM_OPAQUE, ShaderKey.SHADOW_BEACON_BEAM);
        shadow(RenderPipelines.BEACON_BEAM_TRANSLUCENT, ShaderKey.SHADOW_BEACON_BEAM);
        shadow(RenderPipelines.END_PORTAL, ShaderKey.SHADOW_BLOCK);
        shadow(RenderPipelines.END_GATEWAY, ShaderKey.SHADOW_BLOCK);
        shadow(RenderPipelines.ARMOR_TRANSLUCENT, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        shadow(RenderPipelines.LIGHTNING, ShaderKey.SHADOW_LIGHTNING);
        shadow(RenderPipelines.DRAGON_RAYS, ShaderKey.SHADOW_LIGHTNING);
    }

    private IrisMetalCoreGbufferPipelines() {
    }

    /** Reads the same live state that Iris 1.11.2 uses in {@code IrisPipelines}. */
    public static @Nullable ShaderKey resolve(
            final RenderPipeline pipeline,
            final @Nullable WorldRenderingPipeline worldPipeline
    ) {
        HandRenderer hand = HandRenderer.INSTANCE;
        return resolve(
                pipeline,
                new RenderState(
                        ShadowRenderingState.areShadowsCurrentlyBeingRendered(),
                        hand.isActive(),
                        hand.isRenderingSolid(),
                        worldPipeline != null && worldPipeline.getPhase() == WorldRenderingPhase.BLOCK_ENTITIES
                )
        );
    }

    static @Nullable ShaderKey resolve(final RenderPipeline pipeline, final RenderState state) {
        Resolver resolver = (state.shadow() ? SHADOW : MAIN).get(pipeline);
        return resolver == null ? null : resolver.resolve(state);
    }

    static int mappedPipelineCount(final boolean shadow) {
        return (shadow ? SHADOW : MAIN).size();
    }

    static Set<RenderPipeline> mappedPipelines(final boolean shadow) {
        return Set.copyOf((shadow ? SHADOW : MAIN).keySet());
    }

    /**
     * Preserves the physical ABI of Mojang's draw, including an absent stream
     * for procedural pipelines. Logical Iris inputs not present in that ABI
     * are supplied by the cross-compiler's generic constant-input path.
     */
    static @Nullable VertexFormat physicalVertexFormat(final RenderPipeline source, final ShaderKey key) {
        return source.getVertexFormatBinding(0);
    }

    private static void main(final RenderPipeline pipeline, final ShaderKey key) {
        main(pipeline, ignored -> key);
    }

    private static void main(final RenderPipeline pipeline, final Resolver resolver) {
        MAIN.put(pipeline, resolver);
    }

    private static void shadow(final RenderPipeline pipeline, final ShaderKey key) {
        SHADOW.put(pipeline, ignored -> key);
    }

    private static ShaderKey cutout(final RenderState state) {
        if (state.handActive()) {
            return state.handSolid() ? ShaderKey.HAND_CUTOUT_DIFFUSE : ShaderKey.HAND_WATER_DIFFUSE;
        }
        return state.blockEntities() ? ShaderKey.BLOCK_ENTITY_DIFFUSE : ShaderKey.ENTITIES_CUTOUT_DIFFUSE;
    }

    private static ShaderKey solid(final RenderState state) {
        if (state.handActive()) {
            return state.handSolid() ? ShaderKey.HAND_CUTOUT : ShaderKey.HAND_TRANSLUCENT;
        }
        return state.blockEntities() ? ShaderKey.BLOCK_ENTITY : ShaderKey.ENTITIES_SOLID;
    }

    private static ShaderKey translucent(final RenderState state) {
        if (state.handActive()) {
            return state.handSolid() ? ShaderKey.HAND_CUTOUT_DIFFUSE : ShaderKey.HAND_WATER_DIFFUSE;
        }
        return state.blockEntities() ? ShaderKey.BE_TRANSLUCENT : ShaderKey.ENTITIES_TRANSLUCENT;
    }

    private static ShaderKey text(final RenderState state) {
        if (state.handActive()) {
            return state.handSolid() ? ShaderKey.HAND_TEXT : ShaderKey.HAND_TEXT_TRANSLUCENT;
        }
        return state.blockEntities() ? ShaderKey.TEXT_BE : ShaderKey.TEXT;
    }

    private static ShaderKey intensityText(final RenderState state) {
        return state.blockEntities() ? ShaderKey.TEXT_INTENSITY_BE : ShaderKey.TEXT_INTENSITY;
    }

    record RenderState(boolean shadow, boolean handActive, boolean handSolid, boolean blockEntities) {
    }

    @FunctionalInterface
    private interface Resolver {
        ShaderKey resolve(RenderState state);
    }
}
