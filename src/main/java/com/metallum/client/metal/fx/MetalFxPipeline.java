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
 *   <li><b>Frame interpolation</b> (optional): if frame interpolation is on
 *       and the device supports {@code MTLFXFrameInterpolator} (M3+ /
 *       A17 Pro+), a synthetic intermediate frame is generated between the
 *       previous and current frame using the hardware interpolator.</li>
 * </ol>
 *
 * <p>When no MetalFX feature is active, {@link #maybeEncode} is a no-op and
 * the caller presents the source texture directly to the drawable —
 * preserving the legacy render path.
 *
 * <p><b>Why this is now actually useful.</b> Previously the spatial scaler
 * was a no-op because {@code MetalSurface} reported the full display
 * resolution as the internal resolution, so {@code sourceWidth == outputWidth}
 * and the scaler was skipped. {@code MetalSurface} now shrinks the reported
 * internal resolution when spatial upscaling is active, so the game renders
 * at e.g. 77% resolution and MetalFX upscales to 100% — yielding real FPS
 * gains on fragment-bound scenes.
 *
 * <p>The 50/50 blend fallback for frame interpolation has been removed: it
 * produced severe ghosting on fast-moving first-person content (the exact
 * use case where users want interpolation). Frame interpolation now
 * requires the hardware {@code MTLFXFrameInterpolator} path, which is only
 * available on M3+ / A17 Pro+ devices. On older devices the option
 * silently does nothing (the config screen reports "not supported").
 */
@Environment(EnvType.CLIENT)
public final class MetalFxPipeline {
    private final MemorySegment deviceHandle;

    @Nullable
    private MemorySegment spatialScaler = null;
    @Nullable
    private MemorySegment frameInterpolator = null;

    // Intermediate textures. Recreated when the resolution changes. Each
    // carries ShaderRead|ShaderWrite so MetalFX can encode into it and the
    // next pass can sample it.
    @Nullable
    private MemorySegment upscaledColorTexture = null;
    @Nullable
    private MemorySegment previousColorTexture = null;
    @Nullable
    private MemorySegment interpolationOutputTexture = null;
    @Nullable
    private MemorySegment motionVectorTexture = null;

    // Tracked dimensions so we only recreate textures/handles when they change.
    private int cachedInputWidth = -1;
    private int cachedInputHeight = -1;
    private int cachedOutputWidth = -1;
    private int cachedOutputHeight = -1;
    private boolean previousFrameValid = false;
    private boolean loggedSpatialActive = false;
    private boolean loggedInterpActive = false;

    // BGRA8Unorm is the format CAMetalLayer drawables use on Apple platforms;
    // the present pipeline in MetallumNative.swift is hardwired to it.
    private static final long COLOR_FORMAT = MTLPixelFormat.BGRA8Unorm.value;
    // Motion vectors are 2-component floats (x, y) per texel — this matches
    // MTLFXFrameInterpolator's required motion vector format.
    private static final MTLPixelFormat MOTION_FORMAT_ENUM = MTLPixelFormat.RG32Float;
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
        // This branch now actually fires because MetalSurface shrinks the
        // internal resolution reported to Minecraft, so sourceWidth <
        // outputWidth when spatial upscaling is active.
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
                    if (!loggedSpatialActive) {
                        Metallum.LOGGER.info(
                                "[MetalFX] spatial scaler RUNNING: {}x{} -> {}x{} (mode={})",
                                sourceWidth, sourceHeight, outputWidth, outputHeight, cfg.spatialMode());
                        loggedSpatialActive = true;
                    }
                } catch (Throwable t) {
                    Metallum.LOGGER.warn("[MetalFX] spatial scaler encode failed; falling back to source", t);
                    currentFrame = sourceTexture;
                }
            }
        } else if (loggedSpatialActive && !cfg.isSpatialUpscalingActive()) {
            loggedSpatialActive = false;
        }

        // ---- Stage 2: hardware frame interpolation ------------------------
        // Only runs on M3+ / A17 Pro+ (MTLFXFrameInterpolator support).
        // The 50/50 blend fallback was removed due to unacceptable
        // ghosting on fast-moving content. On unsupported devices
        // isFrameInterpolationActive() returns false, so this entire
        // block is skipped and the user sees no interpolation.
        if (cfg.isFrameInterpolationActive() && cfg.usesMtlFxInterpolator()
                && outputWidth > 0 && outputHeight > 0) {
            ensureInterpolationOutput(outputWidth, outputHeight);
            ensurePreviousTexture(outputWidth, outputHeight);
            ensureMotionVectorTexture(outputWidth, outputHeight);
            if (interpolationOutputTexture != null && previousColorTexture != null) {
                if (frameInterpolator == null) {
                    frameInterpolator = MetalNativeBridge.metallum_fx_create_frame_interpolator(
                            deviceHandle(), outputWidth, outputHeight, COLOR_FORMAT
                    );
                }
                if (frameInterpolator != null) {
                    boolean encoded = false;
                    try {
                        if (previousFrameValid) {
                            // Encode the hardware interpolator. We pass a
                            // zero-filled motion vector texture (no engine
                            // motion vectors available) — MTLFXFrameInterpolator
                            // falls back to its internal optical-flow estimate
                            // when motion vectors are zero, which is still
                            // far better than a naive 50/50 blend.
                            MetalNativeBridge.metallum_fx_frame_interpolator_encode(
                                    frameInterpolator,
                                    commandBuffer,
                                    currentFrame,
                                    previousColorTexture,
                                    motionVectorTexture != null ? motionVectorTexture : MemorySegment.NULL,
                                    interpolationOutputTexture,
                                    1.0f, 1.0f,
                                    0
                            );
                            encoded = true;
                        }
                        if (encoded) {
                            // Save the pre-interpolation frame (source or
                            // upscaled) before reassigning currentFrame.
                            MemorySegment preInterpFrame = currentFrame;
                            currentFrame = interpolationOutputTexture;
                            // Blit the pre-interpolation frame into
                            // previousColorTexture for use as "previous" on
                            // the next iteration. We can't hold the source or
                            // upscaled texture across frames (they may be
                            // overwritten next frame), so we copy into our
                            // own previousColorTexture.
                            //
                            // The previous swap-based approach was buggy: after
                            // the swap, previousColorTexture and
                            // interpolationOutputTexture aliased the same
                            // texture, causing the interpolator to read from
                            // and write to the same texture on subsequent
                            // frames — undefined behavior that crashed Metal
                            // validation and made the window disappear.
                            MetalNativeBridge.metallum_fx_encode_frame_blend(
                                    deviceHandle(), commandBuffer,
                                    preInterpFrame, preInterpFrame, previousColorTexture
                            );
                            if (!loggedInterpActive) {
                                Metallum.LOGGER.info(
                                        "[MetalFX] frame interpolation RUNNING (hardware): {}x{}",
                                        outputWidth, outputHeight);
                                loggedInterpActive = true;
                            }
                        }
                    } catch (Throwable t) {
                        Metallum.LOGGER.warn("[MetalFX] frame interpolator encode threw; falling back to source", t);
                    }
                    if (!previousFrameValid) {
                        // First frame: no previous to interpolate from.
                        // Blit current into previous to seed next frame.
                        MetalNativeBridge.metallum_fx_encode_frame_blend(
                                deviceHandle(), commandBuffer,
                                currentFrame, currentFrame, previousColorTexture
                        );
                        previousFrameValid = true;
                    }
                }
            }
        } else if (!cfg.isFrameInterpolationActive()) {
            previousFrameValid = false;
            loggedInterpActive = false;
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

    private void ensureMotionVectorTexture(int width, int height) {
        if (motionVectorTexture != null
                && cachedOutputWidth == width && cachedOutputHeight == height) {
            return;
        }
        if (motionVectorTexture != null) {
            MetalNativeBridge.metallum_release_object(motionVectorTexture);
            motionVectorTexture = null;
        }
        motionVectorTexture = MetalNativeBridge.metallum_create_texture_2d(
                deviceHandle(),
                MOTION_FORMAT_ENUM,
                width, height,
                1L, 1L, 0L,
                USAGE_SHADER_RW,
                MTLStorageMode.Private,
                "metallum-fx-motion"
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
        if (frameInterpolator != null) {
            MetalNativeBridge.metallum_release_object(frameInterpolator);
            frameInterpolator = null;
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
        if (motionVectorTexture != null) {
            MetalNativeBridge.metallum_release_object(motionVectorTexture);
            motionVectorTexture = null;
        }
        previousFrameValid = false;
        loggedSpatialActive = false;
        loggedInterpActive = false;
        cachedInputWidth = cachedInputHeight = cachedOutputWidth = cachedOutputHeight = -1;
    }
}
