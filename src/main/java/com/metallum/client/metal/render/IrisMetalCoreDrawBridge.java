package com.metallum.client.metal.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.BitSet;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/** Resolves one core draw and keeps its descriptor/PSO/context atomic. */
public final class IrisMetalCoreDrawBridge {
    private static final ThreadLocal<CoreDrawOverride> ACTIVE = new ThreadLocal<>();

    private IrisMetalCoreDrawBridge() {
    }

    public static @Nullable CoreDrawOverride prepareCoreDraw(
            final RenderPipeline source,
            final WorldRenderingPipeline worldPipeline,
            final Supplier<String> label,
            final GpuTextureView sceneColor,
            final Optional<Vector4fc> clearColor,
            final GpuTextureView sceneDepth,
            final OptionalDouble clearDepth
    ) {
        if (!(worldPipeline instanceof MetalWorldRenderingPipeline metal)) {
            return null;
        }
        ShaderKey key = IrisMetalCoreGbufferPipelines.resolve(source, worldPipeline);
        if (key == null) {
            if (metal.shouldOverrideCoreShaders(true)
                    && "minecraft".equals(source.getLocation().getNamespace())) {
                throw new IllegalStateException(
                        "Iris active pack has no fixed-version ShaderKey route for " + source.getLocation()
                );
            }
            return null;
        }
        return metal.prepareCoreDraw(
                source, key, label, sceneColor, clearColor, sceneDepth, clearDepth
        );
    }

    public static void begin(final CoreDrawOverride draw) {
        ACTIVE.set(draw);
    }

    public static void clear() {
        ACTIVE.remove();
    }

    public static boolean installPipeline(
            final RenderPassBackend backend,
            final RenderPipeline source
    ) {
        if (!(backend instanceof MetalRenderPass pass)) {
            return false;
        }
        CoreDrawOverride draw = ACTIVE.get();
        if (draw == null) {
            return false;
        }
        if (draw.source() != source) {
            throw new IllegalStateException(
                    "Iris core draw descriptor was prepared for " + draw.source().getLocation()
                            + " but received " + source.getLocation()
            );
        }
        pass.setCompiledPipeline(draw.compiled());
        IrisMetalDynamicDrawBindings.bindCore(pass, draw);
        return true;
    }

    /** Resolves core sampler names from real world-generation resources. */
    public static MetalRenderPass.@Nullable TextureViewAndSampler fallbackSampler(
            final String name,
            final Map<String, MetalRenderPass.TextureViewAndSampler> bound
    ) {
        CoreDrawOverride draw = ACTIVE.get();
        if (draw == null) {
            return null;
        }
        MetalMetalResourceView resources = new MetalMetalResourceView(
                draw.pipeline().resources(), draw.pipeline().shadowReadSnapshot()
        );
        MetalRenderPass.TextureViewAndSampler custom = resources.custom(name);
        if (custom != null) {
            return custom;
        }
        MetalRenderPass.TextureViewAndSampler alias = switch (name) {
            case "gtexture", "texture", "tex" -> first(bound, "u_BlockTex", "Sampler0");
            case "lightmap" -> first(bound, "u_LightTex", "Sampler1");
            case "overlay" -> first(bound, "Sampler1", "u_OverlayTex");
            default -> null;
        };
        if (alias != null) {
            return alias;
        }
        return resources.standard(name, draw.key().isShadow());
    }

    private static MetalRenderPass.@Nullable TextureViewAndSampler first(
            final Map<String, MetalRenderPass.TextureViewAndSampler> bound,
            final String... names
    ) {
        for (String name : names) {
            MetalRenderPass.TextureViewAndSampler value = bound.get(name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public record CoreDrawOverride(
            MetalWorldRenderingPipeline pipeline,
            RenderPipeline source,
            ShaderKey key,
            IrisMetalGlslLinker.LinkedRasterProgram program,
            MetalCompiledRenderPipeline compiled,
            com.mojang.blaze3d.systems.RenderPassDescriptor descriptor
    ) {
        public CoreDrawOverride {
            java.util.Objects.requireNonNull(pipeline, "pipeline");
            java.util.Objects.requireNonNull(source, "source");
            java.util.Objects.requireNonNull(key, "key");
            java.util.Objects.requireNonNull(program, "program");
            java.util.Objects.requireNonNull(compiled, "compiled");
            java.util.Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    private static final class MetalMetalResourceView {
        private final IrisMetalWorldResources resources;
        private final BitSet shadowReadSnapshot;

        private MetalMetalResourceView(
                final IrisMetalWorldResources resources,
                final BitSet shadowReadSnapshot
        ) {
            this.resources = resources;
            this.shadowReadSnapshot = (BitSet) shadowReadSnapshot.clone();
        }

        private MetalRenderPass.@Nullable TextureViewAndSampler custom(final String name) {
            return resources.customTextures().resolve(TextureStage.GBUFFERS_AND_SHADOW, name);
        }

        private MetalRenderPass.@Nullable TextureViewAndSampler standard(
                final String name,
                final boolean shadow
        ) {
            if ("noisetex".equals(name)) {
                return resources.noiseTexture().binding();
            }
            IrisMetalRenderTargets targets = resources.renderTargets();
            if ("depthtex0".equals(name)) {
                return new MetalRenderPass.TextureViewAndSampler(
                        targets.mainDepthView(), targets.depthSampler()
                );
            }
            if ("depthtex1".equals(name)) {
                return new MetalRenderPass.TextureViewAndSampler(
                        targets.noTranslucentsDepthView(), targets.depthSampler()
                );
            }
            if ("depthtex2".equals(name)) {
                return new MetalRenderPass.TextureViewAndSampler(
                        targets.noHandDepthView(), targets.depthSampler()
                );
            }
            int color = renderTargetIndex(name);
            if (color >= 0) {
                return new MetalRenderPass.TextureViewAndSampler(
                        targets.colorTargets().readView(color), targets.colorSampler(color)
                );
            }
            IrisMetalShadowTargets shadows = resources.shadowTargets();
            if (!shadow && shadows != null) {
                if (name.equals("shadowtex0") || name.equals("shadowtex0HW")) {
                    return new MetalRenderPass.TextureViewAndSampler(
                            shadows.shadowDepthView(), shadows.depthSampler(0, !name.endsWith("HW"))
                    );
                }
                if (name.equals("shadowtex1") || name.equals("shadowtex1HW")) {
                    return new MetalRenderPass.TextureViewAndSampler(
                            shadows.shadowDepthNoTranslucentsView(), shadows.depthSampler(1, !name.endsWith("HW"))
                    );
                }
                int shadowColor = shadowColorIndex(name);
                if (shadowColor >= 0) {
                    return new MetalRenderPass.TextureViewAndSampler(
                            shadows.colorView(shadowColor, shadowReadSnapshot),
                            shadows.colorSampler(shadowColor)
                    );
                }
            }
            return null;
        }
    }

    private static int renderTargetIndex(final String name) {
        if (name.startsWith("colortex")) {
            try {
                return Integer.parseInt(name.substring("colortex".length()));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return PackRenderTargetDirectives.LEGACY_RENDER_TARGETS.indexOf(name);
    }

    private static int shadowColorIndex(final String name) {
        if (name.equals("shadowcolor")) {
            return 0;
        }
        if (!name.startsWith("shadowcolor") || name.startsWith("shadowcolorimg")) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring("shadowcolor".length()));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
