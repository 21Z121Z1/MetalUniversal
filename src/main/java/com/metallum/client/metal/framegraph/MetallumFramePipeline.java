package com.metallum.client.metal.framegraph;

import java.util.List;
import java.util.Objects;

import static com.metallum.client.metal.framegraph.FramePass.Phase.FRAME_INTERPOLATION;
import static com.metallum.client.metal.framegraph.FramePass.Phase.MOTION_MERGE;
import static com.metallum.client.metal.framegraph.FramePass.Phase.PRESENT;
import static com.metallum.client.metal.framegraph.FramePass.Phase.REACTIVE_MASK;
import static com.metallum.client.metal.framegraph.FramePass.Phase.TEMPORAL_UPSCALE;
import static com.metallum.client.metal.framegraph.FramePass.Phase.TRANSPARENCY;
import static com.metallum.client.metal.framegraph.FramePass.Phase.UI;
import static com.metallum.client.metal.framegraph.FramePass.Phase.UI_COMPOSITION;
import static com.metallum.client.metal.framegraph.FramePass.Phase.WORLD_MRT;
import static com.metallum.client.metal.framegraph.ResourceDescriptor.ColorSpace.DATA;
import static com.metallum.client.metal.framegraph.ResourceDescriptor.ColorSpace.DISPLAY_NATIVE;
import static com.metallum.client.metal.framegraph.ResourceDescriptor.ColorSpace.LINEAR;
import static com.metallum.client.metal.framegraph.ResourceDescriptor.Lifetime.EXTERNAL;
import static com.metallum.client.metal.framegraph.ResourceDescriptor.Lifetime.HISTORY;
import static com.metallum.client.metal.framegraph.ResourceDescriptor.Lifetime.TRANSIENT;
import static com.metallum.client.metal.framegraph.ResourceDescriptor.PixelFormat.BGRA8_UNORM;
import static com.metallum.client.metal.framegraph.ResourceDescriptor.PixelFormat.DEPTH32_FLOAT;
import static com.metallum.client.metal.framegraph.ResourceDescriptor.PixelFormat.R8_UNORM;
import static com.metallum.client.metal.framegraph.ResourceDescriptor.PixelFormat.RG16_FLOAT;
import static com.metallum.client.metal.framegraph.ResourceDescriptor.SizeDomain.NATIVE_DISPLAY;
import static com.metallum.client.metal.framegraph.ResourceDescriptor.SizeDomain.RENDER;
import static com.metallum.client.metal.framegraph.SemanticResource.CAMERA_MOTION;
import static com.metallum.client.metal.framegraph.SemanticResource.COMPOSED_COLOR;
import static com.metallum.client.metal.framegraph.SemanticResource.CUTOUT_COVERAGE;
import static com.metallum.client.metal.framegraph.SemanticResource.DISOCCLUSION;
import static com.metallum.client.metal.framegraph.SemanticResource.FINAL_COLOR;
import static com.metallum.client.metal.framegraph.SemanticResource.INTERPOLATED_COLOR;
import static com.metallum.client.metal.framegraph.SemanticResource.MERGED_MOTION;
import static com.metallum.client.metal.framegraph.SemanticResource.OBJECT_MOTION;
import static com.metallum.client.metal.framegraph.SemanticResource.OBJECT_MOTION_VALIDITY;
import static com.metallum.client.metal.framegraph.SemanticResource.SCENE_COLOR;
import static com.metallum.client.metal.framegraph.SemanticResource.SCENE_DEPTH;
import static com.metallum.client.metal.framegraph.SemanticResource.UI_COLOR;
import static com.metallum.client.metal.framegraph.SemanticResource.UPSCALED_COLOR;

/**
 * The baseline pipeline this backend actually runs, expressed as a frame graph.
 *
 * <p>Every pass here maps to real work: the MRT world pass, the transparency
 * pass, the {@code metallum_motion_camera_v2} and {@code metallum_motion_merge_v2}
 * kernels, the {@code metallum_cutout_reactive_dilate} kernel, the MetalFX
 * temporal scaler, UI composition, the MetalFX frame interpolator and the
 * presenter. Passes appear only when the corresponding feature is enabled, so
 * the compiled graph describes the configuration that is running rather than a
 * superset of what the code could do.</p>
 */
public final class MetallumFramePipeline {
    private MetallumFramePipeline() {
    }

    /**
     * Which optional stages participate. These mirror the runtime switches in
     * {@code MetalFxConfig}; a disabled stage contributes neither a pass nor a
     * resource, and therefore no allocation slot.
     */
    public record Options(
            boolean temporalUpscaling,
            boolean frameInterpolation,
            boolean objectMotion,
            boolean cutoutReactive
    ) {
        public Options {
            if (frameInterpolation && !temporalUpscaling) {
                // The interpolator is linked to the temporal scaler; without the
                // scaler there is no scaled history for it to interpolate.
                throw new IllegalArgumentException("Frame interpolation requires temporal upscaling");
            }
            if (cutoutReactive && !temporalUpscaling) {
                throw new IllegalArgumentException("The reactive mask is only consumed by the temporal scaler");
            }
            if (objectMotion && !temporalUpscaling) {
                // Object motion exists to feed the scaler's motion input. Writing
                // the MRT attachments with nothing downstream would cost a full
                // RG16F target per frame for no effect.
                throw new IllegalArgumentException("Object motion requires temporal upscaling");
            }
        }

        /** Plain rasterisation: no MetalFX stage at all. */
        public static Options vanilla() {
            return new Options(false, false, false, false);
        }

        /** Temporal upscaling with the full motion and reactive input set. */
        public static Options fullTemporal() {
            return new Options(true, false, true, true);
        }

        /** Everything, including generated frames. */
        public static Options frameGeneration() {
            return new Options(true, true, true, true);
        }
    }

    public static CompiledFrameGraph compile(final Options options, final List<FrameGraphExtension> extensions) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(extensions, "extensions");

        // Without upscaling the scene is rasterised straight at display size, so
        // the scene resources genuinely live in the display domain. Claiming a
        // RENDER domain there would let the compiler alias a scene target with a
        // display-sized one on the grounds that the domains differ.
        ResourceDescriptor.SizeDomain sceneDomain = options.temporalUpscaling() ? RENDER : NATIVE_DISPLAY;

        FrameGraphBuilder graph = new FrameGraphBuilder()
                .resource(SCENE_COLOR, ResourceDescriptor.scalerInput(sceneDomain, BGRA8_UNORM, LINEAR, TRANSIENT))
                .resource(SCENE_DEPTH, ResourceDescriptor.scalerInput(sceneDomain, DEPTH32_FLOAT, DATA, TRANSIENT))
                .resource(UI_COLOR, ResourceDescriptor.attachment(NATIVE_DISPLAY, BGRA8_UNORM, DISPLAY_NATIVE, TRANSIENT))
                // The presenter pins the composed frame until the real drawable
                // reports its presented boundary, and the interpolator needs the
                // previous one, so this can never share a slot.
                .resource(COMPOSED_COLOR, ResourceDescriptor.scalerOutput(NATIVE_DISPLAY, BGRA8_UNORM, DISPLAY_NATIVE, HISTORY))
                .resource(FINAL_COLOR, ResourceDescriptor.presentTarget(NATIVE_DISPLAY, BGRA8_UNORM, DISPLAY_NATIVE));

        graph.pass("world-mrt", WORLD_MRT, pass -> {
            pass.write(SCENE_COLOR).write(SCENE_DEPTH);
            if (options.objectMotion()) {
                pass.write(OBJECT_MOTION).write(OBJECT_MOTION_VALIDITY);
            }
            if (options.cutoutReactive()) {
                pass.write(CUTOUT_COVERAGE);
            }
        });
        graph.pass("transparency", TRANSPARENCY, pass -> pass.readWrite(SCENE_COLOR).read(SCENE_DEPTH));

        if (options.temporalUpscaling()) {
            graph.resource(CAMERA_MOTION, ResourceDescriptor.computeTarget(RENDER, RG16_FLOAT, DATA, TRANSIENT))
                    .resource(MERGED_MOTION, ResourceDescriptor.scalerInput(RENDER, RG16_FLOAT, DATA, TRANSIENT))
                    .resource(DISOCCLUSION, ResourceDescriptor.computeTarget(RENDER, R8_UNORM, DATA, TRANSIENT))
                    .resource(UPSCALED_COLOR, ResourceDescriptor.scalerOutput(NATIVE_DISPLAY, BGRA8_UNORM, DISPLAY_NATIVE, TRANSIENT));

            // metallum_motion_camera_v2: camera motion reconstructed from depth.
            graph.pass("motion-camera", MOTION_MERGE, pass -> pass.read(SCENE_DEPTH).write(CAMERA_MOTION));

            if (options.objectMotion()) {
                graph.resource(OBJECT_MOTION, ResourceDescriptor.computeTarget(RENDER, RG16_FLOAT, DATA, TRANSIENT))
                        .resource(OBJECT_MOTION_VALIDITY, ResourceDescriptor.computeTarget(RENDER, R8_UNORM, DATA, TRANSIENT));
                // metallum_motion_merge_v2: an invalid object sample falls back
                // to camera motion and rejects history rather than becoming a
                // zero velocity, per MetalMotionContract.merge.
                graph.pass("motion-merge", MOTION_MERGE, pass -> pass
                        .read(CAMERA_MOTION)
                        .read(OBJECT_MOTION)
                        .read(OBJECT_MOTION_VALIDITY)
                        .read(SCENE_DEPTH)
                        .write(MERGED_MOTION)
                        .write(DISOCCLUSION));
            } else {
                graph.pass("motion-merge", MOTION_MERGE, pass -> pass
                        .read(CAMERA_MOTION)
                        .read(SCENE_DEPTH)
                        .write(MERGED_MOTION)
                        .write(DISOCCLUSION));
            }

            if (options.cutoutReactive()) {
                graph.resource(CUTOUT_COVERAGE, ResourceDescriptor.computeTarget(RENDER, R8_UNORM, DATA, TRANSIENT))
                        .resource(SemanticResource.REACTIVE_MASK,
                                ResourceDescriptor.scalerInput(RENDER, R8_UNORM, DATA, TRANSIENT));
                // metallum_cutout_reactive_dilate. The reactive band is capped
                // rather than covering every covered pixel; see
                // docs/cutout-shimmer-remediation-2026-07-27.md.
                graph.pass("reactive-mask", REACTIVE_MASK, pass -> pass
                        .read(CUTOUT_COVERAGE)
                        .read(SCENE_DEPTH)
                        .read(DISOCCLUSION)
                        .write(SemanticResource.REACTIVE_MASK));
            }

            graph.pass("temporal-upscale", TEMPORAL_UPSCALE, pass -> {
                pass.read(SCENE_COLOR).read(SCENE_DEPTH).read(MERGED_MOTION);
                if (options.cutoutReactive()) {
                    pass.read(SemanticResource.REACTIVE_MASK);
                }
                pass.write(UPSCALED_COLOR);
            });
        }

        graph.pass("ui", UI, pass -> pass.write(UI_COLOR));
        graph.pass("ui-composition", UI_COMPOSITION, pass -> pass
                .read(options.temporalUpscaling() ? UPSCALED_COLOR : SCENE_COLOR)
                .read(UI_COLOR)
                .write(COMPOSED_COLOR));

        if (options.frameInterpolation()) {
            graph.resource(INTERPOLATED_COLOR,
                    ResourceDescriptor.scalerOutput(NATIVE_DISPLAY, BGRA8_UNORM, DISPLAY_NATIVE, TRANSIENT));
            graph.pass("frame-interpolation", FRAME_INTERPOLATION, pass -> pass
                    .read(COMPOSED_COLOR)
                    .read(MERGED_MOTION)
                    .read(SCENE_DEPTH)
                    .write(INTERPOLATED_COLOR));
        }

        graph.pass("present", PRESENT, pass -> {
            pass.read(COMPOSED_COLOR);
            if (options.frameInterpolation()) {
                pass.read(INTERPOLATED_COLOR);
            }
            pass.write(FINAL_COLOR);
        });

        for (FrameGraphExtension extension : List.copyOf(extensions)) {
            Objects.requireNonNull(extension, "extension");
            if (extension.isEnabled()) {
                extension.declare(graph);
            }
        }
        return graph.compile();
    }
}
