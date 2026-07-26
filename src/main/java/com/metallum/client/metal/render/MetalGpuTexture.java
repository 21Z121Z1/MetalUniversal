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
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.device = device;
        this.mtlPixelFormat = MTLPixelFormat.from(format);

        this.nativeHandle = MetalNativeBridge.metallum_create_texture_2d(
                device.metalDeviceHandle(),
                this.mtlPixelFormat,
                width,
                height,
                depthOrLayers,
                mipLevels,
                (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0 ? 1L : 0L,
                toMtlTextureUsage(usage),
                MTLStorageMode.Private,
                label
        );
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
            // Color render targets are also used as MetalFX output targets.
            // Depth attachments must not receive ShaderWrite, because Metal
            // does not permit storage writes to every depth format.
            if (!this.mtlPixelFormat.hasStencil() && this.mtlPixelFormat != MTLPixelFormat.Depth16Unorm
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
