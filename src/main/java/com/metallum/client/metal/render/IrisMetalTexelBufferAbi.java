package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/** Fixed Iris/Sodium texel-buffer providers that are already supplied by the draw owner. */
@Environment(EnvType.CLIENT)
final class IrisMetalTexelBufferAbi {
    private static final Map<String, GpuFormat> FIXED_FORMATS = Map.of(
            "CloudFaces", GpuFormat.R8_SINT,
            "u_SectionTimeInfo", GpuFormat.R32_SINT
    );

    /** A real buffer range together with the format used to create its Metal texture view. */
    record Binding(GpuBufferSlice slice, GpuFormat format) {
        Binding {
            Objects.requireNonNull(slice, "slice");
            Objects.requireNonNull(format, "format");
        }
    }

    /**
     * Resolves a typed buffer owned by the fixed Iris/Sodium producer. The
     * provider must return a live range from the same Metal device as the
     * generation being prepared; callers never synthesize a fallback buffer.
     */
    @FunctionalInterface
    interface Provider {
        @Nullable Binding resolve(String passName, IrisMetalGlslLinker.SamplerDecl sampler);
    }

    private IrisMetalTexelBufferAbi() {
    }

    static @Nullable GpuFormat formatFor(final String name) {
        return FIXED_FORMATS.get(name);
    }

    static boolean isFixedProvider(final String name) {
        return FIXED_FORMATS.containsKey(name);
    }

    static Binding require(
            final String passName,
            final IrisMetalGlslLinker.SamplerDecl sampler,
            final @Nullable Provider provider,
            final MetalDevice device
    ) {
        Objects.requireNonNull(passName, "passName");
        Objects.requireNonNull(sampler, "sampler");
        Objects.requireNonNull(device, "device");
        GpuFormat expected = formatFor(sampler.name());
        if (expected == null) {
            throw new IllegalStateException(
                    "Iris pass " + passName + " declares pack-owned samplerBuffer '"
                            + sampler.name() + "' without a fixed provider ABI"
            );
        }
        if (provider == null) {
            throw new IllegalStateException(
                    "Iris pass " + passName + " requires typed texel-buffer provider '"
                            + sampler.name() + "'"
            );
        }
        Binding binding = provider.resolve(passName, sampler);
        if (binding == null) {
            throw new IllegalStateException(
                    "Iris pass " + passName + " is missing typed texel-buffer producer '"
                            + sampler.name() + "'"
            );
        }
        if (binding.format() != expected) {
            throw new IllegalStateException(
                    "Iris pass " + passName + " typed texel-buffer '" + sampler.name()
                            + "' uses " + binding.format() + ", expected " + expected
            );
        }
        requireSlice(passName, sampler.name(), binding.slice(), expected, device);
        return binding;
    }

    /** Validates the actual draw-owned range before a Metal texel view is made. */
    static void requireSlice(
            final String passName,
            final String resourceName,
            final GpuBufferSlice slice,
            final GpuFormat format,
            final MetalDevice device
    ) {
        Objects.requireNonNull(passName, "passName");
        Objects.requireNonNull(resourceName, "resourceName");
        Objects.requireNonNull(slice, "slice");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(device, "device");
        GpuFormat fixed = formatFor(resourceName);
        if (fixed != null && fixed != format) {
            throw new IllegalStateException(
                    "Iris pass " + passName + " typed texel-buffer '" + resourceName
                            + "' uses " + format + ", expected fixed ABI format " + fixed
            );
        }
        if (!(slice.buffer() instanceof MetalGpuBuffer buffer)) {
            throw new IllegalStateException(
                    "Iris pass " + passName + " typed texel-buffer '" + resourceName
                            + "' is not backed by Metal"
            );
        }
        if (buffer.isClosed() || !buffer.isOwnedBy(device)) {
            throw new IllegalStateException(
                    "Iris pass " + passName + " typed texel-buffer '" + resourceName
                            + "' is stale or belongs to another Metal device"
            );
        }
        if ((buffer.usage() & com.mojang.blaze3d.buffers.GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER) == 0) {
            throw new IllegalStateException(
                    "Iris pass " + passName + " typed texel-buffer '" + resourceName
                            + "' is missing USAGE_UNIFORM_TEXEL_BUFFER"
            );
        }
        long offset = slice.offset();
        long length = slice.length();
        int blockSize = format.blockSize();
        if (offset < 0L || length <= 0L || length % blockSize != 0L
                || offset > buffer.size()
                || length > buffer.size() - offset) {
            throw new IllegalStateException(
                    "Iris pass " + passName + " typed texel-buffer '" + resourceName
                            + "' has invalid range offset=" + offset + ", length=" + length
                            + ", bufferSize=" + buffer.size() + ", format=" + format
            );
        }
    }
}
