package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
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
        BLOCK("core/block_previous_motion", "core/block_motion", "block_previous_motion/"),
        SHADOW("core/entity_previous_motion", "core/shadow_previous_motion", "shadow_previous_motion/"),
        PARTICLE("core/particle_previous_motion", "core/particle_previous_motion", "particle_previous_motion/"),
        LEASH("core/leash_previous_motion", "core/leash_previous_motion", "leash_previous_motion/"),
        TEXT("core/text_previous_motion", "core/text_previous_motion", "text_previous_motion/"),
        TEXT_BACKGROUND("core/text_background_previous_motion", "core/text_background_previous_motion", "text_background_previous_motion/"),
        LIGHTNING("core/lightning_previous_motion", "core/lightning_previous_motion", "lightning_previous_motion/"),
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
    private static final VertexFormat PREVIOUS_VALIDITY_FORMAT = VertexFormat.builder(0)
            .addAttribute("PreviousPosition", GpuFormat.RGB32_FLOAT)
            .addAttribute("PreviousValidity", GpuFormat.R32_FLOAT).build();
    private static final VertexFormat PREVIOUS_COVERAGE_FORMAT=VertexFormat.builder(0)
            .addAttribute("PreviousPosition",GpuFormat.RGB32_FLOAT).addAttribute("PreviousValidity",GpuFormat.R32_FLOAT)
            .addAttribute("PreviousCoverage",GpuFormat.RG32_FLOAT).build();
    private static final BindGroupLayout RESOURCES = BindGroupLayout.builder()
            .withUniform("MetallumMotion", UniformType.UNIFORM_BUFFER)
            .build();
    private static final ColorTargetState MOTION_TARGET =
            new ColorTargetState(Optional.empty(), GpuFormat.RG16_FLOAT, ColorTargetState.WRITE_COLOR);
    private static final ColorTargetState VALIDITY_TARGET =
            new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, ColorTargetState.WRITE_RED);
    private static final ColorTargetState DEPTH_TARGET =
            new ColorTargetState(Optional.empty(), GpuFormat.RG32_FLOAT, ColorTargetState.WRITE_RED | ColorTargetState.WRITE_GREEN);
    private static final Map<RenderPipeline, RenderPipeline> CACHE = new IdentityHashMap<>();
    private static final Map<RenderPipeline, RenderPipeline> PREVIOUS_POSITION_CACHE = new IdentityHashMap<>();
    private static final Map<RenderPipeline, RenderPipeline[]> DEPTH_CACHE = new IdentityHashMap<>();
    private static final Map<RenderPipeline, RenderPipeline[]> PREVIOUS_POSITION_DEPTH_CACHE = new IdentityHashMap<>();
    private static final Map<RenderPipeline, RenderPipeline[]> PREVIOUS_COVERAGE_DEPTH_CACHE = new IdentityHashMap<>();
    private static final BindGroupLayout SOURCE_DEPTH = BindGroupLayout.builder()
            .withUniform("MetallumSourceDepth",UniformType.COMBINED_IMAGE_SAMPLER).build();
    private static final BindGroupLayout PREVIOUS_RASTER = BindGroupLayout.builder()
            .withUniform("MetallumSurface",UniformType.UNIFORM_BUFFER).build();

    private MetalEntityMotionPipeline() {
    }

    static boolean usesPreviousDepthBias(final RenderPipeline source,final boolean depthAttachmentPresent) {
        var depth=source.getDepthStencilState();
        return depthAttachmentPresent && depth!=null
                && (depth.depthBiasScaleFactor()!=0 || depth.depthBiasConstant()!=0);
    }

    static boolean requiresFogSnapshot(final RenderPipeline source) {
        return previousFamilyOf(source)==PreviousFamily.LIGHTNING;
    }

    static boolean usesEntityLightmap(final RenderPipeline source) {
        return previousFamilyOf(source)==PreviousFamily.ENTITY
                && (source.getShaders().get(ShaderType.VERTEX).getPath().equals("core/item")
                    || !source.getShaderDefines().flags().contains("EMISSIVE"));
    }

    static boolean usesVisibilityLightmap(final RenderPipeline source) {
        var family=previousFamilyOf(source);
        return usesEntityLightmap(source) || family==PreviousFamily.BLOCK || family==PreviousFamily.PARTICLE
                || ((family==PreviousFamily.TEXT || family==PreviousFamily.TEXT_BACKGROUND)
                    && !source.getShaderDefines().flags().contains("IS_SEE_THROUGH"));
    }

    private static boolean auditedShaders(final RenderPipeline source) {
        if (source == null) return false;
        var vertex = source.getShaders().get(ShaderType.VERTEX);
        var fragment = source.getShaders().get(ShaderType.FRAGMENT);
        return vertex != null && vertex.equals(fragment) && "minecraft".equals(vertex.getNamespace());
    }

    /**
     * The family that can replay {@code source}, or null if none can.
     *
     * <p>Keyed on the Minecraft vertex shader path, which is what identifies the
     * clip transform. A pipeline whose shader is not listed here is left alone
     * rather than replayed by the closest-looking family.</p>
     */
    static @Nullable Family familyOf(final RenderPipeline source) {
        if (!auditedShaders(source)) {
            return null;
        }
        var bindings = source.getVertexFormatBindings();
        if (bindings.isEmpty() || bindings.getFirst() == null
                || bindings.stream().skip(1).anyMatch(java.util.Objects::nonNull)) return null;
        return switch (source.getShaders().get(ShaderType.VERTEX).getPath()) {
            case "core/entity", "core/item" -> previousFamilyOf(source) == PreviousFamily.ENTITY ? Family.ENTITY : null;
            case "core/block" -> DefaultVertexFormat.BLOCK.equals(bindings.getFirst()) ? Family.BLOCK : null;
            default -> null;
        };
    }

    static boolean isSplittableVertexShader(final RenderPipeline source) {
        return familyOf(source) != null || previousFamilyOf(source) != null;
    }

    static boolean supports(final RenderPipeline source) {
        if (familyOf(source) == null || !replayableDepth(source)) {
            return false;
        }
        ColorTargetState sourceTarget = source.getColorTargetStates().isEmpty()
                ? null : source.getColorTargetStates().getFirst();
        return sourceTarget != null
                && sourceTarget.blendFunction().isEmpty()
                && !source.getShaderDefines().flags().contains("DISSOLVE");
    }

    /**
     * Exact staged-position replay ABIs checked against the Minecraft 26.3 client shaders.
     * Root-transform support is intentionally independent: leash and world text are exact-only
     * families and must never fall back to a closest-looking root motion shader.
     */
    static boolean supportsPreviousPositions(final RenderPipeline source) {
        return previousFamilyOf(source) != null && replayableDepth(source);
    }

    private static boolean replayableDepth(final RenderPipeline source) {
        var depth=source.getDepthStencilState();
        if(depth!=null && (depth.depthTest()==CompareOp.EQUAL || depth.depthTest()==CompareOp.NOT_EQUAL)
                && (depth.depthBiasScaleFactor()!=0 || depth.depthBiasConstant()!=0)) return false;
        // A live final attachment cannot reconstruct the pre-draw depth for these self-writing tests.
        return depth==null || !depth.writeDepth() || switch(depth.depthTest()) {
            case GREATER_THAN,LESS_THAN,NOT_EQUAL -> false;
            default -> true;
        };
    }

    private static @Nullable PreviousFamily previousFamilyOf(final RenderPipeline source) {
        if (!auditedShaders(source)) {
            return null;
        }
        java.util.List<@Nullable VertexFormat> bindings = source.getVertexFormatBindings();
        if (bindings.isEmpty() || bindings.get(0) == null || (bindings.size() >= 2 && bindings.get(1) != null)) {
            return null;
        }
        VertexFormat format = bindings.get(0);
        String shader = source.getShaders().get(ShaderType.VERTEX).getPath();
        if (shader.equals("core/entity") || shader.equals("core/item")) {
            boolean special = source.getShaderDefines().flags().contains("GLINT_SPECIAL");
            if (!special && DefaultVertexFormat.ENTITY.equals(format)) return PreviousFamily.ENTITY;
            if (shader.equals("core/item") && special && source.getShaderDefines().flags().contains("GLINT")
                    && DefaultVertexFormat.ENTITY_GLINT_SPECIAL.equals(format)) return PreviousFamily.ENTITY;
            return null;
        }
        if (shader.equals("core/rendertype_entity_shadow")
                && DefaultVertexFormat.ENTITY.equals(format)) {
            return PreviousFamily.SHADOW;
        }
        if (shader.equals("core/block") && DefaultVertexFormat.BLOCK.equals(format)) return PreviousFamily.BLOCK;
        if (shader.equals("core/rendertype_lightning") && DefaultVertexFormat.POSITION_COLOR.equals(format)) {
            return PreviousFamily.LIGHTNING;
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
        DEPTH_CACHE.clear();
        PREVIOUS_POSITION_DEPTH_CACHE.clear();
        PREVIOUS_COVERAGE_DEPTH_CACHE.clear();
    }

    static RenderPipeline forSourceWithDepth(final RenderPipeline source) {
        return forSourceWithDepth(source,true);
    }

    static RenderPipeline forSourceWithDepth(final RenderPipeline source,final boolean depthAttachmentPresent) {
        if(!replayableDepth(source))throw new IllegalArgumentException("Source needs pre-draw depth provenance: "+source.getLocation());
        RenderPipeline[] variants=DEPTH_CACHE.computeIfAbsent(source,ignored->new RenderPipeline[2]);
        int slot=depthAttachmentPresent ? 1 : 0;
        if(variants[slot]==null)variants[slot]=buildVariant(source,familyOf(source),null,true,depthAttachmentPresent);
        return variants[slot];
    }

    static RenderPipeline forPreviousPositionsWithDepth(final RenderPipeline source) {
        return forPreviousPositionsWithDepth(source,true);
    }

    static RenderPipeline forPreviousPositionsWithDepth(final RenderPipeline source,final boolean depthAttachmentPresent) {
        if(!replayableDepth(source))throw new IllegalArgumentException("Source needs pre-draw depth provenance: "+source.getLocation());
        PreviousFamily family = previousFamilyOf(source);
        if (family == null) throw new IllegalArgumentException("Source has no previous-position ABI: "+source.getLocation());
        RenderPipeline[] variants=PREVIOUS_POSITION_DEPTH_CACHE.computeIfAbsent(source,ignored->new RenderPipeline[2]);
        int slot=depthAttachmentPresent ? 1 : 0;
        if(variants[slot]==null)variants[slot]=buildVariant(source,null,family,true,depthAttachmentPresent);
        return variants[slot];
    }

    static boolean testsSourceDepth(final RenderPipeline source,final boolean depthAttachmentPresent) {
        return depthAttachmentPresent && source.getDepthStencilState()!=null
                && source.getDepthStencilState().depthTest()!=CompareOp.ALWAYS_PASS;
    }

    static RenderPipeline forPreviousPositionsWithCoverage(final RenderPipeline source,final boolean depthAttachmentPresent) {
        if(previousFamilyOf(source)!=PreviousFamily.PARTICLE || !replayableDepth(source))
            throw new IllegalArgumentException("Source has no bounded weather correspondence ABI: "+source.getLocation());
        RenderPipeline[] variants=PREVIOUS_COVERAGE_DEPTH_CACHE.computeIfAbsent(source,ignored->new RenderPipeline[2]);
        int slot=depthAttachmentPresent ? 1 : 0;
        if(variants[slot]==null)variants[slot]=buildVariant(source,null,PreviousFamily.PARTICLE,true,depthAttachmentPresent,true);
        return variants[slot];
    }

    private static boolean overridesPrivateDepth(final RenderPipeline source,final boolean depthAttachmentPresent) {
        return !testsSourceDepth(source,depthAttachmentPresent)
                || source.getDepthStencilState().depthTest()==CompareOp.EQUAL
                || source.getDepthStencilState().depthTest()==CompareOp.NOT_EQUAL;
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
                    "No root motion family replays " + source.getLocation() + " ("
                            + source.getShaders().get(ShaderType.VERTEX) + ")");
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
        return buildVariant(source, family, previousFamily, false);
    }

    private static RenderPipeline buildVariant(
            final RenderPipeline source,
            final @Nullable Family family,
            final @Nullable PreviousFamily previousFamily,
            final boolean depthCorrespondence
    ) {
        return buildVariant(source,family,previousFamily,depthCorrespondence,true);
    }

    private static RenderPipeline buildVariant(final RenderPipeline source,final @Nullable Family family,
            final @Nullable PreviousFamily previousFamily,final boolean depthCorrespondence,
            final boolean depthAttachmentPresent) {
        return buildVariant(source,family,previousFamily,depthCorrespondence,depthAttachmentPresent,false);
    }

    private static RenderPipeline buildVariant(final RenderPipeline source,final @Nullable Family family,
            final @Nullable PreviousFamily previousFamily,final boolean depthCorrespondence,
            final boolean depthAttachmentPresent,final boolean previousCoverage) {
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
                        (previousPositions ? previousFamily.locationPrefix : family.locationPrefix())
                                + (previousCoverage ? "bounded/" : "")
                                + (depthCorrespondence ? depthAttachmentPresent ? "depth/" : "depth/no_source_attachment/" : "") + sourceName
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
        for (int slot = 0; slot < source.getVertexFormatBindings().size(); slot++) {
            if (source.getVertexFormatBinding(slot) != null) {
                builder.withVertexBinding(slot, source.getVertexFormatBinding(slot));
            }
        }
        if (previousPositions) {
            builder.withVertexBinding(1,previousCoverage ? PREVIOUS_COVERAGE_FORMAT
                    : depthCorrespondence ? PREVIOUS_VALIDITY_FORMAT : PREVIOUS_POSITION_FORMAT);
            if(previousCoverage)builder.withShaderDefine("METALLUM_PREVIOUS_COVERAGE").withShaderDefine("METALLUM_REACTIVE_MATERIAL");
            if (depthCorrespondence) builder.withShaderDefine("METALLUM_PREVIOUS_VALIDITY");
            if (DefaultVertexFormat.ENTITY_GLINT_SPECIAL.equals(source.getVertexFormatBinding(0))) {
                builder.withShaderDefine("METALLUM_ENTITY_GLINT_SPECIAL");
            }
        }
        if (source.getShaders().get(ShaderType.VERTEX).getPath().equals("core/item")) {
            builder.withShaderDefine("METALLUM_ITEM_SOURCE");
        }
        if (usesEntityLightmap(source)) builder.withShaderDefine("METALLUM_ENTITY_LIGHTMAP");
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
        if (depthCorrespondence) {
            builder.withShaderDefine("METALLUM_MOTION_DEPTH").withColorTargetState(2, DEPTH_TARGET);
            if (usesPreviousDepthBias(source,depthAttachmentPresent)) {
                builder.withBindGroupLayout(PREVIOUS_RASTER).withShaderDefine("METALLUM_PREVIOUS_DEPTH_BIAS");
            }
            if (testsSourceDepth(source,depthAttachmentPresent)) {
                builder.withBindGroupLayout(SOURCE_DEPTH)
                        .withShaderDefine("METALLUM_TEST_SOURCE_DEPTH")
                        .withShaderDefine("METALLUM_SOURCE_DEPTH_"+source.getDepthStencilState().depthTest().name());
            }
            if(overridesPrivateDepth(source,depthAttachmentPresent))builder.withShaderDefine("METALLUM_MOTION_OVERLAY");
            if (source.getShaderDefines().flags().stream().anyMatch(flag -> flag.equals("GLINT")
                    || flag.equals("APPLY_TEXTURE_MATRIX") || flag.equals("DISSOLVE"))
                    || previousFamily==PreviousFamily.LIGHTNING) {
                builder.withShaderDefine("METALLUM_REACTIVE_MATERIAL");
            }
        }
        boolean alphaComposite = source.getColorTargetStates().stream().filter(java.util.Objects::nonNull)
                .anyMatch(target -> target.blendFunction().map(blend -> blend.color().sourceFactor() == BlendFactor.SRC_ALPHA).orElse(false))
                // Vanilla's additive OIT also premultiplies RGB by source alpha.
                || source.getShaderDefines().flags().contains("OIT_ACCUMULATE");
        if (alphaComposite) builder.withShaderDefine("METALLUM_ALPHA_COMPOSITE");

        DepthStencilState sourceDepth = source.getDepthStencilState();
        if (depthCorrespondence && overridesPrivateDepth(source,depthAttachmentPresent)) {
            // A source overlay ignores scene Z. Keep its raster Z in our private attachment.
            builder.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS,true,
                    depthAttachmentPresent && sourceDepth!=null ? sourceDepth.depthBiasScaleFactor() : 0,
                    depthAttachmentPresent && sourceDepth!=null ? sourceDepth.depthBiasConstant() : 0));
        } else if (sourceDepth != null) {
            builder.withDepthStencilState(new DepthStencilState(
                    sourceDepth.depthTest(),
                    true,
                    sourceDepth.depthBiasScaleFactor(),
                    sourceDepth.depthBiasConstant()
            ));
        }
        return builder.build();
    }
}
