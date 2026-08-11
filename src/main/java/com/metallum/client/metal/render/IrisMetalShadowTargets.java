package com.metallum.client.metal.render;

import com.metallum.client.metal.render.mtl.MTLCompareFunction;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.util.BitSet;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/** Generation-owned shadowtex0/1 and shadowcolor ping-pong resources. */
@Environment(EnvType.CLIENT)
final class IrisMetalShadowTargets implements AutoCloseable {
    private static final int DEPTH_USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT
            | GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_COPY_SRC
            | GpuTexture.USAGE_COPY_DST;

    private final MetalDevice device;
    private final IrisMetalPingPongTargets colorTargets;
    private final MetalGpuTexture[] colorMain;
    private final MetalGpuTexture[] colorAlt;
    private final MetalGpuTextureView[] colorMainViews;
    private final MetalGpuTextureView[] colorAltViews;
    private final MetalGpuSampler[] colorSamplers;
    private final @Nullable MetalGpuSampler[] passMipSamplers;
    private final MetalGpuSampler[] depthSamplers;
    private final MetalGpuSampler[] depthCompareSamplers;
    private final boolean[] colorMipmapped;
    private final boolean[] depthMipmapped;
    @Nullable
    private final MetalDepthMipmapGenerator depthMipmapGenerator;
    private MetalGpuTexture shadowDepth;
    private MetalGpuTexture shadowDepthNoTranslucents;
    private MetalGpuTextureView shadowDepthView;
    private MetalGpuTextureView shadowDepthNoTranslucentsView;
    private int resolution;
    private boolean closed;

    IrisMetalShadowTargets(
            final MetalDevice device,
            final GpuFormat[] shadowColorFormats,
            final int resolution
    ) {
        this(
                device,
                shadowColorFormats,
                resolution,
                new boolean[shadowColorFormats.length],
                new boolean[shadowColorFormats.length],
                new boolean[2],
                new boolean[2],
                Set.of()
        );
    }

    IrisMetalShadowTargets(
            final MetalDevice device,
            final GpuFormat[] shadowColorFormats,
            final int resolution,
            final boolean[] nearestColor,
            final boolean[] nearestDepth,
            final boolean[] mipmappedDepth
    ) {
        this(
                device,
                shadowColorFormats,
                resolution,
                nearestColor,
                new boolean[shadowColorFormats.length],
                nearestDepth,
                mipmappedDepth,
                Set.of()
        );
    }

    IrisMetalShadowTargets(
            final MetalDevice device,
            final GpuFormat[] shadowColorFormats,
            final int resolution,
            final boolean[] nearestColor,
            final boolean[] colorMipmapped,
            final boolean[] nearestDepth,
            final boolean[] mipmappedDepth
    ) {
        this(
                device,
                shadowColorFormats,
                resolution,
                nearestColor,
                colorMipmapped,
                nearestDepth,
                mipmappedDepth,
                Set.of()
        );
    }

    IrisMetalShadowTargets(
            final MetalDevice device,
            final GpuFormat[] shadowColorFormats,
            final int resolution,
            final boolean[] nearestColor,
            final boolean[] colorMipmapped,
            final boolean[] nearestDepth,
            final boolean[] mipmappedDepth,
            final Set<Integer> storageImageTargets
    ) {
        if (nearestColor.length != shadowColorFormats.length
                || colorMipmapped.length != shadowColorFormats.length) {
            throw new IllegalArgumentException(
                    "One color sampling and mipmap mode is required per shadowcolor target"
            );
        }
        if (nearestDepth.length != 2 || mipmappedDepth.length != 2) {
            throw new IllegalArgumentException("Exactly two shadow depth sampling and mipmap modes are required");
        }
        this.device = device;
        this.colorTargets = new IrisMetalPingPongTargets(
                device, "iris-shadowcolor", shadowColorFormats, resolution, resolution,
                mipmappedIndices(colorMipmapped), storageImageTargets
        );
        this.colorMain = new MetalGpuTexture[shadowColorFormats.length];
        this.colorAlt = new MetalGpuTexture[shadowColorFormats.length];
        this.colorMainViews = new MetalGpuTextureView[shadowColorFormats.length];
        this.colorAltViews = new MetalGpuTextureView[shadowColorFormats.length];
        refreshColorSides();
        this.colorSamplers = new MetalGpuSampler[shadowColorFormats.length];
        this.passMipSamplers = new MetalGpuSampler[shadowColorFormats.length];
        this.colorMipmapped = colorMipmapped.clone();
        for (int index = 0; index < colorSamplers.length; index++) {
            colorSamplers[index] = createSampler(nearestColor[index], colorMipmapped[index], false);
        }
        this.depthSamplers = new MetalGpuSampler[2];
        this.depthCompareSamplers = new MetalGpuSampler[2];
        this.depthMipmapped = mipmappedDepth.clone();
        this.depthMipmapGenerator = mipmappedDepth[0] || mipmappedDepth[1]
                ? new MetalDepthMipmapGenerator(device)
                : null;
        for (int index = 0; index < depthSamplers.length; index++) {
            depthSamplers[index] = createSampler(nearestDepth[index], mipmappedDepth[index], false);
            depthCompareSamplers[index] = createSampler(nearestDepth[index], mipmappedDepth[index], true);
        }
        createDepthTextures(resolution);
    }

    private MetalGpuSampler createSampler(
            final boolean nearest,
            final boolean mipmapped,
            final boolean comparison
    ) {
        FilterMode filter = nearest ? FilterMode.NEAREST : FilterMode.LINEAR;
        return new MetalGpuSampler(
                device,
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                filter,
                filter,
                1,
                mipmapped ? OptionalDouble.empty() : OptionalDouble.of(0.0),
                comparison ? MTLCompareFunction.LessEqual : null
        );
    }

    private static java.util.Set<Integer> mipmappedIndices(final boolean[] mipmapped) {
        java.util.Set<Integer> result = new java.util.HashSet<>();
        for (int index = 0; index < mipmapped.length; index++) {
            if (mipmapped[index]) {
                result.add(index);
            }
        }
        return result;
    }

    private void refreshColorSides() {
        colorTargets.restore(new BitSet());
        for (int index = 0; index < colorTargets.targetCount(); index++) {
            colorMain[index] = colorTargets.readTexture(index);
            colorAlt[index] = colorTargets.writeTexture(index);
            colorMainViews[index] = colorTargets.readView(index);
            colorAltViews[index] = colorTargets.writeView(index);
        }
    }

    private void createDepthTextures(final int newResolution) {
        if (newResolution <= 0) {
            throw new IllegalArgumentException("Shadow resolution must be positive: " + newResolution);
        }
        this.resolution = newResolution;
        this.shadowDepth = (MetalGpuTexture) device.createTexture(
                "iris-shadowtex0", DEPTH_USAGE, GpuFormat.D32_FLOAT, newResolution, newResolution, 1,
                mipLevels(newResolution, depthMipmapped[0])
        );
        this.shadowDepthNoTranslucents = (MetalGpuTexture) device.createTexture(
                "iris-shadowtex1", DEPTH_USAGE, GpuFormat.D32_FLOAT, newResolution, newResolution, 1,
                mipLevels(newResolution, depthMipmapped[1])
        );
        this.shadowDepthView = new MetalGpuTextureView(shadowDepth, 0, shadowDepth.getMipLevels());
        this.shadowDepthNoTranslucentsView = new MetalGpuTextureView(
                shadowDepthNoTranslucents, 0, shadowDepthNoTranslucents.getMipLevels()
        );
    }

    private static int mipLevels(final int extent, final boolean mipmapped) {
        return mipmapped ? 32 - Integer.numberOfLeadingZeros(extent) : 1;
    }

    IrisMetalPingPongTargets colorTargets() {
        ensureOpen();
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

    MetalGpuTextureView shadowDepthView() {
        ensureOpen();
        return shadowDepthView;
    }

    MetalGpuTextureView shadowDepthNoTranslucentsView() {
        ensureOpen();
        return shadowDepthNoTranslucentsView;
    }

    MetalGpuSampler depthSampler(final int index, final boolean comparison) {
        ensureOpen();
        checkDepthIndex(index);
        return comparison ? depthCompareSamplers[index] : depthSamplers[index];
    }

    MetalGpuSampler colorSampler(final int index) {
        ensureOpen();
        return colorSamplers[checkColorIndex(index)];
    }

    /**
     * Selects the sampler required by one shadow-composite pass. A pass may
     * request a mipmap for a target even when the pack's default shadowcolor
     * sampling mode is non-mipmapped; the request is scoped to that pass.
     */
    MetalGpuSampler colorSampler(final int index, final boolean mipmappedForPass) {
        ensureOpen();
        int checked = checkColorIndex(index);
        if (!mipmappedForPass || this.colorMipmapped[checked]) {
            return colorSamplers[checked];
        }
        MetalGpuSampler sampler = this.passMipSamplers[checked];
        if (sampler == null) {
            MetalGpuSampler configured = colorSamplers[checked];
            sampler = new MetalGpuSampler(
                    device,
                    configured.getAddressModeU(),
                    configured.getAddressModeV(),
                    configured.getMinFilter(),
                    configured.getMagFilter(),
                    configured.getMaxAnisotropy(),
                    OptionalDouble.empty()
            );
            this.passMipSamplers[checked] = sampler;
        }
        return sampler;
    }

    void resetMipmaps() {
        ensureOpen();
        colorTargets.resetMipmaps();
    }

    void generateColorMipmaps(final MetalCommandEncoder encoder) {
        ensureOpen();
        for (int index = 0; index < colorMipmapped.length; index++) {
            if (!colorMipmapped[index]) {
                continue;
            }
            encoder.generateMipmaps(colorTargets.readTexture(index));
            colorTargets.enableReadMipmaps(index);
        }
    }

    /**
     * Materializes clears for both sides of every shadow-color target before
     * the scene becomes observable through a sampler. A shadow scene may be
     * depth-only, or may write only one ping-pong side, so relying on a render
     * pass attachment to consume the deferred clear leaves the other side
     * pending until the first main-world sample.
     */
    void materializePendingColorClears(final MetalCommandEncoder encoder) {
        ensureOpen();
        for (int index = 0; index < colorMain.length; index++) {
            encoder.flushPendingClear(colorMain[index]);
            encoder.flushPendingClear(colorAlt[index]);
        }
    }

    /** Generates only the mipmaps requested by the current shadow composite. */
    void generatePassColorMipmaps(
            final MetalCommandEncoder encoder,
            final BitSet readsFromAlt,
            final java.util.Set<Integer> requestedTargets
    ) {
        ensureOpen();
        for (int target : requestedTargets) {
            int checked = checkColorIndex(target);
            MetalGpuTexture texture = colorTexture(checked, readsFromAlt);
            if (texture.getMipLevels() <= 1) {
                throw new IllegalStateException(
                        "Shadow composite requests mipmaps for shadowcolor" + checked
                                + " but the generation allocated one mip level"
                );
            }
            encoder.generateMipmaps(texture);
        }
    }

    GpuFormat colorFormat(final int index) {
        ensureOpen();
        return colorTargets.format(checkColorIndex(index));
    }

    GpuFormat[] colorFormats() {
        ensureOpen();
        GpuFormat[] formats = new GpuFormat[colorTargets.targetCount()];
        for (int index = 0; index < formats.length; index++) {
            formats[index] = colorTargets.format(index);
        }
        return formats;
    }

    MetalGpuTexture colorTexture(final int index, final BitSet readsFromAlt) {
        ensureOpen();
        validateSnapshot(readsFromAlt);
        int checked = checkColorIndex(index);
        return readsFromAlt.get(checked) ? colorAlt[checked] : colorMain[checked];
    }

    MetalGpuTextureView colorView(final int index, final BitSet readsFromAlt) {
        ensureOpen();
        validateSnapshot(readsFromAlt);
        int checked = checkColorIndex(index);
        return readsFromAlt.get(checked) ? colorAltViews[checked] : colorMainViews[checked];
    }

    int resolution() {
        return resolution;
    }

    /** Copies the complete opaque shadow scene into the no-translucents depth target. */
    void captureNoTranslucentsDepth(final MetalCommandEncoder encoder) {
        ensureOpen();
        encoder.copyTextureToTexture(
                shadowDepth, shadowDepthNoTranslucents, 0, 0, 0, 0, 0, resolution, resolution
        );
    }

    void generateDepthMipmaps(final MetalCommandEncoder encoder) {
        ensureOpen();
        if (depthMipmapped[0]) {
            depthMipmapGenerator.generate(encoder, shadowDepth);
        }
        if (depthMipmapped[1]) {
            depthMipmapGenerator.generate(encoder, shadowDepthNoTranslucents);
        }
    }

    IrisMetalRenderTargets.RenderPassDescriptorWithViews createShadowWriteDescriptor(
            final String label,
            final int[] drawBuffers,
            @Nullable final Vector4fc[] clearColors,
            @Nullable final Double clearDepth
    ) {
        return createShadowGbufferDescriptor(label, drawBuffers, clearColors, clearDepth);
    }

    IrisMetalRenderTargets.RenderPassDescriptorWithViews createShadowGbufferDescriptor(
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
        boolean[] written = new boolean[colorTargets.targetCount()];
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            int target = validateDrawTarget(drawBuffers[slot], written, "Shadow DRAWBUFFERS");
            MetalGpuTextureView view = new MetalGpuTextureView(colorMain[target], 0, 1);
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
        return new IrisMetalRenderTargets.RenderPassDescriptorWithViews(
                descriptor,
                views,
                IrisMetalRenderPassMetadata.fromDescriptor(descriptor, drawBuffers)
        );
    }

    IrisMetalRenderTargets.RenderPassDescriptorWithViews createShadowCompositeDescriptor(
            final String label,
            final int[] drawBuffers,
            final BitSet readsFromAlt,
            final int viewportX,
            final int viewportY,
            final int viewportWidth,
            final int viewportHeight
    ) {
        return createShadowCompositeDescriptor(
                label, drawBuffers, readsFromAlt, viewportX, viewportY,
                viewportWidth, viewportHeight, null
        );
    }

    IrisMetalRenderTargets.RenderPassDescriptorWithViews createShadowCompositeDescriptor(
            final String label,
            final int[] drawBuffers,
            final BitSet readsFromAlt,
            final int viewportX,
            final int viewportY,
            final int viewportWidth,
            final int viewportHeight,
            @Nullable final IrisMetalRenderPassMetadata metadata
    ) {
        ensureOpen();
        if (drawBuffers.length == 0) {
            throw new IllegalArgumentException("A shadow composite render pass must write at least one target");
        }
        validateSnapshot(readsFromAlt);
        if (viewportX < 0 || viewportY < 0 || viewportWidth <= 0 || viewportHeight <= 0
                || viewportX + viewportWidth > resolution || viewportY + viewportHeight > resolution) {
            throw new IllegalArgumentException(
                    "Shadow composite viewport is outside " + resolution + "x" + resolution + ": "
                            + viewportX + "," + viewportY + " " + viewportWidth + "x" + viewportHeight
            );
        }
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> label);
        MetalGpuTextureView[] views = new MetalGpuTextureView[drawBuffers.length];
        boolean[] written = new boolean[colorTargets.targetCount()];
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            int target = validateDrawTarget(drawBuffers[slot], written, "Shadow composite DRAWBUFFERS");
            MetalGpuTexture destination = readsFromAlt.get(target) ? colorMain[target] : colorAlt[target];
            MetalGpuTextureView view = new MetalGpuTextureView(destination, 0, 1);
            views[slot] = view;
            descriptor.withColorAttachment(view, Optional.empty());
        }
        descriptor.withRenderArea(new RenderPass.RenderArea(
                viewportX, viewportY, viewportWidth, viewportHeight
        ));
        return new IrisMetalRenderTargets.RenderPassDescriptorWithViews(descriptor, views, metadata);
    }

    void publishFlipState(final BitSet finalReadsFromAlt) {
        ensureOpen();
        validateSnapshot(finalReadsFromAlt);
        colorTargets.restore(finalReadsFromAlt);
    }

    void resize(final int newResolution) {
        ensureOpen();
        if (newResolution == resolution) {
            return;
        }
        colorTargets.resize(newResolution, newResolution);
        refreshColorSides();
        releaseDepthTextures();
        createDepthTextures(newResolution);
    }

    private void releaseDepthTextures() {
        if (shadowDepthView != null) {
            shadowDepthView.close();
            shadowDepthView = null;
        }
        if (shadowDepthNoTranslucentsView != null) {
            shadowDepthNoTranslucentsView.close();
            shadowDepthNoTranslucentsView = null;
        }
        if (shadowDepth != null) {
            shadowDepth.close();
            shadowDepth = null;
        }
        if (shadowDepthNoTranslucents != null) {
            shadowDepthNoTranslucents.close();
            shadowDepthNoTranslucents = null;
        }
    }

    private int validateDrawTarget(final int target, final boolean[] written, final String label) {
        int checked = checkColorIndex(target);
        if (written[checked]) {
            throw new IllegalArgumentException(label + " repeats logical target " + checked);
        }
        written[checked] = true;
        return checked;
    }

    private int checkColorIndex(final int index) {
        if (index < 0 || index >= colorTargets.targetCount()) {
            throw new IllegalArgumentException(
                    "Shadow color target out of range: " + index + " (count=" + colorTargets.targetCount() + ")"
            );
        }
        return index;
    }

    private static void checkDepthIndex(final int index) {
        if (index < 0 || index > 1) {
            throw new IllegalArgumentException("Shadow depth target out of range: " + index);
        }
    }

    private void validateSnapshot(final BitSet snapshot) {
        int invalid = snapshot.nextSetBit(colorTargets.targetCount());
        if (invalid >= 0) {
            throw new IllegalArgumentException(
                    "Shadow flip snapshot contains target " + invalid + " outside target set"
            );
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
        for (MetalGpuSampler sampler : colorSamplers) {
            sampler.close();
        }
        for (MetalGpuSampler sampler : passMipSamplers) {
            if (sampler != null) {
                sampler.close();
            }
        }
        for (MetalGpuSampler sampler : depthSamplers) {
            sampler.close();
        }
        for (MetalGpuSampler sampler : depthCompareSamplers) {
            sampler.close();
        }
        if (depthMipmapGenerator != null) {
            depthMipmapGenerator.close();
        }
    }
}
