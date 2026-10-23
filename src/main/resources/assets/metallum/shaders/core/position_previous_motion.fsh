#version 330
#extension GL_ARB_separate_shader_objects : require

#include <metallum:motion_fragment.glsl>

layout(location = 0) out vec2 metallumMotionTarget;
layout(location = 1) out float metallumValidityTarget;

void main() {
#ifdef METALLUM_PREVIOUS_DEPTH_BIAS
    MetallumMotionSample metallumSample = metallumMotionSample();
#endif
    // water_mask is depth/mask geometry with no sampled alpha in its POSITION ABI.
    // Its source color target writes no color channels, but its depth still participates
    // in the frame-interpolator source contract, so encode exact geometry motion here.
#ifndef METALLUM_PREVIOUS_DEPTH_BIAS
    MetallumMotionSample metallumSample = metallumMotionSample();
#endif
    metallumMotionTarget = metallumSample.motion;
    metallumValidityTarget = metallumExportValidity(metallumSample);
}
