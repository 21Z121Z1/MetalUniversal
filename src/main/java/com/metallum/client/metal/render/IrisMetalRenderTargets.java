package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.util.BitSet;
import java.util.Optional;
import java.util.OptionalDouble;

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
    private MetalGpuTexture mainDepth;
    private MetalGpuTexture noTranslucentsDepth;
    private MetalGpuTexture noHandDepth;
    private int width;
    private int height;
    private boolean closed;

    IrisMetalRenderTargets(
            final MetalDevice device,
            final GpuFormat[] colorFormats,
            final int width,
            final int height
    ) {
        this.device = device;
        this.colorTargets = new IrisMetalPingPongTargets(device, "iris-colortex", colorFormats, width, height);
        createDepthTextures(width, height);
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

    /** depthtex2 capture point: call after translucents, before hand. */
    void captureNoHandDepth(final MetalCommandEncoder encoder) {
        ensureOpen();
        encoder.copyTextureToTexture(mainDepth, noHandDepth, 0, 0, 0, 0, 0, width, height);
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
    }

    private void releaseDepthTextures() {
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
