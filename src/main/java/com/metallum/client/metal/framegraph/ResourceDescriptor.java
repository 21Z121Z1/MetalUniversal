package com.metallum.client.metal.framegraph;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Complete, renderer-neutral description of one semantic resource.
 *
 * <p>The descriptor is the only input to slot allocation, so it has to carry
 * everything that distinguishes two textures that must not share memory. In
 * particular {@link #stages()} is a real constraint and not documentation: it
 * both rejects a pass that uses a resource from a stage that resource was never
 * created for, and keeps two resources with incompatible {@code MTLTextureUsage}
 * out of the same alias slot.</p>
 *
 * <p>Do not create descriptors with "every stage" sets. A descriptor that
 * permits everything disables the stage check for that resource, which is how
 * you end up with a compute kernel writing a resource the backend allocated
 * without {@code shaderWrite} usage.</p>
 */
public record ResourceDescriptor(
        SizeDomain sizeDomain,
        PixelFormat format,
        ColorSpace colorSpace,
        int mipLevels,
        int sampleCount,
        Lifetime lifetime,
        Set<PipelineStage> stages
) {
    public ResourceDescriptor {
        Objects.requireNonNull(sizeDomain, "sizeDomain");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(colorSpace, "colorSpace");
        Objects.requireNonNull(lifetime, "lifetime");
        Objects.requireNonNull(stages, "stages");
        if (stages.isEmpty()) {
            throw new IllegalArgumentException("A resource usable from no pipeline stage cannot be used at all");
        }
        stages = Set.copyOf(stages);
        if (mipLevels < 1) {
            throw new IllegalArgumentException("mipLevels must be positive");
        }
        if (sampleCount < 1) {
            throw new IllegalArgumentException("sampleCount must be positive");
        }
        if (sampleCount > 1 && mipLevels > 1) {
            throw new IllegalArgumentException("A multisampled texture cannot have a mip chain");
        }
    }

    /**
     * A colour or depth attachment written by rasterisation and sampled in a
     * later fragment pass.
     */
    public static ResourceDescriptor attachment(
            final SizeDomain size,
            final PixelFormat format,
            final ColorSpace colorSpace,
            final Lifetime lifetime
    ) {
        return new ResourceDescriptor(size, format, colorSpace, 1, 1, lifetime,
                EnumSet.of(PipelineStage.FRAGMENT, PipelineStage.BLIT));
    }

    /** A resource a compute kernel writes and a later fragment or compute pass reads. */
    public static ResourceDescriptor computeTarget(
            final SizeDomain size,
            final PixelFormat format,
            final ColorSpace colorSpace,
            final Lifetime lifetime
    ) {
        return new ResourceDescriptor(size, format, colorSpace, 1, 1, lifetime,
                EnumSet.of(PipelineStage.COMPUTE, PipelineStage.FRAGMENT, PipelineStage.BLIT));
    }

    /**
     * A resource produced by rasterisation or compute and consumed by a MetalFX
     * scaler or interpolator.
     */
    public static ResourceDescriptor scalerInput(
            final SizeDomain size,
            final PixelFormat format,
            final ColorSpace colorSpace,
            final Lifetime lifetime
    ) {
        return new ResourceDescriptor(size, format, colorSpace, 1, 1, lifetime,
                EnumSet.of(PipelineStage.FRAGMENT, PipelineStage.COMPUTE, PipelineStage.SCALER, PipelineStage.BLIT));
    }

    /** A MetalFX output, readable afterwards for composition and presentation. */
    public static ResourceDescriptor scalerOutput(
            final SizeDomain size,
            final PixelFormat format,
            final ColorSpace colorSpace,
            final Lifetime lifetime
    ) {
        return new ResourceDescriptor(size, format, colorSpace, 1, 1, lifetime,
                EnumSet.of(PipelineStage.SCALER, PipelineStage.FRAGMENT, PipelineStage.BLIT, PipelineStage.PRESENT));
    }

    /** The drawable, or any target handed straight to the presenter. */
    public static ResourceDescriptor presentTarget(final SizeDomain size, final PixelFormat format, final ColorSpace colorSpace) {
        return new ResourceDescriptor(size, format, colorSpace, 1, 1, Lifetime.EXTERNAL,
                EnumSet.of(PipelineStage.FRAGMENT, PipelineStage.BLIT, PipelineStage.PRESENT));
    }

    public boolean allows(final PipelineStage stage) {
        return stages.contains(stage);
    }

    /**
     * Whether two resources may occupy the same allocation slot.
     *
     * <p>Both must be transient, because only a transient resource's contents
     * are dead outside its own pass range. Size domain, format, mip count and
     * sample count must match for the obvious reason that the slot is one
     * texture. The stage sets must match too: the backend derives
     * {@code MTLTextureUsage} from them, and a slot allocated without
     * {@code shaderWrite} cannot host a compute target however well its
     * dimensions line up.</p>
     */
    boolean aliasCompatible(final ResourceDescriptor other) {
        return lifetime == Lifetime.TRANSIENT && other.lifetime == Lifetime.TRANSIENT
                && sizeDomain == other.sizeDomain
                && format == other.format
                && mipLevels == other.mipLevels
                && sampleCount == other.sampleCount
                && stages.equals(other.stages);
    }

    /**
     * Which resolution a resource is allocated at. Keeping render resolution and
     * native display resolution apart is what stops a temporal-upscaling
     * pipeline from aliasing a scaler input onto a scaler output.
     */
    public enum SizeDomain {
        /** Render resolution: the scaler's input size, below display size when upscaling. */
        RENDER,
        /** Native display resolution: the scaler's output size. */
        NATIVE_DISPLAY,
        /** Shadow map resolution. */
        SHADOW,
        /** Sized by the declaring extension; never aliased against another domain. */
        CUSTOM
    }

    public enum PixelFormat {
        BGRA8_UNORM,
        BGRA8_SRGB,
        RGBA8_UNORM,
        RGBA8_SRGB,
        RGBA16_FLOAT,
        RG16_FLOAT,
        R16_FLOAT,
        R8_UNORM,
        R32_UINT,
        DEPTH32_FLOAT
    }

    public enum ColorSpace {
        LINEAR,
        SRGB,
        HDR_LINEAR,
        DISPLAY_NATIVE,
        /** Not colour at all: motion, coverage, masks. Never colour-converted. */
        DATA
    }

    public enum Lifetime {
        /** Dead outside its pass range; may share a slot with another transient resource. */
        TRANSIENT,
        /** Survives across frames for temporal reuse; never aliased. */
        HISTORY,
        /** Owned outside the graph, for example the drawable. Never aliased, never cleared. */
        EXTERNAL
    }

    public enum PipelineStage {
        VERTEX,
        FRAGMENT,
        COMPUTE,
        BLIT,
        /** A MetalFX scaler or frame interpolator encode. */
        SCALER,
        PRESENT
    }
}
