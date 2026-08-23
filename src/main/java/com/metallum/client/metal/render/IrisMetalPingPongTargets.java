package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.BitSet;
import java.util.Objects;
import java.util.Set;

/**
 * Core main/alt ping-pong target array with Iris {@code BufferFlipper}
 * semantics, shared by the colortex and shadowcolor target sets.
 *
 * <p>Contract (mirrors Iris {@code RenderTargets} + {@code BufferFlipper}):
 * each logical target owns two textures. When a target is NOT flipped, reads
 * sample {@code main} and writes land in {@code alt}; {@link #flip(int)}
 * swaps the roles so the freshly written side becomes readable for the next
 * pass. {@link #snapshot()} captures the flip set for framebuffer-cache keys
 * ({@code GlFramebuffer} rebuild driver in Iris); {@link #restore(BitSet)}
 * rewinds to a snapshot (explicit flip / pre-flip directives).</p>
 *
 * <p>Ownership: this class owns every texture it creates and releases them in
 * {@link #close()} / {@link #resize(int, int)}. Render-thread only, like the
 * rest of the backend. Textures are created with RENDER_ATTACHMENT +
 * TEXTURE_BINDING + COPY_SRC + COPY_DST so they can be attached, sampled,
 * copied (final-pass swap chains) and read back by validation.</p>
 */
@Environment(EnvType.CLIENT)
final class IrisMetalPingPongTargets implements AutoCloseable {
    static final int TEXTURE_USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT
            | GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_COPY_SRC
            | GpuTexture.USAGE_COPY_DST;

    private final MetalDevice device;
    private final String labelPrefix;
    private final GpuFormat[] formats;
    private MetalGpuTexture[] main;
    private MetalGpuTexture[] alt;
    private MetalGpuTextureView[] mainViews;
    private MetalGpuTextureView[] altViews;
    private MetalGpuTextureView[] mainSampleViews;
    private MetalGpuTextureView[] altSampleViews;
    private final BitSet flipped;
    private final BitSet flippedAtLeastOnce;
    private final BitSet mipmappedTargets;
    private final BitSet storageImageTargets;
    private final BitSet alphaOneSampleTargets;
    private final BitSet mipmapsOnMain;
    private final BitSet mipmapsOnAlt;
    private int width;
    private int height;
    private boolean closed;

    IrisMetalPingPongTargets(
            final MetalDevice device,
            final String labelPrefix,
            final GpuFormat[] formats,
            final int width,
            final int height
    ) {
        this(device, labelPrefix, formats, width, height, Set.of());
    }

    IrisMetalPingPongTargets(
            final MetalDevice device,
            final String labelPrefix,
            final GpuFormat[] formats,
            final int width,
            final int height,
            final Set<Integer> mipmappedTargets
    ) {
        this(device, labelPrefix, formats, width, height, mipmappedTargets, Set.of(), Set.of());
    }

    IrisMetalPingPongTargets(
            final MetalDevice device,
            final String labelPrefix,
            final GpuFormat[] formats,
            final int width,
            final int height,
            final Set<Integer> mipmappedTargets,
            final Set<Integer> storageImageTargets
    ) {
        this(
                device, labelPrefix, formats, width, height,
                mipmappedTargets, storageImageTargets, Set.of()
        );
    }

    IrisMetalPingPongTargets(
            final MetalDevice device,
            final String labelPrefix,
            final GpuFormat[] formats,
            final int width,
            final int height,
            final Set<Integer> mipmappedTargets,
            final Set<Integer> storageImageTargets,
            final Set<Integer> alphaOneSampleTargets
    ) {
        if (formats.length == 0) {
            throw new IllegalArgumentException("At least one logical target is required");
        }
        this.device = device;
        this.labelPrefix = labelPrefix;
        this.formats = formats.clone();
        this.flipped = new BitSet(formats.length);
        this.flippedAtLeastOnce = new BitSet(formats.length);
        this.mipmappedTargets = validatedTargets(
                Objects.requireNonNull(mipmappedTargets, "mipmappedTargets"), formats.length
        );
        this.storageImageTargets = validatedTargets(
                Objects.requireNonNull(storageImageTargets, "storageImageTargets"), formats.length
        );
        this.alphaOneSampleTargets = validatedTargets(
                Objects.requireNonNull(alphaOneSampleTargets, "alphaOneSampleTargets"), formats.length
        );
        this.mipmapsOnMain = new BitSet(formats.length);
        this.mipmapsOnAlt = new BitSet(formats.length);
        createTextures(width, height);
    }

    private void createTextures(final int newWidth, final int newHeight) {
        if (newWidth <= 0 || newHeight <= 0) {
            throw new IllegalArgumentException("Target extent must be positive: " + newWidth + "x" + newHeight);
        }
        this.width = newWidth;
        this.height = newHeight;
        this.main = new MetalGpuTexture[formats.length];
        this.alt = new MetalGpuTexture[formats.length];
        this.mainViews = new MetalGpuTextureView[formats.length];
        this.altViews = new MetalGpuTextureView[formats.length];
        this.mainSampleViews = new MetalGpuTextureView[formats.length];
        this.altSampleViews = new MetalGpuTextureView[formats.length];
        for (int index = 0; index < formats.length; index++) {
            int mipLevels = this.mipmappedTargets.get(index)
                    ? fullMipLevelCount(newWidth, newHeight)
                    : 1;
            int usage = TEXTURE_USAGE | (this.storageImageTargets.get(index)
                    ? MetalGpuTexture.USAGE_SHADER_WRITE
                    : 0);
            main[index] = (MetalGpuTexture) device.createTexture(
                    labelPrefix + index + "-main", usage, formats[index], newWidth, newHeight, 1, mipLevels);
            alt[index] = (MetalGpuTexture) device.createTexture(
                    labelPrefix + index + "-alt", usage, formats[index], newWidth, newHeight, 1, mipLevels);
            main[index].registerValidationIdentity();
            alt[index].registerValidationIdentity();
            mainViews[index] = new MetalGpuTextureView(main[index], 0, mipLevels);
            altViews[index] = new MetalGpuTextureView(alt[index], 0, mipLevels);
            if (this.alphaOneSampleTargets.get(index)) {
                mainSampleViews[index] = new MetalGpuTextureView(main[index], 0, mipLevels, true);
                altSampleViews[index] = new MetalGpuTextureView(alt[index], 0, mipLevels, true);
            }
        }
    }

    int targetCount() {
        return formats.length;
    }

    GpuFormat format(final int index) {
        return formats[checkIndex(index)];
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /** Texture the NEXT pass should sample for this logical target. */
    MetalGpuTexture readTexture(final int index) {
        ensureOpen();
        return flipped.get(checkIndex(index)) ? alt[index] : main[index];
    }

    /** Texture the CURRENT pass should write for this logical target. */
    MetalGpuTexture writeTexture(final int index) {
        ensureOpen();
        return flipped.get(checkIndex(index)) ? main[index] : alt[index];
    }

    /** Fixed main variant, independent of the logical flip state. */
    MetalGpuTexture mainTexture(final int index) {
        ensureOpen();
        return main[checkIndex(index)];
    }

    /** Fixed alternate variant, independent of the logical flip state. */
    MetalGpuTexture altTexture(final int index) {
        ensureOpen();
        return alt[checkIndex(index)];
    }

    /** Persistent view for the texture the next pass should sample. */
    MetalGpuTextureView readView(final int index) {
        ensureOpen();
        return flipped.get(checkIndex(index)) ? altViews[index] : mainViews[index];
    }

    /** Persistent view for the texture the current pass should write. */
    MetalGpuTextureView writeView(final int index) {
        ensureOpen();
        return flipped.get(checkIndex(index)) ? mainViews[index] : altViews[index];
    }

    /** Sampled view of the current read side, including logical format swizzles. */
    MetalGpuTextureView sampleReadView(final int index) {
        ensureOpen();
        int checked = checkIndex(index);
        if (!this.alphaOneSampleTargets.get(checked)) {
            return this.flipped.get(checked) ? altViews[checked] : mainViews[checked];
        }
        return this.flipped.get(checked) ? altSampleViews[checked] : mainSampleViews[checked];
    }

    /** Sampled view of the current write/history side, including logical format swizzles. */
    MetalGpuTextureView sampleWriteView(final int index) {
        ensureOpen();
        int checked = checkIndex(index);
        if (!this.alphaOneSampleTargets.get(checked)) {
            return this.flipped.get(checked) ? mainViews[checked] : altViews[checked];
        }
        return this.flipped.get(checked) ? mainSampleViews[checked] : altSampleViews[checked];
    }

    /** Marks the currently readable physical side as mip-enabled for this frame. */
    void enableReadMipmaps(final int index) {
        ensureOpen();
        int checked = checkIndex(index);
        if (!this.mipmappedTargets.get(checked)) {
            throw new IllegalStateException(
                    "Logical target " + checked + " was not allocated with a mip chain"
            );
        }
        if (this.flipped.get(checked)) {
            this.mipmapsOnAlt.set(checked);
        } else {
            this.mipmapsOnMain.set(checked);
        }
    }

    /** Whether the currently readable physical side should use a mip sampler. */
    boolean readMipmapsEnabled(final int index) {
        ensureOpen();
        int checked = checkIndex(index);
        return this.flipped.get(checked)
                ? this.mipmapsOnAlt.get(checked)
                : this.mipmapsOnMain.get(checked);
    }

    /** Iris resets both physical-side sampler modes after the final pass. */
    void resetMipmaps() {
        ensureOpen();
        this.mipmapsOnMain.clear();
        this.mipmapsOnAlt.clear();
    }

    void flip(final int index) {
        ensureOpen();
        flipped.flip(checkIndex(index));
        flippedAtLeastOnce.set(index);
    }

    boolean isFlipped(final int index) {
        return flipped.get(checkIndex(index));
    }

    boolean flippedAtLeastOnce(final int index) {
        return flippedAtLeastOnce.get(checkIndex(index));
    }

    /** Immutable copy of the current flip set (framebuffer cache key). */
    BitSet snapshot() {
        ensureOpen();
        return (BitSet) flipped.clone();
    }

    /** Rewinds the flip set to a snapshot (explicit-flip directives). */
    void restore(final BitSet snapshot) {
        ensureOpen();
        flipped.clear();
        flipped.or(snapshot);
    }

    /**
     * Guard for Iris's illegal same-texture feedback rule: a pass may not
     * sample a logical target it is also writing without a flip in between,
     * because both would resolve to the same underlying texture only when the
     * flip state is inconsistent — here both sides are distinct textures, so
     * the illegal case is precisely "read index also being written".
     */
    void checkNoFeedbackLoop(final int[] writeTargets, final int[] readTargets) {
        for (int write : writeTargets) {
            for (int read : readTargets) {
                if (write == read) {
                    throw new IllegalStateException(
                            "Pass reads and writes logical target " + write
                                    + " without an intervening flip (feedback loop)"
                    );
                }
            }
        }
    }

    /**
     * Destroys and recreates every texture at the new extent. Flip state and
     * history reset — after a resize no pass may assume previous contents,
     * mirroring Iris's full target rebuild on resolution change.
     */
    void resize(final int newWidth, final int newHeight) {
        ensureOpen();
        if (newWidth == width && newHeight == height) {
            return;
        }
        releaseTextures();
        flipped.clear();
        flippedAtLeastOnce.clear();
        resetMipmaps();
        createTextures(newWidth, newHeight);
    }

    private static BitSet validatedTargets(final Set<Integer> targets, final int targetCount) {
        BitSet validated = new BitSet(targetCount);
        for (Integer target : targets) {
            if (target == null || target < 0 || target >= targetCount) {
                throw new IllegalArgumentException("Mipmapped logical target out of range: " + target);
            }
            validated.set(target);
        }
        return validated;
    }

    private static int fullMipLevelCount(final int width, final int height) {
        return 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
    }

    private void releaseTextures() {
        for (int index = 0; index < formats.length; index++) {
            if (mainSampleViews[index] != null) {
                mainSampleViews[index].close();
                mainSampleViews[index] = null;
            }
            if (altSampleViews[index] != null) {
                altSampleViews[index].close();
                altSampleViews[index] = null;
            }
            if (mainViews[index] != null) {
                mainViews[index].close();
                mainViews[index] = null;
            }
            if (altViews[index] != null) {
                altViews[index].close();
                altViews[index] = null;
            }
            if (main[index] != null) {
                main[index].close();
                main[index] = null;
            }
            if (alt[index] != null) {
                alt[index].close();
                alt[index] = null;
            }
        }
    }

    private int checkIndex(final int index) {
        if (index < 0 || index >= formats.length) {
            throw new IllegalArgumentException("Logical target index out of range: " + index);
        }
        return index;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Ping-pong targets are closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        releaseTextures();
    }
}
