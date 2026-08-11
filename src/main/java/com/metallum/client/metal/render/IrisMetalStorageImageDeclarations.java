package com.metallum.client.metal.render;

import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Extracts the fixed Iris logical storage-image names before GPU allocation.
 *
 * <p>Only colorimgN and shadowcolorimgN are aliases for the
 * generation-owned ping-pong targets. Other image names remain pack-owned
 * resources and are resolved through IrisMetalComputeResources; an unknown
 * name is deliberately not converted into a sampled-texture fallback.</p>
 */
final class IrisMetalStorageImageDeclarations {
    record Targets(Set<Integer> color, Set<Integer> shadowColor) {
        Targets {
            color = Set.copyOf(Objects.requireNonNull(color, "color"));
            shadowColor = Set.copyOf(Objects.requireNonNull(shadowColor, "shadowColor"));
        }
    }

    private IrisMetalStorageImageDeclarations() {
    }

    static Targets from(final ProgramSet programSet) {
        Objects.requireNonNull(programSet, "programSet");
        Set<Integer> color = new LinkedHashSet<>();
        Set<Integer> shadowColor = new LinkedHashSet<>();

        for (ProgramId id : ProgramId.values()) {
            programSet.get(id).filter(ProgramSource::isValid)
                    .ifPresent(source -> collectRaster(source, color, shadowColor));
        }
        for (ProgramArrayId id : ProgramArrayId.values()) {
            for (ProgramSource source : programSet.getComposite(id)) {
                if (source != null && source.isValid()) {
                    collectRaster(source, color, shadowColor);
                }
            }
            for (ComputeSource[] group : programSet.getCompute(id)) {
                collectComputeGroup(group, color, shadowColor);
            }
        }
        collectComputeGroup(programSet.getSetup(), color, shadowColor);
        collectComputeGroup(programSet.getShadowCompute(), color, shadowColor);
        collectComputeGroup(programSet.getFinalCompute(), color, shadowColor);
        validateRanges(programSet, color, shadowColor);
        return new Targets(color, shadowColor);
    }

    private static void collectRaster(
            final ProgramSource source,
            final Set<Integer> color,
            final Set<Integer> shadowColor
    ) {
        source.getVertexSource().ifPresent(text -> collect(text, color, shadowColor));
        source.getFragmentSource().ifPresent(text -> collect(text, color, shadowColor));
    }

    private static void collectComputeGroup(
            final ComputeSource[] sources,
            final Set<Integer> color,
            final Set<Integer> shadowColor
    ) {
        if (sources == null) {
            return;
        }
        for (ComputeSource source : sources) {
            if (source != null && source.isValid()) {
                source.getSource().ifPresent(text -> collect(text, color, shadowColor));
            }
        }
    }

    private static void collect(
            final String source,
            final Set<Integer> color,
            final Set<Integer> shadowColor
    ) {
        for (IrisMetalGlslLinker.SamplerDecl declaration
                : IrisMetalGlslLinker.inspectSamplerDeclarations(source)) {
            if (!declaration.storageImage()) {
                continue;
            }
            addIndexed(declaration.name(), "colorimg", color);
            addIndexed(declaration.name(), "shadowcolorimg", shadowColor);
        }
    }

    private static void addIndexed(
            final String name,
            final String prefix,
            final Set<Integer> output
    ) {
        if (!name.startsWith(prefix)) {
            return;
        }
        try {
            int index = Integer.parseInt(name.substring(prefix.length()));
            if (index >= 0) {
                output.add(index);
            }
        } catch (NumberFormatException ignored) {
            throw new IllegalArgumentException(
                    "Iris storage image alias must end in a non-negative integer: " + name
            );
        }
    }

    private static void validateRanges(
            final ProgramSet programSet,
            final Set<Integer> color,
            final Set<Integer> shadowColor
    ) {
        int colorCount = IrisMetalRenderTargetFormats.from(programSet.getPackDirectives()).length;
        int shadowCount = programSet.getPack().hasFeature(FeatureFlags.HIGHER_SHADOWCOLOR)
                ? PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_IRIS
                : PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_OF;
        for (Integer target : color) {
            if (target >= colorCount) {
                throw new IllegalArgumentException(
                        "Iris storage image colorimg" + target
                                + " is outside 0.." + (colorCount - 1)
                );
            }
        }
        for (Integer target : shadowColor) {
            if (target >= shadowCount) {
                throw new IllegalArgumentException(
                        "Iris storage image shadowcolorimg" + target
                                + " is outside 0.." + (shadowCount - 1)
                );
            }
        }
    }
}
