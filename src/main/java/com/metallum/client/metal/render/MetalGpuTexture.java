package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.metallum.client.metal.render.mtl.MTLStorageMode;
import com.metallum.client.metal.render.mtl.MTLTextureUsage;
import com.metallum.client.validation.contract.RenderContractRuntime;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.Objects;
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
    private final MetalAllocationIdentity allocationIdentity;
    private final MTLPixelFormat mtlPixelFormat;
    private final MTLStorageMode storageMode;
    private boolean closed;
    @Nullable
    private Vector4fc materializedColorClear;
    @Nullable
    private Double materializedDepthClear;
    private int views = 1;
    private boolean validationAllocationInvalidated;
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
        this(device, usage, label, format, width, height, depthOrLayers, mipLevels, dimension, MTLStorageMode.Private);
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
            final MetalTextureDimension dimension,
            final MTLStorageMode storageMode
    ) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.device = device;
        this.allocationIdentity = MetalAllocationIdentity.allocate(label);
        this.mtlPixelFormat = MTLPixelFormat.from(format);
        this.storageMode = Objects.requireNonNull(storageMode, "storageMode");
        if (storageMode == MTLStorageMode.Memoryless
                && !memorylessCompatible(usage, dimension, depthOrLayers, mipLevels)) {
            throw new IllegalArgumentException(
                    "Memoryless Metal textures must be single-layer 2D render-only attachments"
            );
        }

        this.nativeHandle = MetalNativeBridge.metallum_create_texture(
                device.metalDeviceHandle(),
                this.mtlPixelFormat,
                width,
                height,
                depthOrLayers,
                mipLevels,
                dimension.nativeValue,
                (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0 ? 1L : 0L,
                toMtlTextureUsage(usage, storageMode),
                storageMode,
                label
        );
        if (MetalNativeBridge.isNullHandle(this.nativeHandle)) {
            throw new IllegalStateException(
                    "Failed to create Metal " + dimension + " texture " + label + " ("
                            + width + 'x' + height + 'x' + depthOrLayers + ", " + format + ", "
                            + storageMode + ')'
            );
        }
    }

    static MetalGpuTexture createMemorylessRenderTarget(
            final MetalDevice device,
            @GpuTexture.Usage final int usage,
            final String label,
            final GpuFormat format,
            final int width,
            final int height
    ) {
        return new MetalGpuTexture(
                device,
                usage,
                label,
                format,
                width,
                height,
                1,
                1,
                MetalTextureDimension.TWO_D,
                MTLStorageMode.Memoryless
        );
    }

    static boolean memorylessCompatible(
            @GpuTexture.Usage final int usage,
            final MetalTextureDimension dimension,
            final int depthOrLayers,
            final int mipLevels
    ) {
        return usage == GpuTexture.USAGE_RENDER_ATTACHMENT
                && dimension == MetalTextureDimension.TWO_D
                && depthOrLayers == 1
                && mipLevels == 1;
    }

    int pixelSize() {
        return this.getFormat().blockSize();
    }

    /** Renderer-owned allocation identity; never use the native pointer as a hazard key. */
    MetalAllocationIdentity allocationIdentity() {
        return allocationIdentity;
    }

    long allocationId() {
        return allocationIdentity.allocationId();
    }

    String allocationDebugId() {
        return "metal-texture-" + allocationId();
    }

    /** Observes the renderer-owned identity at the existing contract seam. */
    void registerAllocationIdentity() {
        if (RenderContractRuntime.observing()) {
            MetalCommandEncoder.contractResource(this, 0);
        }
    }

    /** Narrow source compatibility for the pre-authority eager trace hook. */
    @Deprecated
    long validationResourceId() {
        return allocationId();
    }

    /** Narrow source compatibility for existing validation call sites. */
    @Deprecated
    String validationDebugId() {
        return allocationDebugId();
    }

    /** Narrow source compatibility for existing Iris creation sites. */
    @Deprecated
    void registerValidationIdentity() {
        registerAllocationIdentity();
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
            if (!this.validationAllocationInvalidated && RenderContractRuntime.observing()) {
                this.validationAllocationInvalidated = true;
                RenderContractRuntime.invalidateResourceAllocations(
                        this.allocationId(),
                        this.allocationDebugId()
                );
            }
            this.device.queueResourceRelease(handle);
        }
    }

    MTLPixelFormat mtlPixelFormat() {
        return this.mtlPixelFormat;
    }

    MTLStorageMode storageMode() {
        return this.storageMode;
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

    private long toMtlTextureUsage(
            @GpuTexture.Usage final int usage,
            final MTLStorageMode storageMode
    ) {
        long result = 0L;
        if ((usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0 || (usage & GpuTexture.USAGE_COPY_DST) != 0 || (usage & GpuTexture.USAGE_COPY_SRC) != 0) {
            result |= MTLTextureUsage.ShaderRead.value;
        }
        if ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
            result |= MTLTextureUsage.RenderTarget.value;
            // Pass-local memoryless attachments never become long-lived shader
            // resources; keep their Metal usage at RenderTarget so the driver
            // can preserve the strongest tile-memory assumptions.
            if (storageMode != MTLStorageMode.Memoryless) {
                result |= MTLTextureUsage.ShaderRead.value;
            }
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
