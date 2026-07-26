package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Metal-side equivalent of Iris {@code shadows.ShadowRenderTargets}:
 * shadowtex0 (all shadow geometry) / shadowtex1 (no translucents) depth maps
 * plus the flip-aware shadowcolor ping-pong set. Resolution is square and
 * owned by the shader pack's shadow directives, independent of the screen —
 * {@link #resize(int)} rebuilds on pack-config change only.
 *
 * <p>State isolation contract: shadow passes encode into their own
 * render-pass descriptors over these textures and never touch the main
 * {@link IrisMetalRenderTargets}; the shared encoder fence chain still orders
 * shadow writes before main-pass shadow sampling.</p>
 */
@Environment(EnvType.CLIENT)
final class IrisMetalShadowTargets implements AutoCloseable {
    private static final int DEPTH_USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT
            | GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_COPY_SRC
            | GpuTexture.USAGE_COPY_DST;

    private final MetalDevice device;
    private final IrisMetalPingPongTargets colorTargets;
    private MetalGpuTexture shadowDepth;
    private MetalGpuTexture shadowDepthNoTranslucents;
    private int resolution;
    private boolean closed;

    IrisMetalShadowTargets(
            final MetalDevice device,
            final GpuFormat[] shadowColorFormats,
            final int resolution
    ) {
        this.device = device;
        this.colorTargets = new IrisMetalPingPongTargets(
                device, "iris-shadowcolor", shadowColorFormats, resolution, resolution);
        createDepthTextures(resolution);
    }

    private void createDepthTextures(final int newResolution) {
        if (newResolution <= 0) {
            throw new IllegalArgumentException("Shadow resolution must be positive: " + newResolution);
        }
        this.resolution = newResolution;
        this.shadowDepth = (MetalGpuTexture) device.createTexture(
                "iris-shadowtex0", DEPTH_USAGE, GpuFormat.D32_FLOAT, newResolution, newResolution, 1, 1);
        this.shadowDepthNoTranslucents = (MetalGpuTexture) device.createTexture(
                "iris-shadowtex1", DEPTH_USAGE, GpuFormat.D32_FLOAT, newResolution, newResolution, 1, 1);
    }

    IrisMetalPingPongTargets colorTargets() {
        return colorTargets;
    }

    MetalGpuTexture shadowDepthTexture() {
        ensureOpen();
        return shadowDepth;
    }

    MetalGpuTexture shadowDepthNoTranslucentsTexture() {
        ensureOpen();
        return shadowDepthNoTranslucents;
    }

    int resolution() {
        return resolution;
    }

    /** shadowtex1 capture point: after opaque shadow casters, before translucents. */
    void captureNoTranslucentsDepth(final MetalCommandEncoder encoder) {
        ensureOpen();
        encoder.copyTextureToTexture(
                shadowDepth, shadowDepthNoTranslucents, 0, 0, 0, 0, 0, resolution, resolution);
    }

    /**
     * Descriptor for a shadow pass writing the given shadowcolor draw buffers
     * (compact slots, write side of the flip) plus shadowtex0 as depth.
     */
    IrisMetalRenderTargets.RenderPassDescriptorWithViews createShadowWriteDescriptor(
            final String label,
            final int[] drawBuffers,
            @Nullable final Vector4fc[] clearColors,
            @Nullable final Double clearDepth
    ) {
        ensureOpen();
        if (clearColors != null && clearColors.length != drawBuffers.length) {
            throw new IllegalArgumentException("Clear color array must match draw buffer count");
        }
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> label);
        MetalGpuTextureView[] views = new MetalGpuTextureView[drawBuffers.length + 1];
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            MetalGpuTextureView view = new MetalGpuTextureView(colorTargets.writeTexture(drawBuffers[slot]), 0, 1);
            views[slot] = view;
            descriptor.withColorAttachment(
                    view,
                    clearColors == null || clearColors[slot] == null
                            ? Optional.empty()
                            : Optional.of(clearColors[slot])
            );
        }
        MetalGpuTextureView depthView = new MetalGpuTextureView(shadowDepth, 0, 1);
        views[drawBuffers.length] = depthView;
        descriptor.withDepthAttachment(
                depthView,
                clearDepth == null ? OptionalDouble.empty() : OptionalDouble.of(clearDepth)
        );
        descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, resolution, resolution));
        return new IrisMetalRenderTargets.RenderPassDescriptorWithViews(descriptor, views);
    }

    /** Rebuilds all shadow textures at the pack-configured resolution. */
    void resize(final int newResolution) {
        ensureOpen();
        if (newResolution == resolution) {
            return;
        }
        colorTargets.resize(newResolution, newResolution);
        releaseDepthTextures();
        createDepthTextures(newResolution);
    }

    private void releaseDepthTextures() {
        if (shadowDepth != null) {
            shadowDepth.close();
            shadowDepth = null;
        }
        if (shadowDepthNoTranslucents != null) {
            shadowDepthNoTranslucents.close();
            shadowDepthNoTranslucents = null;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Iris shadow targets are closed");
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
}
