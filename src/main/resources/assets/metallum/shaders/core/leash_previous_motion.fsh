#version 330
#extension GL_ARB_separate_shader_objects : require

#include <metallum:motion_fragment.glsl>
layout(location = 2) flat in float metallumAttributeGuard;

layout(location = 0) out vec2 metallumMotionTarget;
layout(location = 1) out float metallumValidityTarget;

void main() {
#ifdef METALLUM_PREVIOUS_DEPTH_BIAS
    MetallumMotionSample metallumSample = metallumMotionSample();
#endif
    if (metallumAttributeGuard < -1.0) {
        discard;
    }
#ifndef METALLUM_PREVIOUS_DEPTH_BIAS
    MetallumMotionSample metallumSample = metallumMotionSample();
#endif
    metallumMotionTarget = metallumSample.motion;
    metallumValidityTarget = metallumExportValidity(metallumSample);
}
