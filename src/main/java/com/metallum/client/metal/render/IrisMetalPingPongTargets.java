package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.BitSet;
import java.util.Objects;
import java.util.Set;

/**
 * Generation-owned main/alternate target pairs implementing Iris
 * {@code BufferFlipper} semantics for colortex and shadowcolor resources.
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
    private final BitSet flipped;
    private final BitSet flippedAtLeastOnce;
    private final BitSet mipmappedTargets;
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
        for (int index = 0; index < formats.length; index++) {
            int mipLevels = this.mipmappedTargets.get(index)
                    ? fullMipLevelCount(newWidth, newHeight)
                    : 1;
            main[index] = (MetalGpuTexture) device.createTexture(
                    labelPrefix + index + "-main", TEXTURE_USAGE, formats[index], newWidth, newHeight, 1, mipLevels);
            alt[index] = (MetalGpuTexture) device.createTexture(
                    labelPrefix + index + "-alt", TEXTURE_USAGE, formats[index], newWidth, newHeight, 1, mipLevels);
            mainViews[index] = new MetalGpuTextureView(main[index], 0, mipLevels);
            altViews[index] = new MetalGpuTextureView(alt[index], 0, mipLevels);
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

    MetalGpuTexture readTexture(final int index) {
        ensureOpen();
        return flipped.get(checkIndex(index)) ? alt[index] : main[index];
    }

    MetalGpuTexture writeTexture(final int index) {
        ensureOpen();
        return flipped.get(checkIndex(index)) ? main[index] : alt[index];
    }

    MetalGpuTexture mainTexture(final int index) {
        ensureOpen();
        return main[checkIndex(index)];
    }

    MetalGpuTexture altTexture(final int index) {
        ensureOpen();
        return alt[checkIndex(index)];
    }

    MetalGpuTextureView readView(final int index) {
        ensureOpen();
        return flipped.get(checkIndex(index)) ? altViews[index] : mainViews[index];
    }

    MetalGpuTextureView writeView(final int index) {
        ensureOpen();
        return flipped.get(checkIndex(index)) ? mainViews[index] : altViews[index];
    }

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

    boolean readMipmapsEnabled(final int index) {
        ensureOpen();
        int checked = checkIndex(index);
        return this.flipped.get(checked)
                ? this.mipmapsOnAlt.get(checked)
                : this.mipmapsOnMain.get(checked);
    }

    void resetMipmaps() {
        ensureOpen();
        this.mipmapsOnMain.clear();
        this.mipmapsOnAlt.clear();
    }

    void flip(final int index) {
        ensureOpen();
        int checked = checkIndex(index);
        flipped.flip(checked);
        flippedAtLeastOnce.set(checked);
    }

    boolean isFlipped(final int index) {
        return flipped.get(checkIndex(index));
    }

    boolean flippedAtLeastOnce(final int index) {
        return flippedAtLeastOnce.get(checkIndex(index));
    }

    BitSet snapshot() {
        ensureOpen();
        return (BitSet) flipped.clone();
    }

    void restore(final BitSet snapshot) {
        ensureOpen();
        if (snapshot.length() > formats.length) {
            throw new IllegalArgumentException("Flip snapshot contains an out-of-range logical target");
        }
        flipped.clear();
        flipped.or(snapshot);
    }

    void checkNoFeedbackLoop(final int[] writeTargets, final int[] readTargets) {
        for (int write : writeTargets) {
            checkIndex(write);
            for (int read : readTargets) {
                checkIndex(read);
                if (write == read) {
                    throw new IllegalStateException(
                            "Pass reads and writes logical target " + write
                                    + " without an intervening flip (feedback loop)"
                    );
                }
            }
        }
    }

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
