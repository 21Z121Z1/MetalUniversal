package com.metallum.client.metal.render;

import com.metallum.client.metal.render.mtl.MTLSamplerMipFilter;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives.RenderTargetSettings;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.util.BitSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Metal-side equivalent of Iris {@code targets.RenderTargets}: the colortexN
 * main/alt ping-pong set plus the three world depth textures
 * (main depth / no-translucents / no-hand — Iris depthtex0/1/2 semantics) and
 * the framebuffer factory that maps Iris draw-buffer directives onto
 * {@link RenderPassDescriptor} attachment lists.
 *
 * <p>Draw-buffer mapping: an Iris pass writing {@code DRAWBUFFERS:025} routes
 * fragment output location k to logical target {@code drawBuffers[k]}; here
 * that becomes a COMPACT descriptor whose attachment slot k is the write-side
 * texture of {@code drawBuffers[k]} — matching GL's
 * {@code glDrawBuffers(new int[]{A0, A2, A5})} routing, where the shader's
 * sequential outputs land on the listed attachments in order.</p>
 *
 * <p>Depth-copy semantics: {@link #captureNoTranslucentsDepth} must be called
 * after opaque geometry (depthtex1 excludes translucents), and
 * {@link #captureNoHandDepth} after translucents but before hand rendering
 * (depthtex2 excludes the hand). Both are full-texture GPU copies inside the
 * encoder fence chain — no CPU readback.</p>
 *
 * <p>Lifecycle: {@link #resize(int, int)} rebuilds every texture and resets
 * flip state; {@link #close()} releases everything. Render-thread only.</p>
 */
@Environment(EnvType.CLIENT)
final class IrisMetalRenderTargets implements AutoCloseable {
    private static final int DEPTH_USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT
            | GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_COPY_SRC
            | GpuTexture.USAGE_COPY_DST;

    private final MetalDevice device;
    private final IrisMetalPingPongTargets colorTargets;
    private final Map<Integer, RenderTargetSettings> targetSettings;
    private MetalGpuTexture mainDepth;
    private MetalGpuTexture noTranslucentsDepth;
    private MetalGpuTexture noHandDepth;
    private MetalGpuTextureView mainDepthView;
    private MetalGpuTextureView noTranslucentsDepthView;
    private MetalGpuTextureView noHandDepthView;
    private final MetalGpuSampler colorSampler;
    private final MetalGpuSampler nearestSampler;
    private final MetalGpuSampler colorMipSampler;
    private final MetalGpuSampler nearestMipSampler;
    private int width;
    private int height;
    private boolean fullClearRequired = true;
    private boolean closed;

    IrisMetalRenderTargets(
            final MetalDevice device,
            final GpuFormat[] colorFormats,
            final int width,
            final int height
    ) {
        this(device, colorFormats, width, height, Map.of(), Set.of());
    }

    IrisMetalRenderTargets(
            final MetalDevice device,
            final GpuFormat[] colorFormats,
            final int width,
            final int height,
            final Map<Integer, RenderTargetSettings> targetSettings
    ) {
        this(device, colorFormats, width, height, targetSettings, Set.of());
    }

    IrisMetalRenderTargets(
            final MetalDevice device,
            final GpuFormat[] colorFormats,
            final int width,
            final int height,
            final Map<Integer, RenderTargetSettings> targetSettings,
            final Set<Integer> mipmappedTargets
    ) {
        this.device = device;
        this.colorTargets = new IrisMetalPingPongTargets(
                device, "iris-colortex", colorFormats, width, height, mipmappedTargets
        );
        this.targetSettings = Map.copyOf(targetSettings);
        this.colorSampler = new MetalGpuSampler(
                device,
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR,
                FilterMode.LINEAR,
                1,
                OptionalDouble.empty(),
                null,
                MTLSamplerMipFilter.NotMipmapped
        );
        this.nearestSampler = new MetalGpuSampler(
                device,
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST,
                FilterMode.NEAREST,
                1,
                OptionalDouble.empty(),
                null,
                MTLSamplerMipFilter.NotMipmapped
        );
        this.colorMipSampler = new MetalGpuSampler(
                device,
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR,
                FilterMode.LINEAR,
                1,
                OptionalDouble.empty(),
                null,
                MTLSamplerMipFilter.Linear
        );
        this.nearestMipSampler = new MetalGpuSampler(
                device,
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST,
                FilterMode.NEAREST,
                1,
                OptionalDouble.empty(),
                null,
                MTLSamplerMipFilter.Linear
        );
        createDepthTextures(width, height);
    }

    /**
     * Applies Iris's per-frame render-target clear contract to both physical
     * sides. Newly allocated or resized targets are fully initialized once;
     * later frames clear only targets whose pack directive keeps clearing on.
     */
    boolean clearForFrame(final MetalCommandEncoder encoder, final Vector4fc fogColor) {
        ensureOpen();
        Vector4f fog = new Vector4f(fogColor.x(), fogColor.y(), fogColor.z(), 1.0F);
        boolean fullClear = this.fullClearRequired;
        for (int index = 0; index < colorTargets.targetCount(); index++) {
            RenderTargetSettings settings = targetSettings.get(index);
            if (!fullClear && (settings == null || !settings.shouldClear())) {
                continue;
            }
            Vector4fc clear = settings == null || settings.getClearColor().isEmpty()
                    ? defaultClearColor(index, fog)
                    : settings.getClearColor().get();
            encoder.clearColorTexture(colorTargets.mainTexture(index), clear);
            encoder.clearColorTexture(colorTargets.altTexture(index), clear);
        }
        this.fullClearRequired = false;
        return fullClear;
    }

    private static Vector4f defaultClearColor(final int index, final Vector4fc fogColor) {
        if (index == 0) {
            return new Vector4f(fogColor);
        }
        if (index == 1) {
            return new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
        }
        return new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);
    }

    private void createDepthTextures(final int newWidth, final int newHeight) {
        this.width = newWidth;
        this.height = newHeight;
        this.mainDepth = (MetalGpuTexture) device.createTexture(
                "iris-depthtex0", DEPTH_USAGE, GpuFormat.D32_FLOAT, newWidth, newHeight, 1, 1);
        this.noTranslucentsDepth = (MetalGpuTexture) device.createTexture(
                "iris-depthtex1", DEPTH_USAGE, GpuFormat.D32_FLOAT, newWidth, newHeight, 1, 1);
        this.noHandDepth = (MetalGpuTexture) device.createTexture(
                "iris-depthtex2", DEPTH_USAGE, GpuFormat.D32_FLOAT, newWidth, newHeight, 1, 1);
        this.mainDepthView = new MetalGpuTextureView(this.mainDepth, 0, 1);
        this.noTranslucentsDepthView = new MetalGpuTextureView(this.noTranslucentsDepth, 0, 1);
        this.noHandDepthView = new MetalGpuTextureView(this.noHandDepth, 0, 1);
    }

    IrisMetalPingPongTargets colorTargets() {
        return colorTargets;
    }

    MetalGpuTexture mainDepthTexture() {
        ensureOpen();
        return mainDepth;
    }

    MetalGpuTexture noTranslucentsDepthTexture() {
        ensureOpen();
        return noTranslucentsDepth;
    }

    MetalGpuTexture noHandDepthTexture() {
        ensureOpen();
        return noHandDepth;
    }

    MetalGpuTextureView mainDepthView() {
        ensureOpen();
        return mainDepthView;
    }

    MetalGpuTextureView noTranslucentsDepthView() {
        ensureOpen();
        return noTranslucentsDepthView;
    }

    MetalGpuTextureView noHandDepthView() {
        ensureOpen();
        return noHandDepthView;
    }

    GpuSampler colorSampler() {
        ensureOpen();
        return colorSampler;
    }

    /** Iris uses nearest filtering for integer render targets. */
    GpuSampler colorSampler(final int logicalTarget) {
        ensureOpen();
        String componentType = colorTargets.format(logicalTarget).componentType().name();
        boolean nearest = componentType.startsWith("UINT") || componentType.startsWith("SINT");
        boolean mipmapped = colorTargets.readMipmapsEnabled(logicalTarget);
        if (nearest) {
            return mipmapped ? nearestMipSampler : nearestSampler;
        }
        return mipmapped ? colorMipSampler : colorSampler;
    }

    void enableReadMipmaps(final int logicalTarget) {
        ensureOpen();
        colorTargets.enableReadMipmaps(logicalTarget);
    }

    void resetMipmaps() {
        ensureOpen();
        colorTargets.resetMipmaps();
    }

    GpuSampler depthSampler() {
        ensureOpen();
        return nearestSampler;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /** depthtex1 capture point: call after opaque, before translucents. */
    void captureNoTranslucentsDepth(final MetalCommandEncoder encoder) {
        ensureOpen();
        encoder.copyTextureToTexture(mainDepth, noTranslucentsDepth, 0, 0, 0, 0, 0, width, height);
    }

    /** Captures depthtex1 from the live Minecraft scene depth attachment. */
    void captureNoTranslucentsDepth(final MetalCommandEncoder encoder, final GpuTexture sourceDepth) {
        ensureOpen();
        checkDepthExtent(sourceDepth);
        encoder.copyTextureToTexture(sourceDepth, noTranslucentsDepth, 0, 0, 0, 0, 0, width, height);
    }

    /** depthtex2 capture point: call after translucents, before hand. */
    void captureNoHandDepth(final MetalCommandEncoder encoder) {
        ensureOpen();
        encoder.copyTextureToTexture(mainDepth, noHandDepth, 0, 0, 0, 0, 0, width, height);
    }

    /** Captures depthtex2 from the live Minecraft scene depth attachment. */
    void captureNoHandDepth(final MetalCommandEncoder encoder, final GpuTexture sourceDepth) {
        ensureOpen();
        checkDepthExtent(sourceDepth);
        encoder.copyTextureToTexture(sourceDepth, noHandDepth, 0, 0, 0, 0, 0, width, height);
    }

    private void checkDepthExtent(final GpuTexture sourceDepth) {
        if (sourceDepth.getWidth(0) != width || sourceDepth.getHeight(0) != height) {
            throw new IllegalArgumentException(
                    "Scene depth extent " + sourceDepth.getWidth(0) + "x" + sourceDepth.getHeight(0)
                            + " does not match Iris targets " + width + "x" + height
            );
        }
    }

    /**
     * Builds a render-pass descriptor for a pass writing the given logical
     * draw buffers (write-side textures at compact attachment slots), with
     * optional per-slot clears, an optional depth attachment on the main
     * depth texture and an optional read-set feedback guard.
     */
    RenderPassDescriptorWithViews createWriteDescriptor(
            final String label,
            final int[] drawBuffers,
            @Nullable final Vector4fc[] clearColors,
            final boolean withDepth,
            @Nullable final Double clearDepth,
            final int @Nullable [] readTargets
    ) {
        ensureOpen();
        if (drawBuffers.length == 0) {
            throw new IllegalArgumentException("A pass must write at least one draw buffer");
        }
        if (clearColors != null && clearColors.length != drawBuffers.length) {
            throw new IllegalArgumentException("Clear color array must match draw buffer count");
        }
        if (readTargets != null) {
            colorTargets.checkNoFeedbackLoop(drawBuffers, readTargets);
        }
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> label);
        MetalGpuTextureView[] views = new MetalGpuTextureView[drawBuffers.length + (withDepth ? 1 : 0)];
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            MetalGpuTexture texture = colorTargets.writeTexture(drawBuffers[slot]);
            MetalGpuTextureView view = new MetalGpuTextureView(texture, 0, 1);
            views[slot] = view;
            descriptor.withColorAttachment(
                    view,
                    clearColors == null || clearColors[slot] == null
                            ? Optional.empty()
                            : Optional.of(clearColors[slot])
            );
        }
        if (withDepth) {
            MetalGpuTextureView depthView = new MetalGpuTextureView(mainDepth, 0, 1);
            views[drawBuffers.length] = depthView;
            descriptor.withDepthAttachment(
                    depthView,
                    clearDepth == null ? OptionalDouble.empty() : OptionalDouble.of(clearDepth)
            );
        }
        descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, width, height));
        return new RenderPassDescriptorWithViews(descriptor, views);
    }

    /**
     * Builds a gbuffer descriptor for a compact Iris DRAWBUFFERS list.
     * Gbuffer programs write the side that is currently readable at their
     * stage snapshot: main when unflipped, alt when flipped. They do not perform
     * the write-opposite-then-flip transition used by composite passes.
     *
     * <p>Every logical target, including colortex0, belongs to this generation.
     * The live scene color supplied by Minecraft is used only to verify the
     * render extent; Iris's final pass is responsible for resolving colortex0
     * to that scene target. Persistent views are owned until resize/reload.</p>
     */
    RenderPassDescriptor createTerrainWriteDescriptor(
            final String label,
            final int[] drawBuffers,
            final GpuTextureView mainColor,
            @Nullable final Vector4fc mainClearColor,
            @Nullable final GpuTextureView sceneDepth,
            @Nullable final Double clearDepth
    ) {
        ensureOpen();
        if (drawBuffers.length == 0) {
            throw new IllegalArgumentException("A gbuffer pass must write at least one draw buffer");
        }
        if (mainColor.getWidth(0) != width || mainColor.getHeight(0) != height) {
            throw new IllegalArgumentException(
                    "Scene color extent " + mainColor.getWidth(0) + "x" + mainColor.getHeight(0)
                            + " does not match Iris targets " + width + "x" + height
            );
        }
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> label);
        boolean[] written = new boolean[colorTargets.targetCount()];
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            int logicalTarget = drawBuffers[slot];
            if (logicalTarget < 0 || logicalTarget >= colorTargets.targetCount()) {
                throw new IllegalArgumentException("Terrain DRAWBUFFERS target out of range: " + logicalTarget);
            }
            if (written[logicalTarget]) {
                throw new IllegalArgumentException("Terrain DRAWBUFFERS repeats logical target " + logicalTarget);
            }
            written[logicalTarget] = true;

            GpuTextureView view = colorTargets.readView(logicalTarget);
            Optional<Vector4fc> clear = Optional.empty();
            if (logicalTarget == 0) {
                if (mainClearColor != null) {
                    clear = Optional.of(mainClearColor);
                }
            }
            descriptor.withColorAttachment(view, clear);
        }
        if (sceneDepth != null) {
            descriptor.withDepthAttachment(
                    sceneDepth,
                    clearDepth == null ? OptionalDouble.empty() : OptionalDouble.of(clearDepth)
            );
        }
        descriptor.withRenderArea(new RenderPass.RenderArea(
                0, 0, width, height
        ));
        return descriptor;
    }

    /**
     * Rebuilds every color and depth texture at the new extent. Flip state
     * resets; previous contents are gone by contract.
     */
    void resize(final int newWidth, final int newHeight) {
        ensureOpen();
        if (newWidth == width && newHeight == height) {
            return;
        }
        colorTargets.resize(newWidth, newHeight);
        releaseDepthTextures();
        createDepthTextures(newWidth, newHeight);
        this.fullClearRequired = true;
    }

    private void releaseDepthTextures() {
        if (mainDepthView != null) {
            mainDepthView.close();
            mainDepthView = null;
        }
        if (noTranslucentsDepthView != null) {
            noTranslucentsDepthView.close();
            noTranslucentsDepthView = null;
        }
        if (noHandDepthView != null) {
            noHandDepthView.close();
            noHandDepthView = null;
        }
        if (mainDepth != null) {
            mainDepth.close();
            mainDepth = null;
        }
        if (noTranslucentsDepth != null) {
            noTranslucentsDepth.close();
            noTranslucentsDepth = null;
        }
        if (noHandDepth != null) {
            noHandDepth.close();
            noHandDepth = null;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Iris render targets are closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        colorTargets.close();
        releaseDepthTextures();
        colorSampler.close();
        nearestSampler.close();
        colorMipSampler.close();
        nearestMipSampler.close();
    }

    /**
     * A descriptor plus the views it references; the caller must keep the
     * views alive until the pass is submitted and then {@link #close()} them.
     */
    record RenderPassDescriptorWithViews(
            RenderPassDescriptor descriptor,
            MetalGpuTextureView[] views
    ) implements AutoCloseable {
        @Override
        public void close() {
            for (MetalGpuTextureView view : views) {
                if (view != null) {
                    view.close();
                }
            }
        }
    }
}
