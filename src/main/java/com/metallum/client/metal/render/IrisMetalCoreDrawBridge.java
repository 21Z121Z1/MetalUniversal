package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
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
        // PreparedRenderType is also used for GUI atlas work after the world
        // pass. Only a draw inside the active world boundary owns Iris targets;
        // GUI/HUD passes remain an explicit non-owned vanilla path.
        if (!metal.shouldOverrideCoreShaders(true)) {
            return null;
        }
        IrisMetalCoreGbufferPipelines.DrawOwnership ownership =
                IrisMetalCoreGbufferPipelines.ownership(source, worldPipeline);
        if (ownership == IrisMetalCoreGbufferPipelines.DrawOwnership.EXPLICIT_NON_OWNED) {
            return null;
        }
        ShaderKey key = IrisMetalCoreGbufferPipelines.resolve(source, worldPipeline);
        if (key == null) {
            if (ownership == IrisMetalCoreGbufferPipelines.DrawOwnership.UNKNOWN
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

    /**
     * Creates a public RenderPass while carrying Iris-only attachment metadata
     * through Mojang's CommandEncoder lifecycle. Calling the Metal backend
     * directly would skip CommandEncoder's in-pass guard and onFinish hook.
     */
    public static RenderPass createRenderPass(
            final CommandEncoder encoder,
            final IrisMetalRenderTargets.RenderPassDescriptorWithViews descriptor
    ) {
        java.util.Objects.requireNonNull(encoder, "encoder");
        java.util.Objects.requireNonNull(descriptor, "descriptor");
        if (!(encoder instanceof com.metallum.mixin.iris.CommandEncoderBackendAccessor accessor)) {
            throw new IllegalStateException(
                    "Iris Metal render pass cannot access CommandEncoder backend; "
                            + "the CommandEncoderBackendAccessor mixin is not active"
            );
        }
        CommandEncoderBackend backend = accessor.metallum$getBackend();
        if (!(backend instanceof MetalCommandEncoder metal)) {
            throw new IllegalStateException(
                    "Iris Metal render pass was routed through a non-Metal CommandEncoder backend: "
                            + backend
            );
        }
        metal.prepareIrisRenderPass(descriptor.metadata(), descriptor);
        try {
            return encoder.createRenderPass(descriptor.descriptor());
        } catch (RuntimeException | Error failure) {
            metal.cancelIrisRenderPass(descriptor);
            throw failure;
        }
    }

    /** Records an actual native draw for a generation-owned shadow core pass. */
    static void recordShadowDraw() {
        CoreDrawOverride draw = ACTIVE.get();
        if (draw != null && draw.key().isShadow()) {
            draw.pipeline().recordShadowCoreDraw();
        }
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
        return true;
    }

    /**
     * Binds core draw-owned uniforms at the first actual draw boundary.
     * Minecraft's PreparedRenderType sets DynamicTransforms and Projection
     * after setPipeline, so binding from the setPipeline mixin is too early.
     */
    static boolean bindPending(final MetalRenderPass pass) {
        CoreDrawOverride draw = ACTIVE.get();
        if (draw == null) {
            return false;
        }
        IrisMetalDynamicDrawBindings.bindCore(pass, draw);
        return true;
    }

    /**
     * Validates a draw-local typed buffer supplied by Minecraft itself. These
     * buffers are intentionally not generation resources: their contents and
     * lifetime belong to the current core draw and must arrive through the
     * vanilla RenderPass call.
     */
    public static void validateDrawOwnedTexelBuffer(
            final String passName,
            final String resourceName,
            final GpuBuffer value
    ) {
        CoreDrawOverride draw = ACTIVE.get();
        if (draw == null || !IrisMetalTexelBufferAbi.isFixedProvider(resourceName)) {
            return;
        }
        MetalDevice device = MetalDeviceRegistry.getActiveDevice();
        if (device == null) {
            throw new IllegalStateException(
                    "Iris core draw " + passName
                            + " supplied typed texel buffer '" + resourceName
                            + "' without an active Metal device"
            );
        }
        GpuFormat format = IrisMetalTexelBufferAbi.formatFor(resourceName);
        if (format == null) {
            throw new IllegalStateException(
                    "Iris core draw " + passName
                            + " supplied an unknown fixed texel buffer '" + resourceName + "'"
            );
        }
        IrisMetalTexelBufferAbi.requireSlice(
                passName,
                resourceName,
                value.slice(),
                format,
                device
        );
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
            case "lightmap" -> first(bound, "u_LightTex", "Sampler2");
            case "iris_overlay", "overlay" -> first(bound, "Sampler1", "u_OverlayTex");
            default -> null;
        };
        if (alias != null) {
            return alias;
        }
        if (isCloudAlbedoSampler(name, draw.key())) {
            MetalRenderPass.TextureViewAndSampler atlas = cloudAtlasBinding();
            if (atlas != null) {
                return atlas;
            }
        }
        if ("iris_overlay".equals(name) || "overlay".equals(name)) {
            return draw.pipeline().mojangExternalOverlay();
        }
        return resources.standard(name, draw.key().isShadow());
    }

    /**
     * The vanilla procedural cloud pass has no sampler write in its
     * {@code RenderPass}; fixed Iris nevertheless gives {@code texture},
     * {@code gtexture}, and {@code tex} the level albedo sampler (unit 0).
     * Resolve that contract from the live block atlas only for cloud draws.
     */
    static boolean isCloudAlbedoSampler(final String name, final ShaderKey key) {
        return !key.isShadow()
                && (key == ShaderKey.CLOUDS || key == ShaderKey.CLOUDS_SODIUM)
                && (name.equals("gtexture") || name.equals("texture") || name.equals("tex"));
    }

    static MetalRenderPass.@Nullable TextureViewAndSampler cloudAtlasBinding() {
        AbstractTexture texture = Minecraft.getInstance()
                .getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS);
        if (!(texture instanceof TextureAtlas)
                || !(texture.getTextureView() instanceof MetalGpuTextureView view)
                || !(view.texture() instanceof MetalGpuTexture gpuTexture)
                || !(texture.getSampler() instanceof MetalGpuSampler sampler)
                || view.isClosed()
                || gpuTexture.isClosed()
                || sampler.isClosed()
                || (gpuTexture.usage() & GpuTexture.USAGE_TEXTURE_BINDING) == 0) {
            return null;
        }
        MetalDevice activeDevice = MetalDeviceRegistry.getActiveDevice();
        if (activeDevice == null
                || !gpuTexture.isOwnedBy(activeDevice)
                || !sampler.isOwnedBy(activeDevice)) {
            return null;
        }
        return new MetalRenderPass.TextureViewAndSampler(view, sampler);
    }

    /**
     * Validates Mojang's externally managed overlay binding without taking
     * ownership of the texture or sampler. The caller stores only the returned
     * view/sampler pair and refreshes it when the generation/device changes.
     */
    static MetalRenderPass.@Nullable TextureViewAndSampler checkedMojangExternalOverlayBinding(
            final MetalDevice device,
            final @Nullable GpuTextureView view,
            final @Nullable GpuSampler sampler
    ) {
        if (!(view instanceof MetalGpuTextureView metalView)
                || !(metalView.texture() instanceof MetalGpuTexture texture)
                || !(sampler instanceof MetalGpuSampler metalSampler)
                || metalView.isClosed()
                || texture.isClosed()
                || metalSampler.isClosed()
                || !texture.isOwnedBy(device)
                || !metalSampler.isOwnedBy(device)
                || (texture.usage() & GpuTexture.USAGE_TEXTURE_BINDING) == 0
                || metalSampler.getAddressModeU() != AddressMode.CLAMP_TO_EDGE
                || metalSampler.getAddressModeV() != AddressMode.CLAMP_TO_EDGE
                || metalSampler.getMinFilter() != FilterMode.LINEAR
                || metalSampler.getMagFilter() != FilterMode.LINEAR) {
            return null;
        }
        return new MetalRenderPass.TextureViewAndSampler(metalView, metalSampler);
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
            IrisMetalRenderTargets.RenderPassDescriptorWithViews descriptor
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
