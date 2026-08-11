package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLCompareFunction;
import com.metallum.client.metal.render.mtl.MTLSamplerAddressMode;
import com.metallum.client.metal.render.mtl.MTLSamplerMinMagFilter;
import com.metallum.client.metal.render.mtl.MTLSamplerMipFilter;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;

import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.OptionalDouble;

@Environment(EnvType.CLIENT)
final class MetalGpuSampler extends GpuSampler {
    private final MetalDevice device;
    private final MemorySegment nativeHandle;
    private final AddressMode addressModeU;
    private final AddressMode addressModeV;
    private final FilterMode minFilter;
    private final FilterMode magFilter;
    private final int maxAnisotropy;
    private final OptionalDouble maxLod;
    private final MTLSamplerMipFilter mipFilter;
    private final boolean normalizedCoordinates;
    private boolean closed;

    MetalGpuSampler(
            final MetalDevice device,
            final AddressMode addressModeU,
            final AddressMode addressModeV,
            final FilterMode minFilter,
            final FilterMode magFilter,
            final int maxAnisotropy,
            final OptionalDouble maxLod
    ) {
        this(
                device, addressModeU, addressModeV, minFilter, magFilter,
                maxAnisotropy, maxLod, null, toMtlMipFilter(maxLod), true
        );
    }

    /**
     * Mod-private extension: vanilla Blaze3D samplers have no depth-compare
     * concept, but Iris shadow samplers ({@code sampler2DShadow} /
     * {@code GL_TEXTURE_COMPARE_MODE}) require one. A non-null
     * {@code compareFunction} creates an MSL {@code sample_compare}-capable
     * sampler through the v2 native ABI.
     */
    MetalGpuSampler(
            final MetalDevice device,
            final AddressMode addressModeU,
            final AddressMode addressModeV,
            final FilterMode minFilter,
            final FilterMode magFilter,
            final int maxAnisotropy,
            final OptionalDouble maxLod,
            @org.jspecify.annotations.Nullable final MTLCompareFunction compareFunction
    ) {
        this(
                device, addressModeU, addressModeV, minFilter, magFilter,
                maxAnisotropy, maxLod, compareFunction, toMtlMipFilter(maxLod), true
        );
    }

    MetalGpuSampler(
            final MetalDevice device,
            final AddressMode addressModeU,
            final AddressMode addressModeV,
            final FilterMode minFilter,
            final FilterMode magFilter,
            final int maxAnisotropy,
            final OptionalDouble maxLod,
            @org.jspecify.annotations.Nullable final MTLCompareFunction compareFunction,
            final MTLSamplerMipFilter mipFilter
    ) {
        this(
                device, addressModeU, addressModeV, minFilter, magFilter,
                maxAnisotropy, maxLod, compareFunction, mipFilter, true
        );
    }

    MetalGpuSampler(
            final MetalDevice device,
            final AddressMode addressModeU,
            final AddressMode addressModeV,
            final FilterMode minFilter,
            final FilterMode magFilter,
            final int maxAnisotropy,
            final OptionalDouble maxLod,
            @org.jspecify.annotations.Nullable final MTLCompareFunction compareFunction,
            final MTLSamplerMipFilter mipFilter,
            final boolean normalizedCoordinates
    ) {
        this.device = device;
        this.mipFilter = Objects.requireNonNull(mipFilter, "mipFilter");
        this.normalizedCoordinates = normalizedCoordinates;
        this.nativeHandle = MetalNativeBridge.metallum_create_sampler_v3(
                device.metalDeviceHandle(),
                MTLSamplerAddressMode.from(addressModeU),
                MTLSamplerAddressMode.from(addressModeV),
                MTLSamplerMinMagFilter.from(minFilter),
                MTLSamplerMinMagFilter.from(magFilter),
                this.mipFilter,
                Math.max(1, maxAnisotropy),
                toMtlMaxLodClamp(maxLod),
                compareFunction == null ? -1 : (int) compareFunction.value,
                normalizedCoordinates
        );
        this.addressModeU = addressModeU;
        this.addressModeV = addressModeV;
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.maxAnisotropy = maxAnisotropy;
        this.maxLod = maxLod;
    }

    @Override
    public @NonNull AddressMode getAddressModeU() {
        return this.addressModeU;
    }

    @Override
    public @NonNull AddressMode getAddressModeV() {
        return this.addressModeV;
    }

    @Override
    public @NonNull FilterMode getMinFilter() {
        return this.minFilter;
    }

    @Override
    public @NonNull FilterMode getMagFilter() {
        return this.magFilter;
    }

    @Override
    public int getMaxAnisotropy() {
        return this.maxAnisotropy;
    }

    @Override
    public @NonNull OptionalDouble getMaxLod() {
        return this.maxLod;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.device.queueResourceRelease(this.nativeHandle);
    }

    boolean isClosed() {
        return this.closed;
    }

    boolean isOwnedBy(final MetalDevice expected) {
        return this.device == expected;
    }

    MemorySegment nativeHandle() {
        return this.nativeHandle;
    }

    MTLSamplerMipFilter mipFilter() {
        return this.mipFilter;
    }

    boolean normalizedCoordinates() {
        return this.normalizedCoordinates;
    }

    private static MTLSamplerMipFilter toMtlMipFilter(final OptionalDouble maxLod) {
        return maxLod.orElse(1000.0) > 0.25 ? MTLSamplerMipFilter.Linear : MTLSamplerMipFilter.Nearest;
    }

    private static double toMtlMaxLodClamp(final OptionalDouble maxLod) {
        return Math.max(0.25, maxLod.orElse(1000.0));
    }
}
