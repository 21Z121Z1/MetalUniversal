package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.Identifier;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/** Builds motion-only MRT variants of Minecraft's ordinary entity pipelines. */
@Environment(EnvType.CLIENT)
final class MetalEntityMotionPipeline {
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath("metallum", "core/entity_motion");
    private static final BindGroupLayout RESOURCES = BindGroupLayout.builder()
            .withUniform("MetallumMotion", UniformType.UNIFORM_BUFFER)
            .build();
    private static final ColorTargetState MOTION_TARGET =
            new ColorTargetState(Optional.empty(), GpuFormat.RG16_FLOAT, ColorTargetState.WRITE_COLOR);
    private static final ColorTargetState VALIDITY_TARGET =
            new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, ColorTargetState.WRITE_RED);
    private static final Map<RenderPipeline, RenderPipeline> CACHE = new IdentityHashMap<>();

    private MetalEntityMotionPipeline() {
    }

    /**
     * Ordinary entity models and item models are two separate Minecraft 26.2
     * pipeline families with the same {@code DefaultVertexFormat.ENTITY} layout
     * and the same {@code ProjMat * ModelViewMat * Position} clip transform, so
     * one reduced motion shader replays both. Dropped items, item frames and
     * held items only reach the interpolator through {@code core/item}.
     */
    static boolean isSplittableVertexShader(final RenderPipeline source) {
        if (source == null) {
            return false;
        }
        String vertexShader = source.getVertexShader().getPath();
        return "core/entity".equals(vertexShader) || "core/item".equals(vertexShader);
    }

    static boolean supports(final RenderPipeline source) {
        if (!isSplittableVertexShader(source)) {
            return false;
        }
        ColorTargetState sourceTarget = source.getColorTargetState();
        return sourceTarget != null
                && sourceTarget.blendFunction().isEmpty()
                && !source.getShaderDefines().flags().contains("DISSOLVE");
    }

    static RenderPipeline forSource(final RenderPipeline source) {
        return CACHE.computeIfAbsent(source, MetalEntityMotionPipeline::build);
    }

    static void clear() {
        CACHE.clear();
    }

    private static RenderPipeline build(final RenderPipeline source) {
        String sourceName = source.getLocation().toString()
                .replace(':', '/')
                .replaceAll("[^a-zA-Z0-9_./-]", "_");
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("metallum", "entity_motion/" + sourceName))
                .withVertexShader(SHADER)
                .withFragmentShader(SHADER)
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
        source.getShaderDefines().flags().forEach(builder::withShaderDefine);
        source.getShaderDefines().values().forEach((name, value) -> {
            try {
                builder.withShaderDefine(name, Integer.parseInt(value));
            } catch (NumberFormatException integerFailure) {
                try {
                    builder.withShaderDefine(name, Float.parseFloat(value));
                } catch (NumberFormatException floatFailure) {
                    // Entity shader values currently consist of numeric
                    // ALPHA_CUTOUT thresholds. Unknown textual defines are not
                    // safe to reinterpret and therefore make this variant
                    // fail closed at shader compilation.
                    throw new IllegalArgumentException(
                            "Unsupported entity motion shader define " + name + "=" + value,
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
