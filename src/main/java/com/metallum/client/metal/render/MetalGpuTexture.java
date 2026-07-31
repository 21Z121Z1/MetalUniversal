package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.metallum.client.metal.render.mtl.MTLStorageMode;
import com.metallum.client.metal.render.mtl.MTLTextureUsage;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
final class MetalGpuTexture extends GpuTexture {
    static final int USAGE_SHADER_WRITE = 1 << 5;
    // Minimal usage flags keep Apple GPU lossless bandwidth compression alive:
    // MTLTextureUsage.ShaderWrite disables it on pre-M5 GPUs, so it is only
    // set for textures that explicitly request USAGE_SHADER_WRITE (MetalFX
    // outputs and compute-written aux textures), never blanket-applied to
    // every color render target.
    private static final boolean MINIMAL_USAGE =
            Boolean.parseBoolean(System.getProperty("metallum.opt.minimalTextureUsage", "true"));
    private final MetalDevice device;
    private final MTLPixelFormat mtlPixelFormat;
    private boolean closed;
    @Nullable
    private Vector4fc materializedColorClear;
    @Nullable
    private Double materializedDepthClear;
    private int views = 1;
    @Nullable
    private MemorySegment nativeHandle;

    MetalGpuTexture(
            final MetalDevice device,
            @GpuTexture.Usage final int usage,
            final String label,
            final GpuFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels
    ) {
        this(
                device, usage, label, format, width, height, depthOrLayers, mipLevels,
                MetalTextureDimension.TWO_D
        );
    }

    MetalGpuTexture(
            final MetalDevice device,
            @GpuTexture.Usage final int usage,
            final String label,
            final GpuFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels,
            final MetalTextureDimension dimension
    ) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.device = device;
        this.mtlPixelFormat = MTLPixelFormat.from(format);

        this.nativeHandle = MetalNativeBridge.metallum_create_texture(
                device.metalDeviceHandle(),
                this.mtlPixelFormat,
                width,
                height,
                depthOrLayers,
                mipLevels,
                dimension.nativeValue,
                (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0 ? 1L : 0L,
                toMtlTextureUsage(usage),
                MTLStorageMode.Private,
                label
        );
        if (MetalNativeBridge.isNullHandle(this.nativeHandle)) {
            throw new IllegalStateException(
                    "Failed to create Metal " + dimension + " texture " + label + " ("
                            + width + 'x' + height + 'x' + depthOrLayers + ", " + format + ')'
            );
        }
    }

    int pixelSize() {
        return this.getFormat().blockSize();
    }

    void recordMaterializedClear(@Nullable final Vector4fc color, @Nullable final Double depth) {
        if (color != null) {
            this.materializedColorClear = color;
        }
        if (depth != null) {
            this.materializedDepthClear = depth;
        }
    }

    boolean clearIsRedundant(@Nullable final Vector4fc color, @Nullable final Double depth) {
        return (color == null || color.equals(this.materializedColorClear))
                && (depth == null || depth.equals(this.materializedDepthClear));
    }

    void markContentsDirty() {
        this.materializedColorClear = null;
        this.materializedDepthClear = null;
    }

    MemorySegment nativeHandle() {
        if (this.nativeHandle == null) {
            throw new IllegalStateException("Native Metal texture is closed");
        }
        return this.nativeHandle;
    }

    void queueNativeRelease(final MemorySegment handle) {
        this.device.queueResourceRelease(handle);
    }

    void addView() {
        this.views++;
    }

    void removeView() {
        this.views--;
        if (this.views < 0) {
            throw new IllegalStateException("Too many views removed from texture");
        }
        if (this.closed && this.views == 0 && this.nativeHandle != null) {
            MemorySegment handle = this.nativeHandle;
            this.nativeHandle = null;
            this.device.queueResourceRelease(handle);
        }
    }

    MTLPixelFormat mtlPixelFormat() {
        return this.mtlPixelFormat;
    }

    MTLPixelFormat mtlDepthPixelFormat() {
        return this.mtlPixelFormat == MTLPixelFormat.Stencil8 ? MTLPixelFormat.Invalid : this.mtlPixelFormat;
    }

    MTLPixelFormat mtlStencilPixelFormat() {
        return this.mtlPixelFormat == MTLPixelFormat.Stencil8 || this.mtlPixelFormat.hasStencil()
                ? this.mtlPixelFormat
                : MTLPixelFormat.Invalid;
    }

    boolean isOwnedBy(final MetalDevice expected) {
        return this.device == expected;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.removeView();
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    private long toMtlTextureUsage(@GpuTexture.Usage final int usage) {
        long result = 0L;
        if ((usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0 || (usage & GpuTexture.USAGE_COPY_DST) != 0 || (usage & GpuTexture.USAGE_COPY_SRC) != 0) {
            result |= MTLTextureUsage.ShaderRead.value;
        }
        if ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
            result |= MTLTextureUsage.RenderTarget.value;
            result |= MTLTextureUsage.ShaderRead.value;
            // Legacy path (kill switch only): blanket ShaderWrite on color
            // attachments because MetalFX outputs used to rely on it. The
            // minimal-usage path instead requires MetalFX output targets to
            // carry USAGE_SHADER_WRITE explicitly (MetalDevice
            // withExtraTextureUsage scope around their creation). Depth
            // attachments must not receive ShaderWrite, because Metal does
            // not permit storage writes to every depth format.
            if (!MINIMAL_USAGE
                    && !this.mtlPixelFormat.hasStencil() && this.mtlPixelFormat != MTLPixelFormat.Depth16Unorm
                    && this.mtlPixelFormat != MTLPixelFormat.Depth32Float) {
                result |= MTLTextureUsage.ShaderWrite.value;
            }
        }
        if ((usage & USAGE_SHADER_WRITE) != 0) {
            result |= MTLTextureUsage.ShaderWrite.value;
        }
        return result == 0L ? MTLTextureUsage.ShaderRead.value : result;
    }

    // --- Iris dormancy support -------------------------------------------
    //
    // Iris mixes a virtual `int iris$getGlId()` into GpuTexture whose default
    // body throws for non-GL textures, and its AbstractTexture hook calls it
    // for EVERY texture the game creates (fonts first). This name-matched
    // override shadows the mixin-added method at runtime and hands Iris a
    // stable synthetic id so its texture-tracking maps stay consistent while
    // it is dormant on Metal. Plain Java: no Iris compile dependency needed —
    // the descriptor `()I` and name are what the JVM dispatches on. Harmless
    // when Iris is absent (just an unused method).
    private static final java.util.concurrent.atomic.AtomicInteger IRIS_SYNTHETIC_ID =
            new java.util.concurrent.atomic.AtomicInteger(1);
    private int irisSyntheticGlId;

    public int iris$getGlId() {
        if (irisSyntheticGlId == 0) {
            irisSyntheticGlId = IRIS_SYNTHETIC_ID.getAndIncrement();
        }
        return irisSyntheticGlId;
    }
}
