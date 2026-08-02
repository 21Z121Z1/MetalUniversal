package com.metallum.client.metal.framegraph;

/**
 * Stable semantic names for the render targets this backend exchanges between
 * passes.
 *
 * <p>An extension declares the resources it reads and writes by semantic name
 * only. No {@code MTLTexture} handle and no native pointer ever crosses an
 * extension boundary: the compiled graph hands the backend an allocation slot
 * index, and the backend alone owns the mapping from slot to Metal object. That
 * is what makes it safe to let a shader pack declare passes without giving it
 * the ability to retain or free a native texture.</p>
 *
 * <p>The set is deliberately limited to resources this renderer actually
 * produces. A name with no producer is a liability, not a feature: it compiles,
 * it reads as supported, and it fails at runtime.</p>
 */
public enum SemanticResource {
    /** Scene colour as the world pass rasterises it, at render resolution. */
    SCENE_COLOR,
    /** Scene depth written by the world pass. */
    SCENE_DEPTH,
    /** Camera-only screen-space motion, {@code RG16_FLOAT}, NDC per the motion contract. */
    CAMERA_MOTION,
    /** Per-object screen-space motion from the entity motion pipeline. */
    OBJECT_MOTION,
    /** Per-pixel validity of {@link #OBJECT_MOTION}; zero means "fall back to camera motion". */
    OBJECT_MOTION_VALIDITY,
    /** Camera/object motion merged by the compute kernel; the scaler's motion input. */
    MERGED_MOTION,
    /** Disocclusion signal produced alongside the merge. */
    DISOCCLUSION,
    /** Alpha-test coverage written by the CUTOUT reactive MRT attachment. */
    CUTOUT_COVERAGE,
    /** Reactive mask consumed by the temporal scaler. */
    REACTIVE_MASK,
    /** User interface colour, at native display resolution. */
    UI_COLOR,
    /** Temporal scaler output, at native display resolution. */
    UPSCALED_COLOR,
    /** Scene and UI composed; the presenter's source frame. */
    COMPOSED_COLOR,
    /** Frame interpolator output presented between two real frames. */
    INTERPOLATED_COLOR,
    /** The drawable this frame presents. Externally owned. */
    FINAL_COLOR
}
