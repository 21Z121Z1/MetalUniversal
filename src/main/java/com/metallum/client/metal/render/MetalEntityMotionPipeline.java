package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/** Builds motion-only MRT variants of the Minecraft pipelines that can be replayed. */
@Environment(EnvType.CLIENT)
final class MetalEntityMotionPipeline {
    /**
     * A group of Minecraft pipelines whose clip position one reduced motion
     * shader can reproduce.
     *
     * <p>Family membership is decided by the clip transform, not by what the
     * geometry represents. Two pipelines belong together exactly when the same
     * reduced vertex shader rebuilds their raster clip position from the same
     * attributes and uniforms; anything else needs its own family, because a
     * shader that reconstructs the wrong clip position produces motion vectors
     * that look plausible and are wrong.</p>
     */
    enum Family {
        /**
         * {@code core/entity} and {@code core/item}: {@code DefaultVertexFormat.ENTITY}
         * with clip position {@code ProjMat * ModelViewMat * Position}. Entity
         * models, dropped items, item frames and held items.
         */
        ENTITY("core/entity_motion", "entity_motion/"),
        /**
         * {@code core/block}: {@code DefaultVertexFormat.BLOCK} with clip position
         * {@code ProjMat * ModelViewMat * (Position + ModelOffset)}. Falling
         * blocks and block entities reach the interpolator only through this
         * family; before it existed they arrived with no object motion at all.
         */
        BLOCK("core/block_motion", "block_motion/");

        private final Identifier shader;
        private final String locationPrefix;

        Family(final String shaderPath, final String locationPrefix) {
            this.shader = Identifier.fromNamespaceAndPath("metallum", shaderPath);
            this.locationPrefix = locationPrefix;
        }

        Identifier shader() {
            return shader;
        }

        String locationPrefix() {
            return locationPrefix;
        }
    }

    private enum PreviousFamily {
        ENTITY("core/entity_previous_motion", "core/entity_motion", "entity_previous_motion/"),
        SHADOW("core/entity_previous_motion", "core/shadow_previous_motion", "shadow_previous_motion/"),
        PARTICLE("core/particle_previous_motion", "core/particle_previous_motion", "particle_previous_motion/"),
        LEASH("core/leash_previous_motion", "core/leash_previous_motion", "leash_previous_motion/"),
        TEXT("core/text_previous_motion", "core/text_previous_motion", "text_previous_motion/"),
        TEXT_BACKGROUND("core/text_background_previous_motion", "core/text_background_previous_motion", "text_background_previous_motion/"),
        WATER_MASK("core/position_previous_motion", "core/position_previous_motion", "position_previous_motion/");

        private final Identifier vertexShader;
        private final Identifier fragmentShader;
        private final String locationPrefix;

        PreviousFamily(final String vertexPath, final String fragmentPath, final String locationPrefix) {
            this.vertexShader = Identifier.fromNamespaceAndPath("metallum", vertexPath);
            this.fragmentShader = Identifier.fromNamespaceAndPath("metallum", fragmentPath);
            this.locationPrefix = locationPrefix;
        }
    }
    private static final VertexFormat PREVIOUS_POSITION_FORMAT = VertexFormat.builder(0)
            .addAttribute("PreviousPosition", GpuFormat.RGB32_FLOAT)
            .build();
    private static final BindGroupLayout RESOURCES = BindGroupLayout.builder()
            .withUniform("MetallumMotion", UniformType.UNIFORM_BUFFER)
            .build();
    private static final ColorTargetState MOTION_TARGET =
            new ColorTargetState(Optional.empty(), GpuFormat.RG16_FLOAT, ColorTargetState.WRITE_COLOR);
    private static final ColorTargetState VALIDITY_TARGET =
            new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, ColorTargetState.WRITE_RED);
    private static final Map<RenderPipeline, RenderPipeline> CACHE = new IdentityHashMap<>();
    private static final Map<RenderPipeline, RenderPipeline> PREVIOUS_POSITION_CACHE = new IdentityHashMap<>();

    private MetalEntityMotionPipeline() {
    }

    /**
     * The family that can replay {@code source}, or null if none can.
     *
     * <p>Keyed on the Minecraft vertex shader path, which is what identifies the
     * clip transform. A pipeline whose shader is not listed here is left alone
     * rather than replayed by the closest-looking family.</p>
     */
    static @Nullable Family familyOf(final RenderPipeline source) {
        if (source == null) {
            return null;
        }
        return switch (source.getVertexShader().getPath()) {
            case "core/entity", "core/item" -> Family.ENTITY;
            case "core/block" -> Family.BLOCK;
            default -> null;
        };
    }

    static boolean isSplittableVertexShader(final RenderPipeline source) {
        return familyOf(source) != null || previousFamilyOf(source) != null;
    }

    static boolean supports(final RenderPipeline source) {
        if (familyOf(source) == null) {
            return false;
        }
        ColorTargetState sourceTarget = source.getColorTargetState();
        return sourceTarget != null
                && sourceTarget.blendFunction().isEmpty()
                && !source.getShaderDefines().flags().contains("DISSOLVE");
    }

    /**
     * Exact staged-position replay ABIs proven against the Minecraft 26.2 client shaders.
     * Root-transform support is intentionally independent: leash and world text are exact-only
     * families and must never fall back to a closest-looking root motion shader.
     */
    static boolean supportsPreviousPositions(final RenderPipeline source) {
        return previousFamilyOf(source) != null;
    }

    private static @Nullable PreviousFamily previousFamilyOf(final RenderPipeline source) {
        if (source == null) {
            return null;
        }
        VertexFormat[] bindings = source.getVertexFormatBindings();
        if (bindings.length == 0 || bindings[0] == null || (bindings.length >= 2 && bindings[1] != null)) {
            return null;
        }
        VertexFormat format = bindings[0];
        String shader = source.getVertexShader().getPath();
        if ((shader.equals("core/entity") || shader.equals("core/item"))
                && DefaultVertexFormat.ENTITY.equals(format)
                && supports(source)) {
            return PreviousFamily.ENTITY;
        }
        if (shader.equals("core/rendertype_entity_shadow")
                && DefaultVertexFormat.ENTITY.equals(format)) {
            return PreviousFamily.SHADOW;
        }
        if (shader.equals("core/particle")
                && DefaultVertexFormat.PARTICLE.equals(format)) {
            return PreviousFamily.PARTICLE;
        }
        if (shader.equals("core/rendertype_leash")
                && DefaultVertexFormat.POSITION_COLOR_LIGHTMAP.equals(format)) {
            return PreviousFamily.LEASH;
        }
        if (shader.equals("core/text") && !source.getShaderDefines().flags().contains("IS_GUI")) {
            boolean seeThrough = source.getShaderDefines().flags().contains("IS_SEE_THROUGH");
            VertexFormat expected = seeThrough
                    ? DefaultVertexFormat.POSITION_TEX_COLOR
                    : DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR;
            return expected.equals(format) ? PreviousFamily.TEXT : null;
        }
        if (shader.equals("core/text_background")) {
            boolean seeThrough = source.getShaderDefines().flags().contains("IS_SEE_THROUGH");
            VertexFormat expected = seeThrough
                    ? DefaultVertexFormat.POSITION_COLOR
                    : DefaultVertexFormat.POSITION_COLOR_LIGHTMAP;
            return expected.equals(format) ? PreviousFamily.TEXT_BACKGROUND : null;
        }
        if (shader.equals("core/rendertype_water_mask")
                && DefaultVertexFormat.POSITION.equals(format)) {
            return PreviousFamily.WATER_MASK;
        }
        return null;
    }

    static VertexFormat previousPositionFormat() {
        return PREVIOUS_POSITION_FORMAT;
    }

    static RenderPipeline forSource(final RenderPipeline source) {
        return CACHE.computeIfAbsent(source, MetalEntityMotionPipeline::build);
    }

    static RenderPipeline forPreviousPositions(final RenderPipeline source) {
        if (!supportsPreviousPositions(source)) {
            throw new IllegalArgumentException("Source pipeline has no exact previous-position motion ABI: " + source.getLocation());
        }
        return PREVIOUS_POSITION_CACHE.computeIfAbsent(source, MetalEntityMotionPipeline::buildPreviousPositions);
    }

    static void clear() {
        CACHE.clear();
        PREVIOUS_POSITION_CACHE.clear();
    }

    private static RenderPipeline build(final RenderPipeline source) {
        return buildVariant(source, false);
    }

    private static RenderPipeline buildPreviousPositions(final RenderPipeline source) {
        PreviousFamily previousFamily = previousFamilyOf(source);
        if (previousFamily == null) {
            throw new IllegalArgumentException(
                    "Source pipeline has no exact previous-position family: " + source.getLocation());
        }
        return buildVariant(source, null, previousFamily);
    }

    private static RenderPipeline buildVariant(final RenderPipeline source, final boolean previousPositions) {
        Family family = familyOf(source);
        if (family == null) {
            throw new IllegalArgumentException(
                    "No root motion family replays " + source.getLocation() + " (" + source.getVertexShader() + ")");
        }
        if (previousPositions) {
            PreviousFamily previousFamily = previousFamilyOf(source);
            if (previousFamily == null) {
                throw new IllegalArgumentException("Previous-position replay is not defined for " + source.getLocation());
            }
            return buildVariant(source, family, previousFamily);
        }
        return buildVariant(source, family, null);
    }

    private static RenderPipeline buildVariant(
            final RenderPipeline source,
            final @Nullable Family family,
            final @Nullable PreviousFamily previousFamily
    ) {
        boolean previousPositions = previousFamily != null;
        if (!previousPositions && family == null) {
            throw new IllegalArgumentException("Missing root motion family for " + source.getLocation());
        }
        String sourceName = source.getLocation().toString()
                .replace(':', '/')
                .replaceAll("[^a-zA-Z0-9_./-]", "_");
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(
                        "metallum",
                        (previousPositions ? previousFamily.locationPrefix : family.locationPrefix()) + sourceName
                ))
                .withVertexShader(previousPositions ? previousFamily.vertexShader : family.shader())
                .withFragmentShader(previousPositions ? previousFamily.fragmentShader : family.shader())
                .withCull(source.isCull())
                .withPolygonMode(source.getPolygonMode())
                .withPrimitiveTopology(source.getPrimitiveTopology())
                .withColorTargetState(0, MOTION_TARGET)
                .withColorTargetState(1, VALIDITY_TARGET);

        source.getBindGroupLayouts().forEach(builder::withBindGroupLayout);
        builder.withBindGroupLayout(RESOURCES);
        for (int slot = 0; slot < source.getVertexFormatBindings().length; slot++) {
            if (source.getVertexFormatBinding(slot) != null) {
                builder.withVertexBinding(slot, source.getVertexFormatBinding(slot));
            }
        }
        if (previousPositions) {
            builder.withVertexBinding(1, PREVIOUS_POSITION_FORMAT);
        }
        source.getShaderDefines().flags().forEach(builder::withShaderDefine);
        source.getShaderDefines().values().forEach((name, value) -> {
            try {
                builder.withShaderDefine(name, Integer.parseInt(value));
            } catch (NumberFormatException integerFailure) {
                try {
                    builder.withShaderDefine(name, Float.parseFloat(value));
                } catch (NumberFormatException floatFailure) {
                    // Both families' shader values currently consist of numeric
                    // ALPHA_CUTOUT thresholds. Unknown textual defines are not
                    // safe to reinterpret and therefore make this variant
                    // fail closed at shader compilation.
                    throw new IllegalArgumentException(
                            "Unsupported motion shader define " + name + "=" + value,
                            floatFailure
                    );
                }
            }
        });

        DepthStencilState sourceDepth = source.getDepthStencilState();
        if (sourceDepth != null) {
            builder.withDepthStencilState(new DepthStencilState(
                    sourceDepth.depthTest(),
                    false,
                    sourceDepth.depthBiasScaleFactor(),
                    sourceDepth.depthBiasConstant()
            ));
        }
        return builder.build();
    }
}
