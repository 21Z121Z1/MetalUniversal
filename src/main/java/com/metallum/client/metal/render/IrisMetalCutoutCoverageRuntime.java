package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.joml.Vector4fc;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bridges Iris-owned compact gbuffer layouts to MetalFX's exact CUTOUT coverage input.
 *
 * <p>Iris owns the pack DRAWBUFFERS slots, so MetalFX must never assume a fixed
 * attachment index. The synthetic CUTOUT shader appends an R8 target after the
 * pack's compact attachment list and writes it at the start of {@code main}.
 * GLSL {@code discard} cancels all fragment outputs, leaving an exact
 * surviving-fragment mask without changing the pack's alpha-test semantics.</p>
 *
 * <p>The extra PSO/descriptor attachment is generation-stable even when MetalFX
 * is disabled for an individual frame. Only the final copy into MetalFX's
 * existing reactive input is conditional. This keeps the cached pipeline's
 * attachment signature immutable across runtime mode switches.</p>
 */
public final class IrisMetalCutoutCoverageRuntime {
    private static final String COVERAGE_OUTPUT = "metallum_MetalFxCutoutCoverage";
    private static final Pattern MAIN = Pattern.compile(
            "\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{"
    );
    private static final ThreadLocal<SyntheticBuild> SYNTHETIC_BUILD = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> ACTIVE_TERRAIN_CUTOUT =
            ThreadLocal.withInitial(() -> false);
    private static final Field RENDER_TARGETS = renderTargetsField();
    private static final ColorTargetState COVERAGE_TARGET = new ColorTargetState(
            Optional.empty(),
            GpuFormat.R8_UNORM,
            ColorTargetState.WRITE_RED
    );

    private IrisMetalCutoutCoverageRuntime() {
    }

    /** Called by the buildSynthetic mixin before Iris emits the generated sources. */
    public static void beginSyntheticBuild(final Object kindObject, final Object programObject) {
        SYNTHETIC_BUILD.remove();
        if (kindObject != IrisMetalPipelineOverrides.TerrainKind.CUTOUT) {
            return;
        }
        if (!(programObject instanceof MetalIrisShaderCompiler.GlslProgram program)) {
            throw new IllegalArgumentException(
                    "Iris CUTOUT coverage expected MetalIrisShaderCompiler.GlslProgram, got "
                            + (programObject == null ? "null" : programObject.getClass().getName())
            );
        }
        int location = program.drawBuffers().length;
        if (location < 0 || location >= ColorTargetState.MAX_COLOR_TARGETS) {
            throw new IllegalStateException(
                    "Iris CUTOUT DRAWBUFFERS leave no color attachment for MetalFX coverage: " + location
            );
        }
        SYNTHETIC_BUILD.set(new SyntheticBuild(location));
    }

    /** Rewrites only the fragment-source Map.put selected by the mixin. */
    public static Object transformGeneratedFragment(final Object sourceObject) {
        SyntheticBuild build = SYNTHETIC_BUILD.get();
        if (build == null) {
            return sourceObject;
        }
        if (!(sourceObject instanceof String source)) {
            throw new IllegalArgumentException("Iris generated fragment source is not a String");
        }
        return injectCoverageOutput(source, build.location());
    }

    /** Appends the PSO target at the same compact slot as the injected output. */
    public static RenderPipeline finishSyntheticBuild(final RenderPipeline.Builder builder) {
        SyntheticBuild build = SYNTHETIC_BUILD.get();
        try {
            if (build != null) {
                builder.withColorTargetState(build.location(), COVERAGE_TARGET);
            }
            return builder.build();
        } finally {
            SYNTHETIC_BUILD.remove();
        }
    }

    /** Tracks Iris terrain kind independently of MetalFX's non-Iris CUTOUT helper. */
    public static void beginTerrainPass(final TerrainRenderPass pass) {
        IrisMetalPipelineOverrides.Instance instance = IrisMetalPipelineOverrides.active();
        ACTIVE_TERRAIN_CUTOUT.set(
                instance != null
                        && !IrisMetalPipelineOverrides.isShadowPassActive()
                        && pass != null
                        && IrisMetalPipelineOverrides.Instance.discriminate(pass.getPipeline())
                                == IrisMetalPipelineOverrides.TerrainKind.CUTOUT
        );
    }

    public static void endTerrainPass() {
        ACTIVE_TERRAIN_CUTOUT.remove();
    }

    /**
     * Creates the Iris-owned CUTOUT descriptor with the appended R8 target.
     * Returns null for every non-CUTOUT path so the established Iris descriptor
     * path remains authoritative there.
     */
    public static RenderPass createTerrainRenderPass(
            final CommandEncoder encoder,
            final Supplier<String> label,
            final GpuTextureView mainColor,
            final Optional<Vector4fc> clearColor,
            final GpuTextureView sceneDepth,
            final OptionalDouble clearDepth
    ) {
        if (!ACTIVE_TERRAIN_CUTOUT.get() || IrisMetalPipelineOverrides.isShadowPassActive()) {
            return null;
        }
        IrisMetalPipelineOverrides.Instance instance = IrisMetalPipelineOverrides.active();
        if (instance == null) {
            return null;
        }
        IrisMetalRenderTargets targets = renderTargets(instance);
        if (targets == null) {
            // Let the established strict Iris path report/reject unavailable
            // generation resources instead of manufacturing a fallback draw.
            return null;
        }
        int[] drawBuffers = instance.drawBuffersFor(IrisMetalPipelineOverrides.TerrainKind.CUTOUT);
        return encoder.createRenderPass(targets.createTerrainWriteDescriptor(
                label.get(),
                drawBuffers,
                mainColor,
                clearColor.orElse(null),
                sceneDepth,
                clearDepth.isPresent() ? clearDepth.getAsDouble() : null,
                true
        ));
    }

    /**
     * Copies exact Iris CUTOUT coverage into the manager-owned MetalFX input.
     * Calling cutoutReactiveAttachment is also the manager's authoritative
     * per-frame observation that an exact producer participated.
     */
    public static void flushToMetalFx() {
        if (!ACTIVE_TERRAIN_CUTOUT.get() || IrisMetalPipelineOverrides.isShadowPassActive()) {
            return;
        }
        IrisMetalPipelineOverrides.Instance instance = IrisMetalPipelineOverrides.active();
        MetalDevice device = MetalDevice.current();
        if (instance == null || device == null) {
            return;
        }
        IrisMetalRenderTargets targets = renderTargets(instance);
        if (targets == null) {
            return;
        }
        GpuTextureView destinationView = MetalFxManager.cutoutReactiveAttachment(
                targets.width(), targets.height()
        );
        if (destinationView == null) {
            return;
        }
        if (!(destinationView.texture() instanceof MetalGpuTexture destination)) {
            throw new IllegalStateException(
                    "MetalFX CUTOUT reactive attachment is not backed by a Metal texture"
            );
        }
        targets.copyMetalFxCutoutCoverageTo(device.commandEncoder(), destination);
    }

    static String injectCoverageOutput(final String source, final int location) {
        if (location < 0 || location >= ColorTargetState.MAX_COLOR_TARGETS) {
            throw new IllegalArgumentException("Invalid MetalFX CUTOUT coverage location " + location);
        }
        if (source.contains(COVERAGE_OUTPUT)) {
            throw new IllegalArgumentException(
                    "Generated Iris fragment source already declares reserved output " + COVERAGE_OUTPUT
            );
        }
        Matcher main = MAIN.matcher(source);
        if (!main.find()) {
            throw new IllegalArgumentException(
                    "Generated Iris CUTOUT fragment shader has no supported void main() entry point"
            );
        }
        int declarationAt = main.start();
        int bodyAt = main.end();
        String declaration = "layout(location = " + location + ") out float "
                + COVERAGE_OUTPUT + ";\n";
        String assignment = "\n    " + COVERAGE_OUTPUT + " = 1.0;";
        return source.substring(0, declarationAt)
                + declaration
                + source.substring(declarationAt, bodyAt)
                + assignment
                + source.substring(bodyAt);
    }

    private static IrisMetalRenderTargets renderTargets(
            final IrisMetalPipelineOverrides.Instance instance
    ) {
        try {
            return (IrisMetalRenderTargets) RENDER_TARGETS.get(instance);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not access Iris generation render targets", e);
        }
    }

    private static Field renderTargetsField() {
        try {
            Field field = IrisMetalPipelineOverrides.Instance.class.getDeclaredField("renderTargets");
            if (!field.trySetAccessible()) {
                throw new IllegalStateException("Iris Instance.renderTargets is not accessible");
            }
            return field;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Iris Metal ABI changed: expected Instance.renderTargets",
                    e
            );
        }
    }

    private record SyntheticBuild(int location) {
    }
}
