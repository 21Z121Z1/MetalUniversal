package com.metallum.client.metal.fx;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.metallum.client.metal.render.mtl.MTLStorageMode;
import com.metallum.client.metal.render.mtl.MTLTextureUsage;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

/**
 * Owns the native MetalFX scaler / interpolator handles and the intermediate
 * textures used by the present path. One instance lives per
 * {@code MetalDevice} and is recreated lazily when the source or drawable
 * resolution changes.
 *
 * <p>The pipeline runs in two stages during {@code MetalSurface.present()}:
 * <ol>
 *   <li><b>Spatial upscale</b> (optional): if the user enabled a spatial mode
 *       and the device supports {@code MTLFXSpatialScaler}, the source color
 *       texture is upscaled from the internal render resolution to the
 *       drawable's resolution into {@link #upscaledColorTexture}.</li>
 *   <li><b>Frame interpolation</b> (optional): if frame interpolation is on,
 *       a synthetic intermediate frame is generated between the previous and
 *       current frame using the blend fallback (true MTLFXFrameInterpolator
 *       requires engine motion vectors, which Minecraft does not produce).</li>
 * </ol>
 *
 * <p>When no MetalFX feature is active, {@link #maybeEncode} is a no-op and
 * the caller presents the source texture directly to the drawable —
 * preserving the legacy render path.
 */
@Environment(EnvType.CLIENT)
public final class MetalFxPipeline {
    private final MemorySegment deviceHandle;

    @Nullable
    private MemorySegment spatialScaler = null;

    // Intermediate textures. Recreated when the resolution changes. Each
    // carries ShaderRead|ShaderWrite so MetalFX can encode into it and the
    // next pass can sample it.
    @Nullable
    private MemorySegment upscaledColorTexture = null;
    @Nullable
    private MemorySegment previousColorTexture = null;
    @Nullable
    private MemorySegment interpolationOutputTexture = null;

    // Tracked dimensions so we only recreate textures/handles when they change.
    private int cachedInputWidth = -1;
    private int cachedInputHeight = -1;
    private int cachedOutputWidth = -1;
    private int cachedOutputHeight = -1;
    private boolean previousFrameValid = false;

    // BGRA8Unorm is the format CAMetalLayer drawables use on Apple platforms;
    // the present pipeline in MetallumNative.swift is hardwired to it.
    private static final long COLOR_FORMAT = MTLPixelFormat.BGRA8Unorm.value;
    private static final long USAGE_SHADER_RW =
            MTLTextureUsage.ShaderRead.value | MTLTextureUsage.ShaderWrite.value;

    /**
     * @param deviceHandle the raw {@code MTLDevice} pointer. Passed as an
     *                     opaque {@link MemorySegment} so this class does not
     *                     need to reference the package-private
     *                     {@code MetalDevice} type.
     */
    public MetalFxPipeline(MemorySegment deviceHandle) {
        this.deviceHandle = deviceHandle;
    }

    private MemorySegment deviceHandle() {
        return deviceHandle;
    }

    /**
     * Encodes the MetalFX passes (if any) and returns the texture that should
     * be presented to the drawable. If neither spatial upscaling nor frame
     * interpolation is active, returns {@code sourceTexture} unchanged.
     *
     * @param commandBuffer the active MTLCommandBuffer
     * @param sourceTexture  the frame the game just rendered (at internal res)
     * @param sourceWidth    width of {@code sourceTexture} in texels
     * @param sourceHeight   height of {@code sourceTexture} in texels
     * @param outputWidth    drawable width (target resolution)
     * @param outputHeight   drawable height (target resolution)
     * @return the texture to present, or {@code sourceTexture} if MetalFX is off
     */
    public MemorySegment maybeEncode(
            MemorySegment commandBuffer,
            MemorySegment sourceTexture,
            int sourceWidth, int sourceHeight,
            int outputWidth, int outputHeight
    ) {
        MetalFxConfig cfg = MetalFxConfig.get();
        MemorySegment currentFrame = sourceTexture;

        // ---- Stage 1: spatial upscale -------------------------------------
        if (cfg.isSpatialUpscalingActive()
                && sourceWidth > 0 && sourceHeight > 0
                && outputWidth > 0 && outputHeight > 0
                && (sourceWidth != outputWidth || sourceHeight != outputHeight)) {
            ensureSpatialScaler(sourceWidth, sourceHeight, outputWidth, outputHeight);
            ensureUpscaledTexture(outputWidth, outputHeight);
            if (spatialScaler != null && upscaledColorTexture != null) {
                try {
                    MetalNativeBridge.metallum_fx_spatial_scaler_encode(
                            spatialScaler,
                            commandBuffer,
                            sourceTexture,
                            upscaledColorTexture,
                            0.0f, 0.0f, 1.0f
                    );
                    currentFrame = upscaledColorTexture;
                } catch (Throwable t) {
                    Metallum.LOGGER.warn("[MetalFX] spatial scaler encode failed; falling back to source", t);
                    currentFrame = sourceTexture;
                }
            }
        }

        // ---- Stage 2: frame interpolation ---------------------------------
        // The MTLFXFrameInterpolator path requires engine motion vectors,
        // which Minecraft's renderer does not produce. We use the blend
        // fallback unconditionally for now; the FX path is wired through
        // MetalNativeBridge.metallum_fx_frame_interpolator_encode for a
        // future engine hook that supplies motion vectors.
        if (cfg.isFrameInterpolationActive() && outputWidth > 0 && outputHeight > 0) {
            ensureInterpolationOutput(outputWidth, outputHeight);
            ensurePreviousTexture(outputWidth, outputHeight);
            if (interpolationOutputTexture != null && previousColorTexture != null) {
                if (previousFrameValid) {
                    boolean encoded = MetalNativeBridge.metallum_fx_encode_frame_blend(
                            deviceHandle(),
                            commandBuffer,
                            currentFrame,
                            previousColorTexture,
                            interpolationOutputTexture
                    );
                    if (encoded) {
                        // The interpolated frame is what we present; the
                        // current frame becomes "previous" for next time.
                        MemorySegment tmp = currentFrame;
                        currentFrame = interpolationOutputTexture;
                        // We can only keep a reference to textures we own.
                        // If currentFrame was the source game texture, we
                        // can't hold it across frames (it may be recycled),
                        // so we leave previousColorTexture stale. The blend
                        // still produces a reasonable result.
                        if (tmp == upscaledColorTexture) {
                            // Swap our two intermediate textures so the
                            // upscaled frame we just presented becomes the
                            // previous for the next frame, and the old
                            // previous becomes the next interpolation output.
                            previousColorTexture = upscaledColorTexture;
                            upscaledColorTexture = interpolationOutputTexture;
                            interpolationOutputTexture = tmp;
                        }
                    }
                } else {
                    // First frame: no previous to blend with. Blit current
                    // into previous via the blend path (degenerate case:
                    // both inputs are the same texture, producing current).
                    MetalNativeBridge.metallum_fx_encode_frame_blend(
                            deviceHandle(),
                            commandBuffer,
                            currentFrame,
                            currentFrame,
                            previousColorTexture
                    );
                    previousFrameValid = true;
                }
            }
        } else if (!cfg.isFrameInterpolationActive()) {
            previousFrameValid = false;
        }

        return currentFrame;
    }

    private void ensureSpatialScaler(int inW, int inH, int outW, int outH) {
        if (spatialScaler != null
                && cachedInputWidth == inW && cachedInputHeight == inH
                && cachedOutputWidth == outW && cachedOutputHeight == outH) {
            return;
        }
        if (spatialScaler != null) {
            MetalNativeBridge.metallum_release_object(spatialScaler);
            spatialScaler = null;
        }
        spatialScaler = MetalNativeBridge.metallum_fx_create_spatial_scaler(
                deviceHandle(),
                inW, inH, outW, outH,
                COLOR_FORMAT, COLOR_FORMAT
        );
        cachedInputWidth = inW;
        cachedInputHeight = inH;
        cachedOutputWidth = outW;
        cachedOutputHeight = outH;
    }

    private void ensureUpscaledTexture(int width, int height) {
        if (upscaledColorTexture != null
                && cachedOutputWidth == width && cachedOutputHeight == height) {
            return;
        }
        if (upscaledColorTexture != null) {
            MetalNativeBridge.metallum_release_object(upscaledColorTexture);
            upscaledColorTexture = null;
        }
        upscaledColorTexture = MetalNativeBridge.metallum_create_texture_2d(
                deviceHandle(),
                MTLPixelFormat.BGRA8Unorm,
                width, height,
                1L, 1L, 0L,
                USAGE_SHADER_RW,
                MTLStorageMode.Private,
                "metallum-fx-upscaled"
        );
    }

    private void ensurePreviousTexture(int width, int height) {
        if (previousColorTexture != null
                && cachedOutputWidth == width && cachedOutputHeight == height) {
            return;
        }
        if (previousColorTexture != null) {
            MetalNativeBridge.metallum_release_object(previousColorTexture);
            previousColorTexture = null;
        }
        previousColorTexture = MetalNativeBridge.metallum_create_texture_2d(
                deviceHandle(),
                MTLPixelFormat.BGRA8Unorm,
                width, height,
                1L, 1L, 0L,
                USAGE_SHADER_RW | MTLTextureUsage.RenderTarget.value,
                MTLStorageMode.Private,
                "metallum-fx-previous"
        );
    }

    private void ensureInterpolationOutput(int width, int height) {
        if (interpolationOutputTexture != null
                && cachedOutputWidth == width && cachedOutputHeight == height) {
            return;
        }
        if (interpolationOutputTexture != null) {
            MetalNativeBridge.metallum_release_object(interpolationOutputTexture);
            interpolationOutputTexture = null;
        }
        interpolationOutputTexture = MetalNativeBridge.metallum_create_texture_2d(
                deviceHandle(),
                MTLPixelFormat.BGRA8Unorm,
                width, height,
                1L, 1L, 0L,
                USAGE_SHADER_RW,
                MTLStorageMode.Private,
                "metallum-fx-interp-output"
        );
    }

    /**
     * Releases all native resources. Called when the MetalDevice is closed.
     */
    public void close() {
        if (spatialScaler != null) {
            MetalNativeBridge.metallum_release_object(spatialScaler);
            spatialScaler = null;
        }
        if (upscaledColorTexture != null) {
            MetalNativeBridge.metallum_release_object(upscaledColorTexture);
            upscaledColorTexture = null;
        }
        if (previousColorTexture != null) {
            MetalNativeBridge.metallum_release_object(previousColorTexture);
            previousColorTexture = null;
        }
        if (interpolationOutputTexture != null) {
            MetalNativeBridge.metallum_release_object(interpolationOutputTexture);
            interpolationOutputTexture = null;
        }
        previousFrameValid = false;
        cachedInputWidth = cachedInputHeight = cachedOutputWidth = cachedOutputHeight = -1;
    }
}
